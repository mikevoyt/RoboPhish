"""Known added degradations: engineering validation, not perceptual quality scores.

The original audience clip is the reference only for the artificially added noise.
It is not a clean concert reference. A harmonic musical tone is an adversarial
negative control: a magnitude-only hum detector cannot identify its semantic origin.
"""
import json
from pathlib import Path
import numpy as np
from scipy import signal
from benchmark import SR
from evaluate_realtime import load_windows
from evaluate_alternatives import hum_lines, dehum, spectral_gate

OUT=Path('research/audio/results')

def improvement(reference, corrupted, restored):
    before=np.mean((corrupted-reference)**2)
    after=np.mean((restored-reference)**2)
    return float(10*np.log10(max(before,1e-18)/max(after,1e-18)))

def main():
    rows=[]
    corpus=json.loads((OUT/'corpus.json').read_text())
    for meta in corpus:
        x=load_windows(meta)[SR*24:SR*36].astype(np.float64)/32768
        t=np.arange(len(x))/SR
        rms=float(np.sqrt(np.mean(x*x)))
        rng=np.random.default_rng(int(meta['date'].replace('-','')))
        for fundamental in [50,60]:
            noise=sum(np.sin(2*np.pi*fundamental*h*t+rng.uniform(0,2*np.pi))/h for h in [1,2,3])
            noise=noise[:,None]*np.array([[1,.9]])
            noise*=rms*10**(-25/20)/np.sqrt(np.mean(noise*noise))
            corrupted=x+noise
            lines,_=hum_lines(corrupted)
            y=dehum(corrupted,lines)
            rows.append(dict(date=meta['date'],corruption=f'hum_{fundamental}_snr25',candidate='confidence_gated_dehum',
                detected_hum_lines=lines,error_improvement_db=improvement(x,corrupted,y)))
        noise=rng.normal(size=x.shape)
        noise=signal.sosfilt(signal.butter(4,6000,'highpass',fs=SR,output='sos'),noise,axis=0)
        noise*=rms*10**(-25/20)/np.sqrt(np.mean(noise*noise))
        corrupted=x+noise
        for floor in [-3,-6]:
            y=spectral_gate(corrupted,floor)
            untouched=spectral_gate(x,floor)
            rows.append(dict(date=meta['date'],corruption='highband_hiss_snr25',candidate=f'high_band_gate_{-floor}db',
                error_improvement_db=improvement(x,corrupted,y),
                original_signal_change_db=float(10*np.log10(max(np.mean((untouched-x)**2),1e-18)/max(np.mean(x*x),1e-18)))))
    # Two sustained musical oscillator notes can be indistinguishable from hum.
    t=np.arange(SR*12)/SR
    musical=.05*(np.sin(2*np.pi*50*t)+.5*np.sin(2*np.pi*100*t))
    negative=np.stack([musical,musical],axis=1)
    false_lines,diagnostics=hum_lines(negative)
    result=dict(trials=rows,adversarial_musical_tone=dict(detected_hum_lines=false_lines,diagnostics=diagnostics),
        interpretation='Error improvement refers only to known added corruption; not subjective sound quality or original recording restoration.')
    (OUT/'controlled.json').write_text(json.dumps(result,indent=2))
    summary=[]
    for candidate in sorted(set(r['candidate'] for r in rows)):
        selected=[r for r in rows if r['candidate']==candidate]
        gains=[r['error_improvement_db'] for r in selected]
        summary.append(dict(candidate=candidate,n=len(selected),median_error_improvement_db=float(np.median(gains)),
            min_error_improvement_db=min(gains),max_error_improvement_db=max(gains),
            positive_trials=sum(g>0.01 for g in gains),detected_trials=sum(bool(r.get('detected_hum_lines')) for r in selected)))
    print('CONTROLLED_SUMMARY',json.dumps(dict(candidates=summary,adversarial_musical_tone_detected_lines=false_lines)),flush=True)
if __name__=='__main__':main()
