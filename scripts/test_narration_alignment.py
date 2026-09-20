import importlib.util
from pathlib import Path
import unittest

spec = importlib.util.spec_from_file_location('alignment', Path(__file__).with_name('align-narration-subtitles.py'))
alignment = importlib.util.module_from_spec(spec)
spec.loader.exec_module(alignment)


class AlignmentTest(unittest.TestCase):
    def test_recognition_partitions_at_pause_without_overlap(self):
        ranges=alignment.recognition_ranges([(0,19000),(21000,38000)],40000,1000)
        self.assertEqual(ranges,[(0,20000),(20000,40000)])

    def test_recognition_does_not_cut_uninterrupted_speech(self):
        with self.assertRaisesRegex(ValueError,'safe speech pause'):
            alignment.recognition_ranges([(0,40000)],40000,1000)

    def test_recognition_prefers_shorter_real_pauses_for_speaker_changes(self):
        active = [(0,8000),(8800,17000),(17500,27000),(27400,31000)]
        ranges = alignment.recognition_ranges(active,31000,1000)
        self.assertEqual(ranges,[(0,8400),(8400,17250),(17250,27200),(27200,31000)])
        for _, boundary in ranges[:-1]:
            self.assertFalse(any(a < boundary < b for a,b in active))

    def test_preserves_original_words_and_line_anchors(self):
        result = alignment.align('盒子留下。\n她不交了。', [
            {'text':'盒子留下','timestamp':(.1,2.1)},
            {'text':'她不交了','timestamp':(3,5)}], 5.2)
        self.assertEqual(result['coverage'],1)
        self.assertEqual([s['text'] for s in result['segments']],['盒子留下。','她不交了。'])
        self.assertLess(result['segments'][0]['end'],result['segments'][1]['start'])

    def test_does_not_invent_timing_for_missing_speech(self):
        with self.assertRaises(ValueError):
            alignment.align('盒子留下。\n许宁亲自来了。',[
                {'text':'盒子留下','timestamp':(0,2)}],5)

    def test_two_sentences_remain_one_authored_anchor(self):
        r = alignment.align('盒子留下。她不交了。',[
            {'text':'盒子留下她不交了','timestamp':(0,4)}],4.1)
        self.assertEqual(len(r['segments']),1)

    def test_traditional_and_homophones_keep_authored_caption(self):
        r = alignment.align('林晚的兵符还在她手上。', [
            {'text':'林婉的冰符還在他手上','timestamp':(.1,4)}],4.2)
        self.assertEqual(r['coverage'],1)
        self.assertLess(r['lexicalCoverage'],1)
        self.assertEqual(r['segments'][0]['text'],'林晚的兵符还在她手上。')

    def test_missing_negation_is_not_accepted_by_overall_coverage(self):
        with self.assertRaisesRegex(ValueError, 'negation'):
            alignment.align('这只盒子明天不能交到他的手上。', [
                {'text':'这只盒子明天能交到他的手上','timestamp':(.1,4)}],4.2)

    def test_traditional_polyphones_use_same_context_as_simplified_text(self):
        original = '还没过门，就防着我？'
        result = alignment.align(original, [
            {'text': '還沒過門就防著我', 'timestamp': (0, 4.06)}], 4.167)
        self.assertEqual(result['coverage'], 1)
        self.assertEqual(result['segments'][0]['text'], original)

    def test_traditional_normalization_does_not_hide_a_missing_negation(self):
        with self.assertRaises(ValueError):
            alignment.align('还没过门就防着我。', [
                {'text': '還過門就防著我', 'timestamp': (0, 4)}], 4.167)

    def test_extra_speech_rejected(self):
        with self.assertRaisesRegex(ValueError, 'Unexpected speech'):
            alignment.align('盒子留下。', [
                {'text':'盒子留下记得点赞关注哦','timestamp':(.1,4)}],4.2)

    def test_wrong_tone_not_treated_as_identical(self):
        with self.assertRaises(ValueError):
            alignment.align('卖掉它。', [
                {'text':'买掉它','timestamp':(.1,2)}],2.2)

    def test_invalid_timing_rejected(self):
        with self.assertRaisesRegex(ValueError, 'timestamps'):
            alignment.align('盒子留下。', [
                {'text':'盒子留下','timestamp':(3,1)}],4)

    def test_compound_word_is_not_a_missing_clause_negation(self):
        result=alignment.align('未婚夫许宁说，只借兵符一夜。', [
            {'text':'威婚夫许宁说只借兵符一夜','timestamp':(.1,4)}],4.2)
        self.assertGreaterEqual(result['coverage'],.85)
        self.assertLess(result['coverage'],1)


if __name__=='__main__': unittest.main()
