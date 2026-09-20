import importlib.util
import unittest
from pathlib import Path

SPEC = importlib.util.spec_from_file_location("attention_ab", Path(__file__).with_name("run-h3-attention-ab.py"))
AB = importlib.util.module_from_spec(SPEC)
SPEC.loader.exec_module(AB)


class AttentionComparisonTest(unittest.TestCase):
    def test_official_non_turbo_branch(self):
        for shot in AB.PROMPTS:
            graph = AB.build(shot)
            self.assertEqual(graph["schedule"]["inputs"], {
                "model": ["model", 0], "scheduler": "simple", "steps": 20, "denoise": 1.0})
            self.assertFalse(any("Lora" in n["class_type"] or "Patch" in n["class_type"] for n in graph.values()))
            self.assertEqual(graph["i2v"]["inputs"]["length"], 124)
            self.assertEqual(graph["video"]["inputs"]["fps"], 24)

    def test_only_attention_flag_changes(self):
        left, right = AB.launch_args("sage"), AB.launch_args("sdpa")
        self.assertEqual(left[:-1], right[:-1])
        self.assertEqual(left[-1], "--use-sage-attention")
        self.assertEqual(right[-1], "--use-pytorch-cross-attention")
        self.assertIn("--disable-all-custom-nodes", left)

    def test_prompt_has_no_narration_or_dialogue_payload(self):
        for shot in AB.PROMPTS:
            graph = AB.build(shot)
            self.assertNotIn("<d>", graph["i2v"]["inputs"]["prompt"])
            self.assertEqual(graph["i2v"]["inputs"]["first_frame"], ["image", 0])


if __name__ == "__main__":
    unittest.main()
