import importlib.util
import tempfile
import unittest
from pathlib import Path

spec = importlib.util.spec_from_file_location("checkpoint", Path(__file__).with_name("production-checkpoint.py"))
checkpoint = importlib.util.module_from_spec(spec)
spec.loader.exec_module(checkpoint)


class ProductionCheckpointTest(unittest.TestCase):
    def test_snapshot_remains_intact_when_source_changes_and_forks_safely(self):
        with tempfile.TemporaryDirectory() as temp:
            root = Path(temp).resolve()
            source = root / "script.txt"
            source.write_text("original", encoding="utf-8")
            folder = root / "saved"
            definition = {"id": "test-v1", "sources": ["script.txt"]}
            result = checkpoint.create(root, definition, folder)
            self.assertEqual(result["verifiedFiles"], 1)
            source.write_text("revision", encoding="utf-8")
            self.assertEqual(checkpoint.verify(folder)["verifiedFiles"], 1)
            fork = root / "revision"
            checkpoint.fork(root, folder, fork)
            self.assertEqual((fork / "script.txt").read_text(), "original")
            with self.assertRaises(FileExistsError):
                checkpoint.fork(root, folder, fork)
            with self.assertRaises(FileExistsError):
                checkpoint.create(root, definition, folder)
            (folder / "snapshot/script.txt").write_text("tampered")
            with self.assertRaises(ValueError):
                checkpoint.verify(folder)

    def test_rejects_paths_outside_root_and_recursive_snapshots(self):
        with tempfile.TemporaryDirectory() as temp:
            root = Path(temp).resolve()
            for path in ("../outside", str(root), ""):
                with self.assertRaises(ValueError):
                    checkpoint.inside(root, path)
            (root / "inputs").mkdir()
            with self.assertRaises(ValueError):
                checkpoint.create(root, {"sources": ["inputs"]}, root / "inputs/saved")

    def test_missing_source_does_not_create_a_checkpoint(self):
        with tempfile.TemporaryDirectory() as temp:
            root = Path(temp).resolve()
            with self.assertRaises(FileNotFoundError):
                checkpoint.create(root, {"sources": ["missing"]}, root / "saved")
            self.assertFalse((root / "saved").exists())


if __name__ == "__main__":
    unittest.main()
