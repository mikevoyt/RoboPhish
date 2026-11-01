"""Preregistered, bounded cohort-tone/dynamics experiment; no clean-reference claim.

Fit only sixteen development full recordings, then freeze script/config/reference
before rendering four selected passages followed by two reserved passages. This
research probe never changes the Android processor or original evaluation split.
"""
import argparse
from datetime import datetime, timezone
import hashlib
import json
import math
from pathlib import Path
import subprocess
import time

import numpy as np
import pyloudnorm as pyln
from scipy import ndimage, signal
import soundfile as sf

import evaluate as ev

ROOT = Path(__file__).resolve().parent
TASK = Path('/Users/mikevoyt/Documents/Codex/2026-11-01/task-3')
EVIDENCE = ROOT / 'evidence'
CONFIG = dict(
    version='classical-cohort-v1', fft_size=4096, hop=1024,
    band_centers_hz=[125,250,500,1000,2000,4000,8000,16000],
    reference_block_seconds=20, reference_hop=8192,
    normalization='For each block, octave-band summed power divided by total FFT power over all bins/channels; dB. Median blocks per recording, then equal-weight cohort median and quartiles.',
    uncertainty_margin_db=1.5, residual_fraction=.5, maximum_tonal_gain_db=2.,
    log_frequency_smoothing_sigma_octaves=.75,
    boost_allowed_hz=[50,3500], boost_full_strength_hz=[88.3883476483,2000],
    dynamics_ratio=1.25, dynamics_threshold_percentile=65,
    maximum_dynamic_attenuation_db=1., attack_ms=80, release_ms=400,
    protected_quiet_percentile=20, onset_flux_percentile=75,
    onset_minimum_positive_flux_fraction=.015, protection_hold_ms=80,
    broadband_rise_protection_db=.7,
    output_peak_limit_dbtp=-1., passage_seconds=20,
    variants=['tonal_only','tonal_plus_dynamics'],
    selected_dates=['1991-03-09','1990-04-29','1995-12-07','2009-08-07'],
    reserved_dates=['1992-12-31','2025-07-26'],
    passage_rule='Start at 50% of full decoded source duration, take next 20 seconds; no content search.',
    interpretation='Cohort is varied audience audio, not a clean or soundboard reference. No denoiser, dereverberator, gap repair, source separation or generated identity.',
)


def write(path, value):
    path.parent.mkdir(parents=True, exist_ok=True)
    path.write_text(json.dumps(value, indent=2) + '\n')


def bands(rate):
    frequency = np.fft.rfftfreq(CONFIG['fft_size'], 1/rate)
    centers = np.asarray(CONFIG['band_centers_hz'])
    masks = [(frequency >= c/np.sqrt(2)) & (frequency < min(c*np.sqrt(2),rate/2)) for c in centers]
    return frequency, masks


def relative_levels(power, masks):
    total = np.maximum(power.sum(axis=-1), 1e-25)
    return np.stack([10*np.log10(np.maximum(power[...,m].sum(axis=-1),1e-25)/total) for m in masks], axis=-1)


def sources():
    original = json.loads((ROOT/'acquisition.json').read_text())['tracks']
    extra = json.loads((TASK/'audio-hard-cases/acquisition.json').read_text())['tracks']
    return {r['date']:{**r,'path':str(TASK/('audio-hard-cases' if r in extra else 'audio-corpus')/(r['date']+'.mp3'))} for r in original+extra}


def stft(x, rate):
    return signal.stft(x.T, fs=rate, window='hann', nperseg=CONFIG['fft_size'],
                       noverlap=CONFIG['fft_size']-CONFIG['hop'], boundary='zeros', padded=True)[2]


def inverse(z, rate, frames):
    return signal.istft(z, fs=rate, window='hann', nperseg=CONFIG['fft_size'],
                        noverlap=CONFIG['fft_size']-CONFIG['hop'], boundary=True)[1].T[:frames]


def unity_checks():
    rng = np.random.default_rng(20261101)
    outcomes=[]
    for rate in [44100,48000]:
        x=rng.normal(0,.03,(rate+37,2));x[0]=[.6,-.4];x[-1]=[-.3,.2]
        y=inverse(stft(x,rate),rate,len(x))
        error=float(np.max(np.abs(x-y)))
        if y.shape!=x.shape or error>1e-12:
            raise RuntimeError('Unity STFT reconstruction or edge trim failed')
        outcomes.append(dict(sample_rate=rate,frames=len(x),max_absolute_error=error))
    return outcomes


def reference_levels(path, rate):
    _, masks=bands(rate)
    block_frames=int(CONFIG['reference_block_seconds']*rate)
    size,hop=CONFIG['fft_size'],CONFIG['reference_hop']
    levels=[];window=np.hanning(size)
    with sf.SoundFile(path) as audio:
        if audio.samplerate!=rate or audio.channels!=2:
            raise RuntimeError('Unexpected development PCM format')
        while True:
            block=audio.read(block_frames,dtype='float64',always_2d=True)
            if len(block)<size:
                break
            frames=np.lib.stride_tricks.sliding_window_view(block,size,axis=0)[::hop]
            power=np.abs(np.fft.rfft(frames*window,axis=-1))**2
            spectrum=power.sum(axis=(0,1))
            if spectrum.sum()>1e-20:
                levels.append(relative_levels(spectrum,masks).tolist())
    return levels


def prepare(output):
    output.mkdir(parents=True,exist_ok=True)
    prereg=EVIDENCE/'classical-preregistration.json'
    if prereg.exists():
        verify_freeze()
        print('Existing frozen experiment verified; no refit',flush=True)
        return
    all_sources=sources();dev=[r for r in all_sources.values() if r['split']=='development']
    if len(dev)!=16:
        raise RuntimeError('Expected exactly sixteen development recordings')
    records=[]
    for row in dev:
        path=TASK/'audio-evaluation-final-development'/(row['date']+'-enhanced.wav')
        evidence=json.loads((ROOT/'evidence/final-development.json').read_text())['tracks']
        prior=next(r for r in evidence if r['metadata']['date']==row['date'])
        if not prior['engine']['profile']['passthrough']:
            raise RuntimeError('Development reference must contain unchanged source samples')
        if ev.file_hash(row['path'])!=row['sha256']:
            raise RuntimeError('Source hash changed')
        levels=reference_levels(path,row['sample_rate'])
        records.append(dict(date=row['date'],title=row['title'],source_sha256=row['sha256'],
                            decoded_wav_sha256=ev.file_hash(path),sample_rate=row['sample_rate'],
                            block_levels_db=levels,median_levels_db=np.median(levels,axis=0).tolist()))
        print('FIT_REFERENCE',row['date'],len(levels),'blocks',flush=True)
    matrix=np.asarray([r['median_levels_db'] for r in records])
    reference=dict(cohort='Sixteen development audience recordings; no soundboard/clean reference',
                   config=CONFIG,tracks=records,median_db=np.median(matrix,axis=0).tolist(),
                   q25_db=np.quantile(matrix,.25,axis=0).tolist(),
                   q75_db=np.quantile(matrix,.75,axis=0).tolist())
    write(EVIDENCE/'classical-reference.json',reference)
    chosen=[]
    for date in CONFIG['selected_dates']+CONFIG['reserved_dates']:
        row=all_sources[date]
        if ev.file_hash(row['path'])!=row['sha256']:
            raise RuntimeError('Selected source hash changed')
        chosen.append({k:row[k] for k in ['date','title','split','sha256','sample_rate','channels','duration']})
    frozen=dict(config=CONFIG,frozen_at_utc=datetime.now(timezone.utc).isoformat(),reference_sha256=ev.file_hash(EVIDENCE/'classical-reference.json'),
                script_sha256=ev.file_hash(Path(__file__)),
                evaluator_sha256=ev.file_hash(ROOT/'evaluate.py'),
                listening_template_sha256=ev.file_hash(ROOT/'listening.html'),
                original_frozen_protocol_sha256=ev.file_hash(ROOT/'frozen-protocol.json'),
                original_core_fingerprint=ev.fingerprint(),
                clips=chosen,unity_reconstruction=unity_checks(),
                frozen_before_any_candidate_render=True,
                protocol='One fixed candidate, two variants. All four selected passages render and measure before either reserved passage. No parameter sweep or post-result change. Earlier holdout originals have been measured previously; these passages remain excluded from cohort fitting.',
                quality_claim='None. No subjective ratings collected.')
    write(prereg,frozen)
    print('FROZEN',ev.file_hash(prereg),flush=True)


def verify_freeze():
    frozen=json.loads((EVIDENCE/'classical-preregistration.json').read_text())
    checks={'script_sha256':Path(__file__),'reference_sha256':EVIDENCE/'classical-reference.json',
            'evaluator_sha256':ROOT/'evaluate.py','listening_template_sha256':ROOT/'listening.html',
            'original_frozen_protocol_sha256':ROOT/'frozen-protocol.json'}
    if frozen['config']!=CONFIG or frozen['original_core_fingerprint']!=ev.fingerprint():
        raise RuntimeError('Configuration or original processor changed after freeze')
    for key,path in checks.items():
        if frozen[key]!=ev.file_hash(path):
            raise RuntimeError('Frozen input changed: '+key)
    return frozen


def frequency_curve(gains, frequency):
    centers=np.asarray(CONFIG['band_centers_hz'],dtype=float)
    # Dense log grid makes smoothing width independent of sample rate and bin spacing.
    grid=np.linspace(np.log2(20),np.log2(24000),1024)
    raw=np.interp(grid,np.log2(centers),gains)
    sigma=CONFIG['log_frequency_smoothing_sigma_octaves']/(grid[1]-grid[0])
    smooth=ndimage.gaussian_filter1d(raw,sigma=sigma,mode='nearest')
    return np.interp(np.log2(np.maximum(frequency,20)),grid,smooth)


def tonal_curve(z, rate, reference):
    frequency,masks=bands(rate)
    power=(np.abs(z)**2).sum(axis=(0,2))
    observed=relative_levels(power,masks)
    lower=np.asarray(reference['q25_db'])-CONFIG['uncertainty_margin_db']
    upper=np.asarray(reference['q75_db'])+CONFIG['uncertainty_margin_db']
    residual=np.where(observed<lower,lower-observed,np.where(observed>upper,upper-observed,0))
    gains=np.clip(residual*CONFIG['residual_fraction'],-CONFIG['maximum_tonal_gain_db'],CONFIG['maximum_tonal_gain_db'])
    curve=frequency_curve(gains,frequency)
    # Positive gain tapers continuously to zero before hiss/subsonic regions.
    low,high=CONFIG['boost_allowed_hz'];lo_full,hi_full=CONFIG['boost_full_strength_hz']
    low_factor=np.clip(np.log2(np.maximum(frequency,1)/low)/np.log2(lo_full/low),0,1)
    high_factor=np.clip(np.log2(high/np.maximum(frequency,1))/np.log2(high/hi_full),0,1)
    curve=np.minimum(curve,0)+np.maximum(curve,0)*low_factor*high_factor
    return curve,dict(observed_relative_band_db=observed.tolist(),cohort_lower_db=lower.tolist(),
                      cohort_upper_db=upper.tolist(),bounded_raw_band_gain_db=gains.tolist(),
                      maximum_applied_boost_db=float(curve.max()),maximum_applied_cut_db=float(-curve.min()))


def dynamic_curve(z, rate):
    frequency,masks=bands(rate);magnitude=np.sqrt((np.abs(z)**2).mean(axis=0))
    power=magnitude**2;broad=power.sum(axis=0);level=10*np.log10(np.maximum(broad,1e-25))
    quiet=level<=np.percentile(level,CONFIG['protected_quiet_percentile'])
    positive=np.maximum(magnitude[:,1:]-magnitude[:,:-1],0).sum(axis=0)/np.maximum(magnitude[:,:-1].sum(axis=0),1e-20)
    flux=np.r_[0,positive]
    threshold=max(CONFIG['onset_minimum_positive_flux_fraction'],float(np.percentile(flux,CONFIG['onset_flux_percentile'])))
    onset=flux>threshold
    rises=np.r_[False,np.diff(level)>CONFIG['broadband_rise_protection_db']]
    hold=max(1,math.ceil(CONFIG['protection_hold_ms']*.001*rate/CONFIG['hop']))
    protected=quiet.copy()
    for index in np.flatnonzero(onset|rises):protected[index:index+hold]=True
    band_levels=np.stack([10*np.log10(np.maximum(power[m].sum(axis=0),1e-25)) for m in masks])
    active=~quiet
    if not active.any():
        return np.zeros((len(frequency),z.shape[-1])),dict(
            maximum_dynamic_attenuation_db=0.,protected_frame_fraction=1.,
            active_dynamic_frame_fraction=0.,maximum_protected_frame_attenuation_db=0.,
            thresholds_db=None,onset_threshold_fraction=threshold,
            abstention_reason='No frames above the quiet threshold')
    thresholds=np.percentile(band_levels[:,active],CONFIG['dynamics_threshold_percentile'],axis=1)
    desired=np.clip((band_levels-thresholds[:,None])*(1-1/CONFIG['dynamics_ratio']),0,CONFIG['maximum_dynamic_attenuation_db'])
    attack=np.exp(-CONFIG['hop']/(rate*CONFIG['attack_ms']*.001));release=np.exp(-CONFIG['hop']/(rate*CONFIG['release_ms']*.001))
    state=np.zeros(len(masks));smoothed=np.zeros_like(desired)
    for frame in range(desired.shape[1]):
        if protected[frame]:
            state[:]=0 # Immediate post-smoother protection: release cannot dull a new onset.
        else:
            coefficient=np.where(desired[:,frame]>state,attack,release)
            state=coefficient*state+(1-coefficient)*desired[:,frame]
        smoothed[:,frame]=state
    smoothed[:,protected]=0
    centers=np.asarray(CONFIG['band_centers_hz'],dtype=float)
    # Linear interpolation across octave centers yields broad linked attenuation.
    interpolated=np.empty((len(frequency),z.shape[-1]))
    for frame in range(z.shape[-1]):
        interpolated[:,frame]=np.interp(np.log2(np.maximum(frequency,centers[0])),np.log2(centers),smoothed[:,frame])
    interpolated[:,protected]=0
    return -interpolated,dict(maximum_dynamic_attenuation_db=float(interpolated.max()),
                              protected_frame_fraction=float(protected.mean()),
                              active_dynamic_frame_fraction=float(np.mean(interpolated.max(axis=0)>.01)),
                              maximum_protected_frame_attenuation_db=float(interpolated[:,protected].max()) if protected.any() else 0.,
                              thresholds_db=thresholds.tolist(),onset_threshold_fraction=threshold)


def raw_pcm(path, audio):
    if not np.all(np.isfinite(audio)):
        raise RuntimeError('Non-finite rendered output')
    if np.max(np.abs(audio))>=1:
        raise RuntimeError('Would introduce integer clipping')
    np.rint(audio*32768).astype('<i2').tofile(path)


def three_page(output, trials, reserved=False):
    page=(ROOT/'listening.html').read_text()
    replacements={
        '__TRIALS_JSON__':json.dumps(trials).replace('</','<\\/'),
        'Research comparison; unchanged where no correction qualified.':'Classical processing experiment; preference is unproven.',
        'A and B start':'A, B and C start',
        'The labels conceal the original and processed-candidate outputs. Conservative processing can abstain, making a pair identical.':'Labels conceal the original, tonal correction, and tonal correction plus gentle dynamics. This reference cohort consists of audience recordings, not soundboards.',
        '<button id="b" disabled>B</button>':'<button id="b" disabled>B</button><button id="c" disabled>C</button>',
        '<option>B</option>':'<option>B</option><option>C</option>',
        '<option>B more natural</option>':'<option>B more natural</option><option>C more natural</option>',
        '<option>Neither natural</option>':'<option>None natural</option>',
        "const storageKey='robophish-offline-blind-v1'":"const storageKey='robophish-classical-three-v1'",
        "$('b').classList.remove('active')":"$('b').classList.remove('active');$('c').classList.remove('active')",
        'buffers.length!==2':'buffers.length!==3',
        "$('b').classList.toggle('active',which===1)":"$('b').classList.toggle('active',which===1);$('c').classList.toggle('active',which===2)",
        "${which===0?'A':'B'}":"${'ABC'[which]}",
        "['a','b','pause']":"['a','b','c','pause']",
        'Loading synchronized pair…':'Loading synchronized comparison…',
        "Math.abs(buffers[0].duration-buffers[1].duration)>.001":"buffers.some(b=>Math.abs(buffers[0].duration-b.duration)>.001)",
        'Ready. Select A or B.':'Ready. Select A, B or C.',
        "$('b').onclick=()=>play(1);":"$('b').onclick=()=>play(1);$('c').onclick=()=>play(2);",
        "protocol:'blind-pair-v1'":"protocol:'classical-blind-three-v1'",
        'a blind preference comparison':'a three-version blind preference comparison',
    }
    for old,new in replacements.items():
        if old not in page:raise RuntimeError('Listening template changed: '+old[:50])
        page=page.replace(old,new)
    if reserved:
        page=page.replace('Listen before deciding.','Reserved validation passages.')
        page=page.replace('<footer>','<p><a href="../index.html">Return to four selected passages</a></p><footer>')
    else:
        page=page.replace('<footer>','<p><a href="validation/index.html">Separate reserved validation passages</a></p><footer>')
    (output/'index.html').write_text(page)


def audition(audio, metadata, output):
    output.mkdir(parents=True,exist_ok=True);rate=metadata['sample_rate'];meter=pyln.Meter(rate)
    loudness={name:float(meter.integrated_loudness(x)) for name,x in audio.items()}
    target=min(-18.,*loudness.values())
    matched={name:x*10**((target-loudness[name])/20) for name,x in audio.items()}
    maximum=max(float(np.max(np.abs(signal.resample_poly(x,4,1,axis=0)))) for x in matched.values())
    common=min(1.,10**(-1/20)/max(maximum,1e-20))
    order=sorted(audio,key=lambda name:hashlib.sha256((metadata['date']+name+'classical-blind-v1').encode()).hexdigest())
    files=[];key={};decoded_loudness=[]
    for label,name in zip('ABC',order):
        filename=metadata['date']+'_'+label+'.flac';path=output/filename
        sf.write(path,matched[name]*common,rate,subtype='PCM_24')
        decoded,sr=sf.read(path,always_2d=True)
        if decoded.shape!=audio[name].shape or sr!=rate:raise RuntimeError('Audition frame alignment failed')
        decoded_loudness.append(float(meter.integrated_loudness(decoded)))
        files.append(filename);key[label]=name
    error=max(decoded_loudness)-min(decoded_loudness)
    if error>.01:raise RuntimeError('Audition loudness mismatch')
    trial=dict(id=metadata['date'],date=metadata['date'],title=metadata['title'],passage='Fixed 20 s midpoint passage',
               split=metadata['probe_group'],start_seconds=metadata['start_seconds'],files=files,
               crowd_annotation='Unreviewed; no semantic crowd annotation or preference rating',
               loudness_match_error_lu=error)
    return trial,dict(id=metadata['date'],**key),dict(max_decoded_loudness_difference_lu=error,frames=audio['original'].shape[0],sample_rate=rate,files=files)


def render(output):
    frozen=verify_freeze();reference=json.loads((EVIDENCE/'classical-reference.json').read_text())
    output.mkdir(parents=True,exist_ok=True);listen=output/'listening';listen.mkdir(exist_ok=True)
    validation=listen/'validation';validation.mkdir(exist_ok=True)
    results=[];trials=[];reserved_trials=[];keys=[];artifact_checks=[];all_sources=sources()
    for index,date in enumerate(CONFIG['selected_dates']+CONFIG['reserved_dates']):
        verify_freeze()
        metadata=all_sources[date].copy();rate=metadata['sample_rate']
        if ev.file_hash(metadata['path'])!=metadata['sha256']:raise RuntimeError('Source changed')
        metadata['probe_group']='selected' if index<4 else 'reserved'
        raw=output/(date+'-source.s16');full=output/(date+'-full.s16')
        ev.decode(metadata['path'],full,rate,2)
        x=np.memmap(full,dtype='<i2',mode='r').reshape(-1,2)
        start=len(x)//2;frames=int(CONFIG['passage_seconds']*rate)
        metadata['start_seconds']=start/rate
        excerpt=np.asarray(x[start:start+frames],dtype=np.float64)/32768
        if len(excerpt)!=frames:raise RuntimeError('Short source passage')
        del x;full.unlink()
        # Preserve any pre-existing integer clipping in the source exactly.
        np.rint(excerpt*32768).astype('<i2').tofile(raw)
        print('EVALUATE',date,metadata['probe_group'],flush=True)
        original,oe,of=ev.metrics(raw,rate,2)
        z=stft(excerpt,rate);unity=inverse(z,rate,len(excerpt))
        unity_error=float(np.max(np.abs(unity-excerpt)))
        if unity_error>1e-12:raise RuntimeError('Natural-source unity reconstruction failed')
        curve,tone_info=tonal_curve(z,rate,reference)
        dynamics,dynamics_info=dynamic_curve(z,rate)
        audio={'original':excerpt};variants=[]
        for variant in CONFIG['variants']:
            started=time.monotonic()
            gain_db=curve[:,None]+(dynamics if variant=='tonal_plus_dynamics' else 0)
            gain=10**(gain_db/20);processed_z=z*gain[None,:,:]
            y=inverse(processed_z,rate,len(excerpt))
            peak=float(np.max(np.abs(signal.resample_poly(y,4,1,axis=0))))
            headroom=min(1.,10**(CONFIG['output_peak_limit_dbtp']/20)/max(peak,1e-25))
            y*=headroom;path=output/(date+'-'+variant+'.s16');raw_pcm(path,y)
            rounded=np.asarray(np.memmap(path,dtype='<i2',mode='r').reshape(-1,2),dtype=np.float64)/32768
            measured,me,mf=ev.metrics(path,rate,2)
            comparison=ev.compare(original,measured,oe,me,of,mf)
            gain_match=math.sqrt(np.mean(excerpt**2)/max(np.mean(rounded**2),1e-25))
            residual=rounded*gain_match-excerpt
            reconstructed_z=stft(rounded,rate)
            stereo_cross=reconstructed_z[0]*reconstructed_z[1].conj();original_cross=z[0]*z[1].conj()
            reliable=np.abs(original_cross)>max(float(np.abs(original_cross).max())*1e-10,1e-25)
            phase_delta=np.angle(stereo_cross*original_cross.conj())
            phase=float(np.max(np.abs(phase_delta[reliable]))) if reliable.any() else 0.
            weights=np.abs(original_cross)
            weighted_phase=float(np.sum(np.abs(phase_delta)*weights)/max(float(weights.sum()),1e-25)*180/np.pi)
            variants.append(dict(variant=variant,metrics=measured,comparison=comparison,
                                 render_seconds=time.monotonic()-started,headroom_gain_db=ev.db(headroom),
                                 rms_matched_change_relative_db=ev.db(np.sqrt(np.mean(residual**2))/max(np.sqrt(np.mean(excerpt**2)),1e-25)),
                                 weighted_reconstructed_interchannel_phase_error_degrees=weighted_phase,
                                 reliable_bin_maximum_reconstructed_phase_error_radians=phase,
                                 shared_real_stft_mask=True,
                                 maximum_applied_mask_change_db=float(np.max(np.abs(gain_db))),
                                 decoded_pcm_sha256=ev.file_hash(path)))
            audio[variant]=rounded;path.unlink()
        destination=listen if index<4 else validation
        trial,key,check=audition(audio,metadata,destination)
        (trials if index<4 else reserved_trials).append(trial);keys.append(key);artifact_checks.append(check)
        results.append(dict(metadata=metadata,original=original,unity_reconstruction_error=unity_error,
                            tonal_analysis=tone_info,dynamics_analysis=dynamics_info,variants=variants))
        raw.unlink()
        write(EVIDENCE/'classical-results.json',dict(preregistration_sha256=ev.file_hash(EVIDENCE/'classical-preregistration.json'),
              complete=len(results)==6,tracks=results,artifact_checks=artifact_checks,
              quality_claim='Unestablished; no human listening ratings. Numerical bounds are engineering safeguards, not preference scores.'))
        write(listen/'trials.json',trials);write(validation/'trials.json',reserved_trials)
        write(listen/'answer-key.json',keys)
        three_page(listen,trials)
        if reserved_trials:three_page(validation,reserved_trials,True)
        print('RESULT',date,[(v['variant'],v['comparison']['all_preservation_checks_pass']) for v in variants],flush=True)
    print('COMPLETE: 6 sources, 12 variants; no retuning',flush=True)


def main():
    parser=argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--output',type=Path,required=True)
    parser.add_argument('--action',choices=['prepare','render'],required=True)
    args=parser.parse_args()
    (prepare if args.action=='prepare' else render)(args.output)


if __name__=='__main__':main()
