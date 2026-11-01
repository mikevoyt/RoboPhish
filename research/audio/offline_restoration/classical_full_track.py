"""One fixed full-track extension; exact streaming implementation of frozen probe.

The full-track profile is estimated from the complete song. The earlier audition
profiles were estimated separately from their twenty-second passages. This is
validation only: no reference/configuration tuning and no Android integration.
"""
import argparse
from datetime import datetime, timezone
import json
import math
from pathlib import Path
import resource
import time

import numpy as np
from scipy import fft, signal

import classical_probe as c
import evaluate as ev

CONFIG=c.CONFIG
DATE='2016-10-31'
BLOCK_FRAMES=128
WINDOW_SECONDS=20
EVIDENCE=c.EVIDENCE


def frame_blocks(path,rate,channels=2,dtype='<i2'):
    """Match scipy.signal.stft boundary=zeros, padded=True, scaling=spectrum."""
    samples=np.memmap(path,dtype=dtype,mode='r').reshape(-1,channels)
    length=len(samples);size,hop=CONFIG['fft_size'],CONFIG['hop']
    count=1+math.ceil(length/hop)
    window=signal.get_window('hann',size,fftbins=True)
    scale=32768. if dtype=='<i2' else 1.
    for frame in range(0,count,BLOCK_FRAMES):
        number=min(BLOCK_FRAMES,count-frame)
        start=frame*hop-size//2
        stop=(frame+number-1)*hop+size//2
        block=np.zeros((stop-start,channels))
        lo,hi=max(0,start),min(length,stop)
        if hi>lo:block[lo-start:hi-start]=samples[lo:hi]/scale
        windows=np.lib.stride_tricks.sliding_window_view(block,size,axis=0)[::hop]
        z=fft.rfft(windows*window,axis=-1).transpose(1,2,0)/window.sum()
        yield frame,z


def collect_profile(path,rate):
    frequency,masks=c.bands(rate);power_sum=np.zeros(len(frequency))
    broad_parts=[];band_parts=[];flux_parts=[];previous=None
    for _,z in frame_blocks(path,rate):
        squared=np.abs(z)**2
        power_sum+=squared.sum(axis=(0,2))
        magnitude=np.sqrt(squared.mean(axis=0));power=magnitude**2
        broad_parts.append(power.sum(axis=0))
        band_parts.append(np.stack([10*np.log10(np.maximum(power[m].sum(axis=0),1e-25)) for m in masks]))
        preceding=np.concatenate([magnitude[:,:1] if previous is None else previous[:,None],magnitude[:,:-1]],axis=1)
        flux_parts.append(np.maximum(magnitude-preceding,0).sum(axis=0)/np.maximum(preceding.sum(axis=0),1e-20))
        previous=magnitude[:,-1]
    broad=np.concatenate(broad_parts)
    return power_sum,np.concatenate(band_parts,axis=1),broad,np.concatenate(flux_parts)


def tone_from_power(power,rate,reference):
    frequency,masks=c.bands(rate)
    observed=c.relative_levels(power,masks)
    lower=np.asarray(reference['q25_db'])-CONFIG['uncertainty_margin_db']
    upper=np.asarray(reference['q75_db'])+CONFIG['uncertainty_margin_db']
    residual=np.where(observed<lower,lower-observed,np.where(observed>upper,upper-observed,0))
    gains=np.clip(residual*CONFIG['residual_fraction'],-CONFIG['maximum_tonal_gain_db'],CONFIG['maximum_tonal_gain_db'])
    curve=c.frequency_curve(gains,frequency)
    low,high=CONFIG['boost_allowed_hz'];lo_full,hi_full=CONFIG['boost_full_strength_hz']
    low_factor=np.clip(np.log2(np.maximum(frequency,1)/low)/np.log2(lo_full/low),0,1)
    high_factor=np.clip(np.log2(high/np.maximum(frequency,1))/np.log2(high/hi_full),0,1)
    curve=np.minimum(curve,0)+np.maximum(curve,0)*low_factor*high_factor
    return curve,dict(observed_relative_band_db=observed.tolist(),cohort_lower_db=lower.tolist(),
                      cohort_upper_db=upper.tolist(),bounded_raw_band_gain_db=gains.tolist(),
                      maximum_applied_boost_db=max(0.,float(curve.max())),maximum_applied_cut_db=max(0.,float(-curve.min())))


def temporal_dynamics(band_levels,broad,flux,rate):
    """Identical global percentiles, protection and continuous smoother as probe."""
    level=10*np.log10(np.maximum(broad,1e-25))
    quiet=level<=np.percentile(level,CONFIG['protected_quiet_percentile'])
    threshold=max(CONFIG['onset_minimum_positive_flux_fraction'],float(np.percentile(flux,CONFIG['onset_flux_percentile'])))
    onset=flux>threshold
    rises=np.r_[False,np.diff(level)>CONFIG['broadband_rise_protection_db']]
    hold=max(1,math.ceil(CONFIG['protection_hold_ms']*.001*rate/CONFIG['hop']))
    protected=quiet.copy()
    for index in np.flatnonzero(onset|rises):protected[index:index+hold]=True
    active=~quiet
    if not active.any():
        return np.zeros_like(band_levels),dict(maximum_dynamic_attenuation_db=0.,protected_frame_fraction=1.,
            active_dynamic_frame_fraction=0.,maximum_protected_frame_attenuation_db=0.,thresholds_db=None,
            onset_threshold_fraction=threshold,abstention_reason='No frames above the quiet threshold')
    thresholds=np.percentile(band_levels[:,active],CONFIG['dynamics_threshold_percentile'],axis=1)
    desired=np.clip((band_levels-thresholds[:,None])*(1-1/CONFIG['dynamics_ratio']),0,CONFIG['maximum_dynamic_attenuation_db'])
    attack=np.exp(-CONFIG['hop']/(rate*CONFIG['attack_ms']*.001));release=np.exp(-CONFIG['hop']/(rate*CONFIG['release_ms']*.001))
    state=np.zeros(band_levels.shape[0]);smoothed=np.zeros_like(desired)
    for frame in range(desired.shape[1]):
        if protected[frame]:state[:]=0
        else:
            coefficient=np.where(desired[:,frame]>state,attack,release)
            state=coefficient*state+(1-coefficient)*desired[:,frame]
        smoothed[:,frame]=state
    smoothed[:,protected]=0
    # Maximum/active fraction measured after interpolation in render as in probe.
    return smoothed,dict(protected_frame_fraction=float(protected.mean()),
                         maximum_protected_frame_attenuation_db=0.,thresholds_db=thresholds.tolist(),
                         onset_threshold_fraction=threshold)


class WolaWriter:
    """Finalize hops only after all overlapping frames have contributed."""
    def __init__(self,path,frames):
        self.file=path.open('wb');self.frames=frames;self.written=0;self.position=0
        self.size=CONFIG['fft_size'];self.hop=CONFIG['hop']
        self.window=signal.get_window('hann',self.size,fftbins=True)
        self.buffer=np.zeros((self.size,2));self.norm=np.zeros(self.size)

    def emit(self,count):
        left,right=self.size//2,self.size//2+self.frames
        lo,hi=max(self.position,left),min(self.position+count,right)
        if hi>lo:
            a,b=lo-self.position,hi-self.position
            if np.any(self.norm[a:b]<=1e-10):raise RuntimeError('Invalid WOLA overlap normalization')
            audio=self.buffer[a:b]/self.norm[a:b,None]
            if not np.all(np.isfinite(audio)):raise RuntimeError('Nonfinite reconstructed audio')
            self.file.write(audio.astype('<f8').tobytes());self.written+=len(audio)
        self.position+=count

    def accept(self,spectrum):
        frame=fft.irfft(spectrum,axis=-1,n=self.size).T*self.window.sum()
        self.buffer+=frame*self.window[:,None];self.norm+=self.window**2
        self.emit(self.hop)
        self.buffer[:-self.hop]=self.buffer[self.hop:];self.buffer[-self.hop:]=0
        self.norm[:-self.hop]=self.norm[self.hop:];self.norm[-self.hop:]=0

    def finish(self):
        self.emit(self.size-self.hop);self.file.close()
        if self.written!=self.frames:raise RuntimeError('WOLA output frame count mismatch')


def render_stream(source,rate,curve,smoothed,outputs):
    frames=source.stat().st_size//4
    writers={name:WolaWriter(path,frames) for name,path in outputs.items()}
    frequency,_=c.bands(rate);centers=np.asarray(CONFIG['band_centers_hz'],dtype=float)
    log_frequency=np.log2(np.maximum(frequency,centers[0]));log_centers=np.log2(centers)
    maximum=0.;active=0;total=0
    for start,z in frame_blocks(source,rate):
        for offset in range(z.shape[-1]):
            reduction=np.interp(log_frequency,log_centers,smoothed[:,start+offset])
            maximum=max(maximum,float(reduction.max()));active+=int(reduction.max()>.01);total+=1
            for name,writer in writers.items():
                gain_db=curve-(reduction if name=='tonal_plus_dynamics' else 0)
                writer.accept(z[:,:,offset]*10**(gain_db[None,:]/20))
    for writer in writers.values():writer.finish()
    return dict(maximum_dynamic_attenuation_db=maximum,active_dynamic_frame_fraction=active/max(total,1))


def peak_and_quantize(source,destination):
    x=np.memmap(source,dtype='<f8',mode='r').reshape(-1,2);peak=0.
    for start in range(0,len(x),262144):
        end=min(len(x),start+262144);lo=max(0,start-64);hi=min(len(x),end+64)
        block=np.asarray(x[lo:hi]);up=signal.resample_poly(block,4,1,axis=0)
        peak=max(peak,float(np.max(np.abs(up[(start-lo)*4:(end-lo)*4]))))
    headroom=min(1.,10**(CONFIG['output_peak_limit_dbtp']/20)/max(peak,1e-25))
    with destination.open('wb') as file:
        for start in range(0,len(x),262144):
            audio=np.asarray(x[start:start+262144])*headroom
            if not np.all(np.isfinite(audio)) or np.max(np.abs(audio))>=1:raise RuntimeError('Unsafe PCM output')
            file.write(np.rint(audio*32768).astype('<i2').tobytes())
    return dict(pre_headroom_oversampled_peak_dbfs=ev.db(peak),headroom_gain_db=ev.db(headroom))


def equivalence(output):
    """Natural selected passage + boundary test; no full-track result inspected."""
    output.mkdir(parents=True,exist_ok=True);reference=json.loads((EVIDENCE/'classical-reference.json').read_text())
    source=c.sources()['1991-03-09'];full=output/'equivalence-source.s16'
    ev.decode(source['path'],full,source['sample_rate'],2)
    all_samples=np.memmap(full,dtype='<i2',mode='r').reshape(-1,2)
    start=len(all_samples)//2;x=np.asarray(all_samples[start:start+44100*20],dtype=np.float64)/32768
    del all_samples;full.unlink()
    rng=np.random.default_rng(20261102)
    synthetic=rng.normal(0,.03,(48000+37,2));synthetic[0]=[.6,-.4];synthetic[-1]=[-.3,.2]
    cases=[('selected-natural-1991-03-09',44100,x),('48000-boundary-impulses',48000,synthetic),('all-quiet',44100,np.zeros((44100+37,2)))]
    rows=[]
    for name,rate,audio in cases:
        raw=output/(name+'.s16');np.rint(audio*32768).astype('<i2').tofile(raw)
        audio=np.fromfile(raw,dtype='<i2').reshape(-1,2).astype(float)/32768
        z=c.stft(audio,rate);curve,_=c.tonal_curve(z,rate,reference);dynamic,_=c.dynamic_curve(z,rate)
        streamed=np.concatenate([block for _,block in frame_blocks(raw,rate)],axis=-1)
        stft_error=float(np.max(np.abs(z-streamed)))
        p,levels,broad,flux=collect_profile(raw,rate);actual_curve,_=tone_from_power(p,rate,reference)
        smooth,_=temporal_dynamics(levels,broad,flux,rate)
        paths={v:output/(name+'-'+v+'.f64') for v in CONFIG['variants']}
        render_stream(raw,rate,actual_curve,smooth,paths)
        profile_error=float(np.max(np.abs(curve-actual_curve)))
        frequency,_=c.bands(rate);centers=np.asarray(CONFIG['band_centers_hz'],dtype=float)
        dyn=np.stack([-np.interp(np.log2(np.maximum(frequency,centers[0])),np.log2(centers),smooth[:,j]) for j in range(smooth.shape[1])],axis=1)
        dynamic_error=float(np.max(np.abs(dynamic-dyn)))
        errors={}
        for variant,path in paths.items():
            actual=np.fromfile(path,dtype='<f8').reshape(-1,2)
            desired=c.inverse(z*10**((curve[:,None]+(dynamic if variant=='tonal_plus_dynamics' else 0))[None,:,:]/20),rate,len(audio))
            if actual.shape!=desired.shape:raise RuntimeError('Streaming equivalence frame failure')
            errors[variant]=float(np.max(np.abs(actual-desired)))
            path.unlink()
        unity_path=output/(name+'-unity.f64')
        render_stream(raw,rate,np.zeros_like(curve),np.zeros_like(smooth),{'tonal_only':unity_path})
        unity=np.fromfile(unity_path,dtype='<f8').reshape(-1,2)
        unity_error=float(np.max(np.abs(audio-unity)));unity_path.unlink();raw.unlink()
        row=dict(case=name,sample_rate=rate,frames=len(audio),stft_max_error=stft_error,
                 tonal_curve_max_error_db=profile_error,dynamic_curve_max_error_db=dynamic_error,
                 rendered_max_absolute_error=errors,unity_max_absolute_error=unity_error)
        rows.append(row)
        if max(stft_error,profile_error,dynamic_error,unity_error,*errors.values())>1e-10:
            raise RuntimeError('Streaming equivalence failed: '+json.dumps(row))
    return rows


def prepare(output):
    c.verify_freeze();out=output/'equivalence';out.mkdir(parents=True,exist_ok=True)
    path=EVIDENCE/'classical-full-track-preregistration.json'
    if path.exists():verify();return
    rows=equivalence(out);ev.file_hash(Path(__file__))
    c.write(EVIDENCE/'classical-full-track-equivalence.json',dict(cases=rows,threshold=1e-10,passed=True))
    source=c.sources()[DATE]
    prereg=dict(frozen_at_utc=datetime.now(timezone.utc).isoformat(),date=DATE,title=source['title'],
                source_sha256=source['sha256'],script_sha256=ev.file_hash(Path(__file__)),
                original_preregistration_sha256=ev.file_hash(EVIDENCE/'classical-preregistration.json'),
                reference_sha256=ev.file_hash(EVIDENCE/'classical-reference.json'),
                equivalence_sha256=ev.file_hash(EVIDENCE/'classical-full-track-equivalence.json'),
                config=CONFIG,block_frames=BLOCK_FRAMES,window_seconds=WINDOW_SECONDS,
                profile_scope='Complete selected track. Earlier six audition profiles were each estimated from a separate 20 s passage.',
                protocol='One reserved full track, two frozen variants, no tuning. Full-track, fixed nonoverlapping 20 s windows, and first/last 1 s edge metrics with reconstructed-output phase; retain failures and undefined metrics. Exact WOLA trimming and continuous global dynamics state.',
                before_full_track_processing=True)
    initial=EVIDENCE/'classical-full-track-preregistration-initial.json'
    if initial.exists():
        prereg['superseded_validation_preregistration_sha256']=ev.file_hash(initial)
        prereg['supersession_reason']='Parent review requested explicit 1 s edge diagnostics after initial wrapper freeze completed, before any full-track candidate render. Only validation/reporting changed; processing parameters and transform unchanged.'
    c.write(path,prereg);print('FULL_TRACK_FROZEN',ev.file_hash(path),flush=True)


def verify():
    c.verify_freeze();p=json.loads((EVIDENCE/'classical-full-track-preregistration.json').read_text())
    checks={'script_sha256':Path(__file__),'original_preregistration_sha256':EVIDENCE/'classical-preregistration.json',
            'reference_sha256':EVIDENCE/'classical-reference.json','equivalence_sha256':EVIDENCE/'classical-full-track-equivalence.json'}
    for key,path in checks.items():
        if ev.file_hash(path)!=p[key]:raise RuntimeError('Frozen full-track extension changed: '+key)
    if p['config']!=CONFIG:raise RuntimeError('Frozen config changed')
    return p


def reconstructed_phase(source,processed,rate):
    """Audit actual PCM with fresh STFTs, in bounded blocks; not mask identity."""
    total_weight=weighted_error=0.;raw_maximum=0.
    for (a_index,a),(b_index,b) in zip(frame_blocks(source,rate),frame_blocks(processed,rate)):
        if a_index!=b_index or a.shape!=b.shape:raise RuntimeError('Phase audit alignment failure')
        original_cross=a[0]*a[1].conj();processed_cross=b[0]*b[1].conj()
        delta=np.abs(np.angle(processed_cross*original_cross.conj()))
        weight=np.sqrt(np.abs(original_cross)*np.abs(processed_cross))
        total_weight+=float(weight.sum());weighted_error+=float((delta*weight).sum())
        raw_maximum=max(raw_maximum,float(delta.max()))
    return dict(weighted_reconstructed_phase_error_degrees=weighted_error/total_weight*180/np.pi if total_weight>1e-20 else None,
                phase_support_defined=total_weight>1e-20,total_cross_spectral_weight=total_weight,
                maximum_unweighted_phase_error_radians=raw_maximum,
                caveat='Unweighted weak-bin maxima are retained; a small weighted error does not establish every-bin phase preservation.')


def window_metrics(source,processed,rate,output,segments=None):
    x=np.memmap(source,dtype='<i2',mode='r').reshape(-1,2)
    rendered={name:np.memmap(path,dtype='<i2',mode='r').reshape(-1,2) for name,path in processed.items()}
    rows=[];length=WINDOW_SECONDS*rate
    edges=segments is not None
    if segments is None:segments=[(start,min(len(x),start+length)) for start in range(0,len(x),length)]
    for start,end in segments:
        if end-start<rate:raise RuntimeError('Unplanned subsecond final evaluation window')
        raw=output/'window-original.s16';np.asarray(x[start:end]).tofile(raw)
        a,ae,af=ev.metrics(raw,rate,2);variants=[]
        for name,y in rendered.items():
            destination=output/'window-processed.s16';np.asarray(y[start:end]).tofile(destination)
            b,be,bf=ev.metrics(destination,rate,2)
            variants.append(dict(variant=name,original=a,processed=b,comparison=ev.compare(a,b,ae,be,af,bf),
                                 phase=reconstructed_phase(raw,destination,rate)))
            destination.unlink()
        raw.unlink();rows.append(dict(kind=('first_second' if start==0 else 'last_second') if edges else 'fixed_20_second_window',
                                      start_seconds=start/rate,end_seconds=end/rate,frames=end-start,variants=variants))
    return rows


def finite_report(value,path='',undefined=None):
    """Keep undefined metrics explicit without writing invalid JSON NaN tokens."""
    if undefined is None:undefined=[]
    if isinstance(value,float) and not math.isfinite(value):
        undefined.append(path);return None
    if isinstance(value,dict):return {key:finite_report(item,path+'.'+key,undefined) for key,item in value.items()}
    if isinstance(value,list):return [finite_report(item,path+'['+str(index)+']',undefined) for index,item in enumerate(value)]
    return value


def run_full(output):
    frozen=verify();source=c.sources()[DATE];rate=source['sample_rate']
    if ev.file_hash(source['path'])!=frozen['source_sha256']:raise RuntimeError('Source changed')
    output.mkdir(parents=True,exist_ok=True);original=output/(DATE+'-original.s16')
    ev.decode(source['path'],original,rate,2);frames=original.stat().st_size//4
    started=time.monotonic();power,levels,broad,flux=collect_profile(original,rate)
    reference=json.loads((EVIDENCE/'classical-reference.json').read_text())
    curve,tone=tone_from_power(power,rate,reference);smooth,dynamic=temporal_dynamics(levels,broad,flux,rate)
    profile_seconds=time.monotonic()-started
    float_paths={name:output/(DATE+'-'+name+'.f64') for name in CONFIG['variants']}
    started=time.monotonic();dynamic.update(render_stream(original,rate,curve,smooth,float_paths))
    reconstruction_seconds=time.monotonic()-started
    processed={name:output/(DATE+'-'+name+'.s16') for name in CONFIG['variants']}
    quantization={}
    for name,path in float_paths.items():
        quantization[name]=peak_and_quantize(path,processed[name]);path.unlink()
    print('RENDERED_FULL_TRACK',frames/rate,'seconds',flush=True)
    a,ae,af=ev.metrics(original,rate,2);variants=[]
    for name,path in processed.items():
        b,be,bf=ev.metrics(path,rate,2);comparison=ev.compare(a,b,ae,be,af,bf)
        variants.append(dict(variant=name,metrics=b,comparison=comparison,**quantization[name],pcm_sha256=ev.file_hash(path),
                             phase=reconstructed_phase(original,path,rate)))
        ev.run(['ffmpeg','-v','error','-y','-f','s16le','-ar',rate,'-ac',2,'-i',path,'-c:a','pcm_s16le',output/(DATE+'-'+name+'.wav')])
    windows=window_metrics(original,processed,rate,output)
    edges=window_metrics(original,processed,rate,output,[(0,rate),(frames-rate,frames)])
    failures=[dict(start_seconds=w['start_seconds'],variant=v['variant'],failed_checks=[k for k,val in v['comparison']['provisional_checks'].items() if not val]) for w in windows for v in w['variants'] if not v['comparison']['all_preservation_checks_pass']]
    edge_failures=[dict(edge=w['kind'],variant=v['variant'],failed_checks=[k for k,val in v['comparison']['provisional_checks'].items() if not val]) for w in edges for v in w['variants'] if not v['comparison']['all_preservation_checks_pass']]
    report=dict(date=DATE,title=source['title'],complete=True,source_sha256=source['sha256'],
                full_track_preregistration_sha256=ev.file_hash(EVIDENCE/'classical-full-track-preregistration.json'),
                profile_scope=frozen['profile_scope'],frames=frames,duration_seconds=frames/rate,sample_rate=rate,
                original=a,tonal_analysis=tone,dynamics_analysis=dynamic,variants=variants,windows=windows,edge_windows=edges,
                summary=dict(full_track_checks_pass=all(v['comparison']['all_preservation_checks_pass'] for v in variants),
                             window_count=len(windows),variant_window_checks=len(windows)*2,window_failures=failures,
                             edge_window_count=2,edge_variant_checks=4,edge_failures=edge_failures),
                timing=dict(profile_seconds=profile_seconds,both_variant_reconstruction_seconds=reconstruction_seconds),
                resource_usage=dict(process_peak_rss_bytes=resource.getrusage(resource.RUSAGE_SELF).ru_maxrss,
                                    platform='macOS; ru_maxrss bytes',fft_block_frames=BLOCK_FRAMES),
                quality_claim='No subjective preference, soundboard-equivalence, generalization or Android-readiness claim. A single reserved track is a limited engineering validation.')
    verify()
    if ev.file_hash(source['path'])!=source['sha256']:raise RuntimeError('Source changed after processing')
    undefined=[];report=finite_report(report,undefined=undefined);report['undefined_metric_paths']=undefined
    c.write(EVIDENCE/'classical-full-track-results.json',report);c.write(output/'full-track-results.json',report)
    print(json.dumps(report['summary'],indent=2),flush=True)


def main():
    parser=argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--output',type=Path,required=True)
    parser.add_argument('--action',choices=['prepare','render'],required=True)
    args=parser.parse_args();(prepare if args.action=='prepare' else run_full)(args.output)


if __name__=='__main__':main()
