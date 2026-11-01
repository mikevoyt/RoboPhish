#!/usr/bin/env python3
"""Exact-engine correction tests with known added defects, not invented clean concert references."""
import argparse, json, subprocess, shutil, hashlib
from pathlib import Path
import numpy as np
from scipy import signal

ROOT=Path(__file__).resolve().parents[3]
DATES=['1993-08-13','2003-07-30','2009-08-07','2023-08-05']
SR=44100

def bell(x,hz,db,q=.8):
    a=10**(db/40);w=2*np.pi*hz/SR;alpha=np.sin(w)/(2*q)
    b=np.array([1+alpha*a,-2*np.cos(w),1-alpha*a]);a0=np.array([1+alpha/a,-2*np.cos(w),1-alpha/a])
    return signal.lfilter(b/a0[0],a0/a0[0],x,axis=0)

def reconstruction_db(reference,candidate):
    # A single global scale makes a quieter output neither rewarded nor penalized.
    scale=np.sum(reference*candidate)/max(np.sum(candidate*candidate),1e-20)
    error=reference-scale*candidate
    return float(10*np.log10(np.sum(reference*reference)/max(np.sum(error*error),1e-20)))

def main():
    p=argparse.ArgumentParser();p.add_argument('--corpus',type=Path,required=True);p.add_argument('--output',type=Path,required=True)
    p.add_argument('--java',default=shutil.which('java'));p.add_argument('--javac',default=shutil.which('javac'))
    args=p.parse_args();args.output.mkdir(parents=True,exist_ok=True);classes=args.output/'classes';classes.mkdir(exist_ok=True)
    sources=[ROOT/'mobile/src/main/java/com/bayapps/android/robophish/audio/offline'/f for f in ['OfflineRestorationDsp.java','OfflineRestorationProfile.java']]
    engine_hashes={x.name:hashlib.sha256(x.read_bytes()).hexdigest() for x in sources}
    subprocess.run([args.javac,'-d',str(classes),*[str(x) for x in sources],str(Path(__file__).with_name('OfflineRender.java'))],check=True)
    results=[]
    for date in DATES:
        mp3=args.corpus/f'{date}.mp3'
        duration=float(subprocess.check_output(['ffprobe','-v','error','-show_entries','format=duration','-of','default=nw=1:nk=1',str(mp3)],text=True))
        raw=subprocess.check_output(['ffmpeg','-v','error','-ss',str(duration*.5),'-i',str(mp3),'-t','24','-ar',str(SR),'-ac','2','-f','f32le','-'])
        original=np.frombuffer(raw,dtype='<f4').reshape(-1,2).astype(np.float64)
        # Reserve enough headroom for the known defect; every comparison shares this gain.
        original*=.22/max(abs(original).max(),1e-20)
        for hz in [160,315,630]:
            for db in [3,6,9]:
                degraded=bell(original,hz,db)
                source=args.output/'source.s16';render=args.output/'render.s16';report=args.output/'render.json'
                degraded16=np.rint(np.clip(degraded,-1,32767/32768)*32768).astype('<i2')
                degraded16.tofile(source)
                subprocess.run([args.java,'-cp',str(classes),'OfflineRender',str(source),str(render),str(report),str(SR),'2'],check=True,stdout=subprocess.DEVNULL)
                restored=np.fromfile(render,dtype='<i2').reshape(-1,2)/32768.0
                profile=json.loads(report.read_text())
                before=reconstruction_db(original,degraded16/32768.0);after=reconstruction_db(original,restored)
                results.append({'date':date,'added_bell_hz':hz,'added_gain_db':db,'before_reference_snr_db':before,'after_reference_snr_db':after,
                    'reference_error_improvement_db':after-before,'abstained':profile['profile']['passthrough'],
                    'maximum_correction_db':profile['profile']['maximumCorrectionDb'],'new_clipped_samples':profile['new_clipped_samples']})
        print(date,flush=True)
    improvements=np.array([x['reference_error_improvement_db'] for x in results])
    summary={'description':'36 controlled broad resonant-coloration trials on four development recordings; reference is original audience audio BEFORE added bell, not clean concert sound.',
        'source_sha256':{d:hashlib.sha256((args.corpus/f'{d}.mp3').read_bytes()).hexdigest() for d in DATES},
        'engine_sha256':engine_hashes,
        'trials':results,'median_error_improvement_db':float(np.median(improvements)),'minimum_error_improvement_db':float(np.min(improvements)),
        'improved_cases':int(np.sum(improvements>.1)),'worsened_cases':int(np.sum(improvements<-.1)),
        'limitations':'Controlled correction is not evidence of perceived improvement on unmodified recordings. No human ratings supplied.'}
    (args.output/'controlled_summary.json').write_text(json.dumps(summary,indent=2)+'\n')
    print(json.dumps({k:v for k,v in summary.items() if k not in ['trials','source_sha256','engine_sha256']},indent=2))
if __name__=='__main__':main()
