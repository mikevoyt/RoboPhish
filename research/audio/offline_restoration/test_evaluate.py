import tempfile
import unittest
import json
from pathlib import Path
import numpy as np
from evaluate import metrics, compare, listening_pair


class EvidenceTests(unittest.TestCase):
    def test_known_stereo_and_identical_preservation(self):
        rate=44100
        t=np.arange(rate*2)/rate
        rng=np.random.default_rng(92)
        left=.2*np.sin(2*np.pi*420*t)*(1+.2*np.sin(2*np.pi*3*t))+.005*rng.normal(size=len(t))
        right=.12*np.sin(2*np.pi*420*t+.3)*(1+.2*np.sin(2*np.pi*3*t))+.005*rng.normal(size=len(t))
        audio=np.round(np.column_stack([left,right])*32768).astype('<i2')
        with tempfile.TemporaryDirectory() as tmp:
            path=Path(tmp)/'original.s16';audio.tofile(path)
            result,env,flux=metrics(path,rate,2)
            self.assertEqual(result['frames'],len(t))
            self.assertAlmostEqual(result['stereo_correlation'],np.corrcoef(audio.T)[0,1],places=8)
            diff=compare(result,result,env,env,flux,flux,True)
            self.assertTrue(diff['all_preservation_checks_pass'])
            self.assertAlmostEqual(diff['crest_change_db'],0)
            json.dumps(dict(measurements=result,comparison=diff))
            clips,key=listening_pair(path,path,dict(date='test',title='synthetic',split='development'),rate,2,Path(tmp))
            self.assertTrue(clips)
            self.assertTrue(all(abs(c['loudness_match_error_lu'])<.001 for c in clips))
            self.assertEqual(len(clips),len(key))


if __name__=='__main__':
    unittest.main()
