#!/usr/bin/env python3
"""Small, local NARA-WPE baseline. Outputs remain research-only; no model uploads."""
import argparse,json,subprocess,time
from pathlib import Path
import numpy as np
import soundfile as sf
from scipy import signal
from nara_wpe.wpe import wpe
from controlled_degradation import reconstruction_db

DATES=['1993-08-13','1995-12-07','2009-08-07','2024-08-16']
SR=44100

def describe(x):
    mid=(x[:,0]+x[:,1])/2;side=(x[:,0]-x[:,1])/2
    return {'peak_dbfs':float(20*np.log10(max(np.max(abs(x)),1e-20))),
        'crest_db':float(20*np.log10(max(np.max(abs(x)),1e-20)/np.sqrt(np.mean(x*x)))),
        'correlation':float(np.corrcoef(x.T)[0,1]),
        'side_mid_db':float(10*np.log10(max(np.mean(side*side),1e-20)/max(np.mean(mid*mid),1e-20)))}

def main():
    p=argparse.ArgumentParser();p.add_argument('--corpus',type=Path,required=True);p.add_argument('--output',type=Path,required=True);a=p.parse_args()
    a.output.mkdir(parents=True,exist_ok=True);rows=[]
    for date in DATES:
        mp3=a.corpus/f'{date}.mp3';duration=float(subprocess.check_output(['ffprobe','-v','error','-show_entries','format=duration','-of','default=nw=1:nk=1',str(mp3)],text=True))
        raw=subprocess.check_output(['ffmpeg','-v','error','-ss',str(duration*.5),'-i',str(mp3),'-t','20','-ar',str(SR),'-ac','2','-f','f32le','-'])
        reference=np.frombuffer(raw,dtype='<f4').reshape(-1,2).astype(np.float64)
        reference*=.35/max(abs(reference).max(),1e-20)
        for injected in [False,True]:
            x=reference.copy()
            if injected:
                # Known additional late reflections, shared timing with slightly different channel gains.
                for seconds,amp in [(0.070,.24),(.111,.18),(.177,.12),(.263,.07)]:
                    offset=int(SR*seconds);x[offset:]+=reference[:-offset]*np.array([amp,amp*.9])
            _,_,z=signal.stft(x.T,fs=SR,window='hann',nperseg=2048,noverlap=1536,boundary='zeros')
            started=time.monotonic()
            estimated=wpe(z.transpose(1,0,2),taps=6,delay=3,iterations=2,statistics_mode='valid').transpose(1,0,2)
            elapsed=time.monotonic()-started
            variants={'wpe_full':estimated,'wpe_25_percent':.75*z+.25*estimated}
            ratio=np.sqrt(np.sum(abs(estimated)**2,axis=0)/np.maximum(np.sum(abs(z)**2,axis=0),1e-20))
            mask=np.clip(ratio,.84,1)
            variants['wpe_shared_mask_1_5db']=z*mask[None,:,:]
            original_metrics=describe(x)
            for name,spectrum in variants.items():
                _,y=signal.istft(spectrum,fs=SR,window='hann',nperseg=2048,noverlap=1536,boundary=True)
                y=y.T[:len(x)]
                measured=describe(y)
                row={'date':date,'injected_late_reflections':injected,'variant':name,'elapsed_seconds':elapsed,
                    'reference_error_improvement_db':reconstruction_db(reference,y)-reconstruction_db(reference,x) if injected else None,
                    'crest_change_db':measured['crest_db']-original_metrics['crest_db'],
                    'correlation_change':measured['correlation']-original_metrics['correlation'],
                    'side_mid_change_db':measured['side_mid_db']-original_metrics['side_mid_db']}
                rows.append(row)
                if not injected:
                    # RMS matched inspection clips only; final blinded corpus uses integrated loudness.
                    y*=np.sqrt(np.mean(x*x)/max(np.mean(y*y),1e-20))
                    sf.write(a.output/f'{date}-{name}.flac',y,SR,subtype='PCM_24')
            if not injected:sf.write(a.output/f'{date}-original.flac',x,SR,subtype='PCM_24')
        print(date,flush=True)
    result={'library':'nara_wpe==0.0.11 (MIT)','configuration':{'fft':2048,'hop':512,'taps':6,'delay':3,'iterations':2},
        'rows':rows,'decision':'Research-only. Speech-derived dereverberation is not validated crowd-preserving concert restoration.',
        'limitations':'Four development passages, not full tracks or subjective assessment. Controlled reference is original audience recording before injected extra room reflections.'}
    (a.output/'wpe_summary.json').write_text(json.dumps(result,indent=2)+'\n')
if __name__=='__main__':main()
