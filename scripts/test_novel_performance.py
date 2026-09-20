import importlib.util
import json
import re
import unittest
from tempfile import TemporaryDirectory
from pathlib import Path

ROOT=Path(__file__).resolve().parents[1]
spec=importlib.util.spec_from_file_location('novel_compose',ROOT/'scripts/compose-novel-performance.py')
module=importlib.util.module_from_spec(spec)
spec.loader.exec_module(module)


class NovelPerformanceTest(unittest.TestCase):
    def test_revised_second_episode_preserves_dialogue_and_narration(self):
        out = ROOT/'docs/production-recipes/jie-ming-ep02-v2'
        manifest = json.loads((out/'manifest.json').read_text(encoding='utf-8'))
        script = (ROOT/manifest['approvedScript']).read_text(encoding='utf-8')
        expected = re.findall(r'^(沈照|裴衡|岑岳)：(.+)$', script, re.M)
        takes = {take['id']:take for take in manifest['takes']}
        actual = [(line['speaker'],line['text']) for key in manifest['editOrder']
                  if key in takes for line in takes[key]['lines']]
        self.assertEqual(expected, actual)
        master = json.loads((out/'audio/narration-manifest.json').read_text(encoding='utf-8'))
        self.assertEqual(''.join(re.findall(r'^旁白／沈照：(.+)$', script, re.M)),
                         master['voice'][0]['text'])
        clauses = (out/'audio/narration.txt').read_text(encoding='utf-8').splitlines()
        used = [i for block in manifest['narrationBlocks'] for i in block['segments']]
        self.assertEqual(used, list(range(len(clauses))))
        for take in takes.values():
            self.assertEqual(take['frames'] % 17, 5)
            self.assertTrue((out/'keyframes'/take['image']).is_file())
            self.assertNotIn('上一世', take['prompt'])

    def test_excluded_take_cannot_be_reintroduced(self):
        with TemporaryDirectory() as temporary:
            out = Path(temporary)
            (out/'audio').mkdir()
            (out/'narration.wav').write_bytes(b'fixture')
            module.save(out/'audio/narration-alignment.json',
                        {'audioSha256':module.sha(out/'narration.wav')})
            module.save(out/'manifest.json', {
                'narrationMaster':'narration.wav', 'narrationBlocks':[],
                'takes':[{'id':'bad','excludedReason':'Incorrect prop action'}],
                'editOrder':['bad']})
            with self.assertRaisesRegex(ValueError, 'Excluded take'):
                module.build(out)

    def test_first_episode_revision_preserves_approved_dialogue(self):
        out = ROOT/'docs/production-recipes/jie-ming-ep01-v2'
        manifest = json.loads((out/'manifest.json').read_text(encoding='utf-8'))
        script = (ROOT/manifest['approvedScript']).read_text(encoding='utf-8')
        expected = re.findall(r'^(沈照|裴衡)：(.+)$', script, re.M)
        takes = {take['id']:take for take in manifest['takes']}
        actual = [(line['speaker'], line['text']) for key in manifest['editOrder']
                  if key in takes for line in takes[key]['lines']]
        self.assertEqual(expected, actual)
        master = json.loads((out/'audio/narration-manifest.json').read_text(encoding='utf-8'))
        self.assertEqual(''.join(re.findall(r'^旁白／沈照：(.+)$', script, re.M)),
                         master['voice'][0]['text'])
        for take in manifest['takes']:
            self.assertEqual(take['frames'] % 17, 5)
            self.assertTrue((out/'keyframes'/take['image']).is_file())
            self.assertNotIn('师兄渡劫那天', take['prompt'])

    def test_ambient_action_block_preserves_frames_without_inventing_dialogue(self):
        row={'startFrame':0,'frames':48,'audioMode':'ambient',
             'pictures':[{'inFrame':24,'outFrame':72}]}
        self.assertEqual(module.validate_schedule([row]),48)
        module.protect_narration([],row['pictures'][0])
        with self.assertRaises(ValueError):
            module.protect_narration([{'start':1.5,'end':2.0}],row['pictures'][0])

    def test_second_episode_preserves_script_and_voice_ownership(self):
        story = ROOT/'screenplays/jie-ming-bu-huan'
        out = ROOT/'docs/production-recipes/jie-ming-ep02'
        script = (story/'episode-02.txt').read_text(encoding='utf-8')
        manifest = json.loads((out/'manifest.json').read_text(encoding='utf-8'))
        narration = (story/'episode-02/narration.txt').read_text(encoding='utf-8').splitlines()
        master = json.loads((story/'episode-02/narration-manifest.json').read_text(encoding='utf-8'))
        expected = re.findall(r'^(沈照|裴衡|岑岳)：(.+)$', script, re.M)
        actual = [(line['speaker'], line['text']) for take in manifest['takes'] for line in take['lines']]
        self.assertEqual(expected, actual)
        self.assertEqual(''.join(narration), master['voice'][0]['text'])
        self.assertEqual(''.join(re.findall(r'^旁白／沈照：(.+)$', script, re.M)), ''.join(narration))
        used = [index for block in manifest['narrationBlocks'] for index in block['segments']]
        self.assertEqual(used, list(range(len(narration))))
        for take in manifest['takes']:
            self.assertEqual(take['frames'] % 17, 5)
            self.assertGreaterEqual(take['frames'], 124)
            self.assertTrue((out/'keyframes'/take['image']).is_file())
            for sentence in narration:
                self.assertNotIn(sentence, take['prompt'])
            if take['mode'] == 'narration':
                self.assertFalse(take['lines'])
            else:
                self.assertEqual(len(take['lines']), 1)
                self.assertIn(take['lines'][0]['text'], take['prompt'])

    def test_every_spoken_line_is_preserved(self):
        script=(ROOT/'screenplays/jie-ming-bu-huan/episode-01.txt').read_text(encoding='utf-8')
        manifest=json.loads((ROOT/'docs/production-recipes/jie-ming-ep01/manifest.json').read_text(encoding='utf-8'))
        expected=re.findall(r'^(沈照|裴衡)：(.+)$',script,re.M)
        actual=[(l['speaker'],l['text']) for t in manifest['takes'] for l in t['lines']]
        self.assertEqual(expected,actual)
        narration=''.join(re.findall(r'^旁白：(.+)$',script,re.M))
        master=json.loads((ROOT/'screenplays/jie-ming-bu-huan/narration-manifest.json').read_text(encoding='utf-8'))
        self.assertEqual(narration,master['voice'][0]['text'])

    def test_narration_not_sent_to_video_model(self):
        m=json.loads((ROOT/'docs/production-recipes/jie-ming-ep01/manifest.json').read_text(encoding='utf-8'))
        for take in m['takes']:
            if take['mode']=='narration':
                self.assertFalse(take['lines'])
            self.assertNotIn('师兄飞升那天',take['prompt'])
            self.assertTrue((ROOT/'docs/production-recipes/jie-ming-ep01/keyframes'/take['image']).is_file())

    def test_no_freeze_padding(self):
        with self.assertRaises(ValueError):
            module.allocate(125,[{'inFrame':0,'outFrame':124}])
        result=module.allocate(200,[{'inFrame':0,'outFrame':124},{'inFrame':5,'outFrame':129}])
        self.assertEqual([p['outFrame']-p['inFrame'] for p in result],[124,76])

    def test_semantic_subtitle_wrapping(self):
        result=module.wrap_caption('重来一次，他又把借条推到了我面前。')
        self.assertEqual(result,'重来一次\\N他又把借条推到了我面前')
        self.assertNotIn('\\N',module.wrap_caption('翻过来。'))
        with self.assertRaises(ValueError):
            module.wrap_caption('{\\pos(1,2)}文字')

    def test_review_cannot_replace_or_overrun_a_source(self):
        clips={'test':{'source':'original.mp4','inFrame':0,'outFrame':124}}
        self.assertEqual(module.reviewed_source(clips,'test',{'inFrame':12})['inFrame'],12)
        for edit in ({'outFrame':125},{'inFrame':-1},{'inFrame':3.5},{'source':'other.mp4'}):
            with self.assertRaises(ValueError):
                module.reviewed_source(clips,'test',edit)

    def test_internal_pause_edit_protects_actual_speech(self):
        spans=[{'start':1.336,'end':1.992},{'start':3.96,'end':4.904}]
        module.protect_speech(spans,[{'inFrame':26,'outFrame':55},{'inFrame':90,'outFrame':122}])
        with self.assertRaises(ValueError):
            module.protect_speech(spans,[{'inFrame':26,'outFrame':40},{'inFrame':90,'outFrame':122}])
        with self.assertRaises(ValueError):
            module.protect_speech(spans,[{'inFrame':90,'outFrame':122},{'inFrame':26,'outFrame':55}])

    def test_narration_can_use_only_the_silent_part_of_a_dialogue_take(self):
        spans=[{'start':3.832,'end':4.936}]
        module.protect_narration(spans,{'inFrame':0,'outFrame':75})
        with self.assertRaises(ValueError):
            module.protect_narration(spans,{'inFrame':0,'outFrame':94})

    def test_coarse_asr_timing_cannot_put_words_before_the_voice(self):
        qa={'duration':5.184,'segments':[{'text':'这张纸','start':0,'end':1.58},
            {'text':'我拿去问师父','start':1.62,'end':4.872}],
            'activity':{'spans':[{'start':2.616,'end':3.464},{'start':3.736,'end':4.712}]}}
        with self.assertRaises(ValueError):
            module.dialogue_captions(qa)
        reviewed=[{'text':'这张纸','start':2.5,'end':3.6},{'text':'我拿去问师父','start':3.68,'end':4.88}]
        self.assertEqual(module.dialogue_captions(qa,reviewed),reviewed)
        with self.assertRaises(ValueError):
            module.dialogue_captions(qa,[{**reviewed[0],'text':'随便改词'},reviewed[1]])

    def test_gap_or_mixed_voice_not_allowed(self):
        row={'startFrame':0,'frames':12,'audioMode':'narration','pictures':[{'inFrame':0,'outFrame':12}]}
        self.assertEqual(module.validate_schedule([row]),12)
        with self.assertRaises(ValueError):
            module.validate_schedule([row,{**row,'startFrame':13}])
        with self.assertRaises(ValueError):
            module.validate_schedule([{**row,'audioMode':'mixed'}])


if __name__=='__main__':
    unittest.main()
