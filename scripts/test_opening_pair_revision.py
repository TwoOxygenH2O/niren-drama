"""Validate draft bookkeeping and handoffs, not artistic quality."""
import json
import re
import unittest
from pathlib import Path

ROOT = Path(__file__).resolve().parents[1]
DRAFT = ROOT / "screenplays/jie-ming-bu-huan/revisions/opening-pair-v2"
SPEECH = re.compile(r"^(旁白／沈照|沈照|裴衡|岑岳)：(.+)$", re.M)


class OpeningPairRevisionTest(unittest.TestCase):
    def setUp(self):
        self.revision = json.loads((DRAFT / "revision.json").read_text(encoding="utf-8"))

    def test_draft_never_claims_render_or_approval(self):
        self.assertEqual(self.revision["status"], "script_revision_only")
        for flag in ("rendered", "audioRecorded", "userApprovedScript", "commercialReleaseApproved"):
            self.assertIs(self.revision[flag], False)
        self.assertIs(self.revision["narration"]["overlapWithDialogue"], False)
        self.assertIs(self.revision["narration"]["sendToVideoPrompt"], False)

    def test_voice_order_matches_scripts_and_keeps_narration_separate(self):
        for episode in self.revision["episodes"]:
            script = (DRAFT / episode["script"]).read_text(encoding="utf-8")
            lines = SPEECH.findall(script)
            self.assertEqual([speaker for speaker, _ in lines], episode["voiceOrder"])
            speakers = {speaker for speaker, _ in lines if speaker != "旁白／沈照"}
            self.assertEqual(speakers, set(episode["dialogueSpeakers"]))
            self.assertGreater(sum(speaker == "旁白／沈照" for speaker, _ in lines), 1)
            last_speaker = None
            for line in script.splitlines():
                if line == "【旁白停】":
                    last_speaker = None
                match = SPEECH.fullmatch(line)
                if match:
                    speaker = match.group(1)
                    if speaker != "旁白／沈照":
                        self.assertNotEqual(last_speaker, "旁白／沈照")
                    last_speaker = speaker

    def test_episode_boundary_preserves_contract_jade_and_positions(self):
        first, second = self.revision["episodes"]
        self.assertEqual(first["endState"], second["startState"])
        self.assertIn("左袖内袋", first["endState"]["jade"])
        self.assertIn("未签", first["endState"]["contract"])
        self.assertFalse(first["endState"]["masterIdentityConfirmed"])
        self.assertTrue(second["endState"]["masterIdentityConfirmed"])
        self.assertIn("未知", second["endState"]["otherBorrowers"])


if __name__ == "__main__":
    unittest.main()
