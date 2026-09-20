import importlib.util
import unittest
from pathlib import Path

spec = importlib.util.spec_from_file_location('dialogue_qa', Path(__file__).with_name('qa-zhouzong-dialogue.py'))
qa = importlib.util.module_from_spec(spec)
spec.loader.exec_module(qa)


class DialogueCaptionGroupingTest(unittest.TestCase):
    def test_vocative_stays_with_request(self):
        take={'lines':[{'text':'执事，劳烦您看一眼。这个印是不是师父的？'}]}
        self.assertEqual(qa.caption_text(take),'执事，劳烦您看一眼\n这个印是不是师父的')

    def test_grouping_preserves_negation_and_never_joins_different_speakers(self):
        take={'lines':[{'text':'不，不能签。'},{'text':'好。'}]}
        self.assertEqual(qa.caption_text(take),'不，不能签\n好')

    def test_excluded_takes_never_hold_the_watch_queue_open(self):
        manifest={'takes':[{'id':'old', 'excludedReason':'extra speech'}, {'id':'new'}]}
        self.assertEqual(qa.review_takes(manifest), [{'id':'new'}])
        self.assertEqual(qa.review_takes(manifest, 'old'), [])
        self.assertEqual(qa.review_takes(manifest, 'new'), [{'id':'new'}])


if __name__ == '__main__':
    unittest.main()
