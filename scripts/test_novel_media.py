"""Small FFmpeg regression fixture for frame-grid audio; not a generated drama sample."""
import importlib.util
import json
import shutil
import subprocess
import tempfile
import unittest
from pathlib import Path

import numpy as np

ROOT=Path(__file__).resolve().parents[1]


def load(name,filename):
    spec=importlib.util.spec_from_file_location(name,ROOT/'scripts'/filename)
    module=importlib.util.module_from_spec(spec)
    spec.loader.exec_module(module)
    return module


@unittest.skipUnless(shutil.which('ffmpeg') and shutil.which('ffprobe'),'FFmpeg is required')
class NativeAudioGridTest(unittest.TestCase):
    def test_fractional_video_lengths_do_not_accumulate_audio_drift(self):
        compose=load('novel_test_compose','compose-novel-performance.py')
        verify=load('novel_test_verify','verify-novel-performance.py')
        with tempfile.TemporaryDirectory(prefix='novel-audio-grid-') as tmp:
            out=Path(tmp)
            voice=out/'fixture.wav'
            picture=out/'fixture.mp4'
            subprocess.run(['ffmpeg','-y','-v','error','-f','lavfi','-i',
                'anoisesrc=color=white:amplitude=0.2:sample_rate=24000:duration=15:seed=43',
                '-af','lowpass=f=2500','-c:a','pcm_s16le',str(voice)],check=True)
            subprocess.run(['ffmpeg','-y','-v','error','-f','lavfi','-i',
                'color=c=gray:size=768x1344:rate=24:duration=5',
                '-c:v','libx264','-preset','ultrafast','-pix_fmt','yuv420p',str(picture)],check=True)
            rows=[]
            cursor=0
            for index,(frames,audio_start) in enumerate(((88,6.54),(99,2.458333333))):
                rows.append({'id':str(index),'audioMode':'narration','audio':str(voice),
                    'audioStart':audio_start,'frames':frames,'startFrame':cursor,
                    'pictures':[{'source':str(picture),'inFrame':0,'outFrame':frames}]})
                cursor+=frames
            data={'completeEpisode':True,'duration':cursor/24,'frameCount':cursor,'timeline':rows,
                'captions':[{'text':'测试字幕','start':.2,'end':1.8}],
                'artisticApproval':False}
            compose.compose(out,data)
            original=verify.audio(voice)
            mixed=verify.audio(out/'final/episode-subtitled.mp4')
            clean=verify.audio(out/'final/episode-clean.mp4')
            self.assertTrue(np.array_equal(clean,mixed))
            final_probe=json.loads(subprocess.check_output(['ffprobe','-v','error','-show_streams',
                    '-of','json',str(out/'final/episode-subtitled.mp4')]))
            final_audio=next(s for s in final_probe['streams'] if s['codec_type']=='audio')
            self.assertLess(abs(float(final_audio['duration'])-cursor/24),.005)
            for index,row in enumerate(rows):
                probe=json.loads(subprocess.check_output(['ffprobe','-v','error','-show_streams',
                    '-of','json',str(out/f'final/part-{index:02}.mov')]))
                sound=next(s for s in probe['streams'] if s['codec_type']=='audio')
                self.assertEqual(int(sound['nb_frames']),row['frames']*2000)
                match=verify.audio_match(original,mixed,row['audioStart']+.4,row['startFrame']/24+.4,1)
                self.assertLess(abs(match['offsetSeconds']),1/240)
                self.assertGreater(match['correlation'],.95)
            self.assertTrue(np.isfinite(mixed).all())


if __name__=='__main__':
    unittest.main()
