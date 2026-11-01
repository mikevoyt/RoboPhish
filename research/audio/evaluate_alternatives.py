"""Stress test targeted restoration candidates. None are enabled in the app.

Stationary-line detection requires narrow peaks across 80% of windows and at least
2 harmonics. The spectral gate is deliberately included as a negative-control risk:
without isolated noise, minimum statistics can confuse cymbal decay with noise.
"""
import json, math
from pathlib import Path
import numpy as np
from scipy import signal, ndimage
from benchmark import SR, METER, measures
from evaluate_realtime import load_windows

OUT=Path('research/audio/results')
def hum_lines(x):
    _,_,z=signal.stft(x.mean(axis=1),fs=SR,nperseg=SR,noverlap=SR//2,boundary=None)
    p=np.abs(z)**2
    lines=[];diagnostics=[]
    for fundamental in [50,60]:
        hits=[]
        for harmonic in [1,2,3]:
            f=fundamental*harmonic
            local=np.median(np.r_[p[f-7:f-3],p[f+4:f+8]],axis=0)+1e-18
            ratio=10*np.log10((p[f]+1e-18)/local)
            confidence=float(np.mean(ratio>12))
            level=float(np.median(p[f]))
            hit=confidence>.8 and level>1e-7
            if hit:hits.append(f)
            diagnostics.append(dict(frequency=f,persistence=confidence,median_prominence_db=float(np.median(ratio)),median_power=level,hit=hit))
        if len(hits)>=2:lines+=hits
    return lines,diagnostics

def dehum(x,lines):
    y=x.copy()
    for f in lines:
        b,a=signal.iirnotch(f,60,fs=SR)
        y=signal.lfilter(b,a,y,axis=0)
    return y

def spectral_gate(x,floor_db):
    f,t,z=signal.stft(x.T,fs=SR,nperseg=2048,noverlap=1536,boundary='zeros')
    power=np.mean(np.abs(z)**2,axis=0)
    noise=np.percentile(power,10,axis=1)[:,None]
    gain=np.sqrt(np.maximum(1-noise/(power+1e-18),0))
    gain=np.maximum(gain,10**(floor_db/20))
    gain=ndimage.gaussian_filter(gain,sigma=(2,2))
    gain[f<6000,:]=1
    _,y=signal.istft(z*gain[None,:,:],fs=SR,nperseg=2048,noverlap=1536)
    return y.T[:len(x)]

def main():
    rows=[]
    corpus=json.loads((OUT/'corpus.json').read_text())
    for meta in corpus:
        x=load_windows(meta).astype(np.float64)/32768
        original=measures(x)
        lines,diagnostics=hum_lines(x)
        candidates={'confidence_gated_dehum':dehum(x,lines),'high_band_gate_3db':spectral_gate(x,-3),'high_band_gate_6db':spectral_gate(x,-6)}
        for name,y in candidates.items():
            y*=10**((original['lufs']-METER.integrated_loudness(y))/20)
            m=measures(y)
            delta=m['crest_db']-original['crest_db']
            stereo=m['stereo_corr']-original['stereo_corr']
            side=m['side_mid_db']-original['side_mid_db']
            rows.append(dict(date=meta['date'],candidate=name,crest_change_db=delta,stereo_corr_change=stereo,
                side_mid_change_db=side,true_peak_estimate_db=m['true_peak_estimate_db'],
                high_band_change_db=10*math.log10(m['bands']['hiss']/original['bands']['hiss']),
                guardrail_pass=abs(delta)<=1.5 and abs(stereo)<=.05 and abs(side)<=.5,
                detected_hum_lines=lines,hum_diagnostics=diagnostics if name=='confidence_gated_dehum' else None))
    (OUT/'alternatives.json').write_text(json.dumps(rows,indent=2))
    print('ALTERNATIVE_SUMMARY',json.dumps([{k:v for k,v in r.items() if k!='hum_diagnostics'} for r in rows]),flush=True)
    print('Noise removal is unproven: no noise-only segments or clean references were supplied. Spectral reduction is not equivalent to quality improvement.',flush=True)
if __name__=='__main__':main()
