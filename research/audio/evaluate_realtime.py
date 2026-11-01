"""Evaluate the exact shipping PCM engine and generate blinded, loudness-matched clips."""
import json, math, platform, subprocess, time
from pathlib import Path
import numpy as np
import soundfile as sf
from benchmark import SR, METER, measures

OUT=Path('research/audio/results'); CACHE=Path('/tmp/robophish-corpus')
def load_windows(meta):
    windows=[]
    for fraction in [.2,.5,.8]:
        start=max(0,min(meta['duration']-21,meta['duration']*fraction))
        data=subprocess.check_output(['ffmpeg','-v','error','-ss',str(start),'-i',str(CACHE/(meta['date']+'.mp3')),'-t','20','-f','s16le','-ac','2','-ar',str(SR),'-'])
        windows.append(np.frombuffer(data,'<i2').reshape(-1,2))
    return np.concatenate(windows)

def main():
    source='mobile/src/main/java/com/bayapps/android/robophish/audio/AudienceDsp.java'
    subprocess.run(['javac','-d','/tmp/audio-dsp',source,'research/audio/DspRender.java'],check=True)
    corpus=json.loads((OUT/'corpus.json').read_text())
    print('CORPUS_MANIFEST',json.dumps(corpus),flush=True)
    results=[];blind=[];clips=OUT/'listening';clips.mkdir(exist_ok=True)
    for meta in corpus:
        pcm=load_windows(meta);x=pcm.astype(np.float64)/32768
        original=measures(x)
        renderings={'original':x}
        for mode,amount in [('RUMBLE',.6),('RUMBLE',1),('CLARITY',.6),('CLARITY',1)]:
            run=subprocess.run(['java','-cp','/tmp/audio-dsp','DspRender',mode,str(amount)],input=pcm.tobytes(),capture_output=True,check=True)
            y=np.frombuffer(run.stdout,'<i2').reshape(-1,2).astype(np.float64)/32768
            assert len(y)==len(x)
            meter=measures(y);timing=json.loads(run.stderr)
            key=mode+'_'+str(amount)
            delta=meter['lufs']-original['lufs']
            result=dict(date=meta['date'],mode=mode,amount=amount,output=meter,
                loudness_delta_lu=delta,crest_delta_db=meter['crest_db']-original['crest_db'],
                stereo_corr_delta=meter['stereo_corr']-original['stereo_corr'],timing=timing)
            results.append(result)
            if amount==.6:renderings[key]=y
        # Compare a mid-track 12 s passage at equal integrated loudness and common peak-safe gain.
        segments={key:value[SR*24:SR*36] for key,value in renderings.items()}
        normalized={key:value*10**((-23-METER.integrated_loudness(value))/20) for key,value in segments.items()}
        peak=max(np.max(np.abs(v)) for v in normalized.values())
        common=min(1,10**(-3/20)/max(peak,1e-12))
        keys=list(normalized)
        np.random.default_rng(int(meta['date'].replace('-',''))).shuffle(keys)
        mapping={}
        for letter,key in zip('ABC',keys):
            name=meta['date']+'-'+letter+'.flac'
            sf.write(clips/name,normalized[key]*common,SR,subtype='PCM_16')
            mapping[letter]=key
        blind.append(dict(date=meta['date'],title=meta['title'],key=mapping))
    (OUT/'realtime.json').write_text(json.dumps(results,indent=2))
    (clips/'answer-key.json').write_text(json.dumps(blind,indent=2))
    (clips/'README.txt').write_text('Blind preference exercise. Each date has A/B/C versions of the same 12-second passage. One is original; two use conservative processing. Levels are matched using integrated loudness, with a common peak-safe gain. Compare with good headphones at comfortable volume. Rate clarity, bass naturalness, cymbal/vocal artifacts, crowd distraction, stereo image and overall preference, including ties. Do not inspect answer-key.json until ratings are complete. This is not a standardized MUSHRA test: no clean reference exists. No listening results have been collected yet.\\n')
    summary=[dict(date=r['date'],mode=r['mode'],amount=r['amount'],loudness_delta_lu=r['loudness_delta_lu'],
        crest_delta_db=r['crest_delta_db'],stereo_corr_delta=r['stereo_corr_delta'],
        true_peak_estimate_db=r['output']['true_peak_estimate_db'],
        rtf=r['timing']['processing_seconds']/r['timing']['audio_seconds'],
        max_buffer_ms=r['timing']['max_buffer_ms']) for r in results]
    print('REALTIME_SUMMARY',json.dumps(summary),flush=True)
    print('Benchmark host',platform.platform(),flush=True)
if __name__=='__main__':main()
