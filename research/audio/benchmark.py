"""Audience-tape study: metrics are guardrails, not perceptual quality scores."""
import argparse, hashlib, json, math, re, subprocess, time, urllib.request
from pathlib import Path
import numpy as np
from scipy import signal
import pyloudnorm as pyln

DATES=['1989-08-26','1990-11-04','1991-07-21','1993-08-13','1994-06-22','1997-12-06',
       '1998-04-03','2000-09-14','2003-07-30','2009-08-07','2018-07-17','2023-08-05',
       '1992-04-16','1995-12-07','1996-12-06','1999-07-10','2010-08-14','2012-08-31','2015-08-22','2024-08-16']
SR=44100
def request(url,destination=None):
    req=urllib.request.Request(url,headers={'User-Agent':'RoboPhish-AudioResearch/1.0'})
    for attempt in range(3):
        try:
            with urllib.request.urlopen(req,timeout=90) as r:
                if destination:
                    with open(destination,'wb') as f:
                        while chunk:=r.read(256*1024): f.write(chunk)
                    return
                return json.load(r)
        except Exception:
            if attempt==2: raise
            time.sleep(2+attempt)

def corpus(cache):
    selected=[]
    for date in DATES:
        if len(selected)==12: break
        try:
            raw=request('https://phish.in/api/v2/shows/'+date)
            show=raw.get('data',raw)
            print('SHOW',date,'keys',sorted(show.keys()),flush=True)
            notes=show.get('taper_notes') or ''
            tags=' '.join(str(t.get('name','') if isinstance(t,dict) else t) for t in show.get('tags',[]))
            print('SOURCE',date,show.get('audio_status'),tags,notes[:700].replace('\n',' '),flush=True)
            if show.get('sbd') is True or show.get('audio_status') in ('missing','none') or re.search(r'\bsbd\b|\bsoundboard\b',tags+' '+notes,re.I): continue
            tracks=show.get('tracks',[])
            candidates=[t for t in tracks if t.get('mp3_url',t.get('mp3')) and
                        (t.get('duration',0)>180_000 or 180<t.get('duration',0)<10000)]
            if not candidates: continue
            track=candidates[len(candidates)//2]
            url=track.get('mp3_url',track.get('mp3'))
            title=track.get('title') or track.get('song_name') or track.get('slug')
            path=cache/(date+'.mp3')
            if not path.exists(): request(url,path)
            digest=hashlib.sha256(path.read_bytes()).hexdigest()
            info=json.loads(subprocess.check_output(['ffprobe','-v','error','-show_format','-show_streams','-of','json',str(path)]))
            duration=float(info['format']['duration'])
            windows=[]
            for fraction in [.2,.5,.8]:
                start=max(0,min(duration-21,duration*fraction))
                data=subprocess.check_output(['ffmpeg','-v','error','-ss',str(start),'-i',str(path),'-t','20','-f','f32le','-ac','2','-ar',str(SR),'-'])
                windows.append(np.frombuffer(data,'<f4').reshape(-1,2).astype(np.float64))
            audio=np.concatenate(windows)
            selected.append((dict(date=date,title=title,url=url,sha256=digest,duration=duration,
                source_notes=notes,source_tags=tags,sbd_flag=show.get('sbd'),audio_status=show.get('audio_status'),
                split='development' if len(selected)%2==0 else 'holdout'),audio))
            print('DOWNLOADED',date,title,'seconds',round(duration),'bytes',path.stat().st_size,flush=True)
            time.sleep(.5)
        except Exception as exc: print('CORPUS_ERROR',date,type(exc).__name__,str(exc)[:250],flush=True)
    return selected

def bell(freq,gain,q=.7):
    A=10**(gain/40);w=2*np.pi*freq/SR;alpha=np.sin(w)/(2*q);c=np.cos(w)
    b=np.array([1+alpha*A,-2*c,1-alpha*A]);a=np.array([1+alpha/A,-2*c,1-alpha/A])
    return np.r_[b/a[0],a/a[0]]
def process(audio,hp,mud,presence):
    sos=[signal.butter(2,hp,'highpass',fs=SR,output='sos')[0]]
    if mud:sos.append(bell(280,mud))
    if presence:sos.append(bell(2200,presence))
    return signal.sosfilt(np.array(sos),audio,axis=0)
def db(value):return float(20*np.log10(max(float(value),1e-12)))
METER=pyln.Meter(SR)
def measures(x):
    f,p=signal.welch(x,fs=SR,nperseg=4096,axis=0)
    bands={name:float(np.sum(p[(f>=lo)&(f<hi)])) for name,lo,hi in
        [('rumble',0,30),('bass',30,180),('lowmid',180,600),('mid',600,2000),('presence',2000,6000),('hiss',6000,20000)]}
    mid=(x[:,0]+x[:,1])/2;side=(x[:,0]-x[:,1])/2
    rms=np.sqrt(np.mean(x*x));peak=np.max(np.abs(x))
    truepeak=np.max(np.abs(signal.resample_poly(x,4,1,axis=0)))
    return dict(lufs=float(METER.integrated_loudness(x)),rms_db=db(rms),peak_db=db(peak),
        true_peak_estimate_db=db(truepeak),crest_db=db(peak/rms),stereo_corr=float(np.corrcoef(x.T)[0,1]),
        side_mid_db=db(np.sqrt(np.mean(side*side)/max(np.mean(mid*mid),1e-18))),
        clipped_fraction=float(np.mean(np.abs(x)>=.9999)),bands=bands)

def main():
    parser=argparse.ArgumentParser();parser.add_argument('--output',default='research/audio/results');parser.add_argument('--cache',default='/tmp/robophish-corpus')
    args=parser.parse_args();out=Path(args.output);out.mkdir(parents=True,exist_ok=True);cache=Path(args.cache);cache.mkdir(parents=True,exist_ok=True)
    data=corpus(cache)
    (out/'corpus.json').write_text(json.dumps([m for m,x in data],indent=2))
    if len(data)<12:raise RuntimeError(f'Only {len(data)} recordings acquired; cannot claim a 12-track study')
    rows=[]
    variants=[(hp,mud,presence) for hp in (20,30,45) for mud in (0,-1.5,-3) for presence in (0,1,2)]
    for metadata,x in data:
        original=measures(x)
        for hp,mud,presence in variants:
            y=process(x,hp,mud,presence)
            gain=10**((original['lufs']-float(METER.integrated_loudness(y)))/20);y*=gain
            result=measures(y)
            result.update(date=metadata['date'],split=metadata['split'],hp=hp,mud=mud,presence=presence,
                loudness_match_gain_db=db(gain),crest_change_db=result['crest_db']-original['crest_db'],
                stereo_corr_change=result['stereo_corr']-original['stereo_corr'],
                side_mid_change_db=result['side_mid_db']-original['side_mid_db'],
                rumble_change_db=10*math.log10(max(result['bands']['rumble'],1e-18)/max(original['bands']['rumble'],1e-18)))
            result['guardrail_pass']=abs(result['crest_change_db'])<=1.5 and abs(result['stereo_corr_change'])<=.05 and abs(result['side_mid_change_db'])<=.5
            rows.append(result)
        print('MEASURED',metadata['date'],'original',json.dumps({k:v for k,v in original.items() if k!='bands'}),flush=True)
        (out/(metadata['date']+'.json')).write_text(json.dumps(dict(metadata=metadata,original=original,variants=rows[-len(variants):]),indent=2))
    summary=[]
    for hp,mud,presence in variants:
        selected=[r for r in rows if (r['hp'],r['mud'],r['presence'])==(hp,mud,presence)]
        summary.append(dict(hp=hp,mud=mud,presence=presence,passed=sum(r['guardrail_pass'] for r in selected),
            median_rumble_reduction_db=float(np.median([r['rumble_change_db'] for r in selected])),
            max_abs_crest_change_db=max(abs(r['crest_change_db']) for r in selected),
            max_true_peak_estimate_db=max(r['true_peak_estimate_db'] for r in selected)))
    (out/'summary.json').write_text(json.dumps(summary,indent=2))
    print('SUMMARY',json.dumps(summary),flush=True)
    print('Completed 324 matched-loudness comparisons. Metrics do NOT establish perceptual improvement.',flush=True)
if __name__=='__main__':main()
