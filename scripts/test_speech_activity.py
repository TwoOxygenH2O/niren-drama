import unittest

from speech_activity import refine_single_caption, summarize


class SpeechActivityTests(unittest.TestCase):
    def test_non_speech_margins_and_internal_gap(self):
        r = summarize([{"start": 2.8, "end": 3.7}, {"start": 4.2, "end": 5.0}], 5.2)
        self.assertAlmostEqual(r["leadingNonSpeechSeconds"], 2.8)
        self.assertAlmostEqual(r["trailingNonSpeechSeconds"], .2)
        self.assertAlmostEqual(r["internalNonSpeech"][0]["duration"], .5)
        self.assertTrue(r["reviewRequired"])

    def test_single_caption_does_not_appear_during_long_lead_in(self):
        original = {"segments": [{"text": "那就写张借据。", "start": 0, "end": 4}]}
        r = refine_single_caption(original, summarize([{"start": 2, "end": 3.2}], 4), 4)
        self.assertAlmostEqual(r["segments"][0]["start"], 1.94)
        self.assertAlmostEqual(r["segments"][0]["end"], 3.36)
        self.assertEqual(original["segments"][0]["start"], 0)

    def test_multiple_authored_lines_are_not_invented_from_vad(self):
        r = {"segments": [{"text": "甲", "start": 0, "end": 1}, {"text": "乙", "start": 2, "end": 3}]}
        self.assertEqual(refine_single_caption(r, summarize([{"start": .1, "end": 3}], 4), 4), r)

    def test_silent_audio_cannot_support_asr_hallucination(self):
        with self.assertRaisesRegex(ValueError, "No speech"):
            refine_single_caption({"segments": []}, summarize([], 4), 4)

    def test_conflicting_asr_and_speech_bounds_fail(self):
        with self.assertRaisesRegex(ValueError, "disagree"):
            refine_single_caption({"segments": [{"text": "甲", "start": 0, "end": 1}]},
                                  summarize([{"start": 2, "end": 3}], 4), 4)

    def test_invalid_intervals_are_not_silently_repaired(self):
        for spans in ([{"start": -1, "end": 2}], [{"start": 0, "end": 5}],
                      [{"start": 0, "end": 2}, {"start": 1, "end": 3}],
                      [{"start": float("nan"), "end": 2}]):
            with self.assertRaises(ValueError):
                summarize(spans, 4)


if __name__ == "__main__":
    unittest.main()
