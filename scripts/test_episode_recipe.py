import importlib.util
import json
import tempfile
import unittest
from pathlib import Path

spec = importlib.util.spec_from_file_location('recipe', Path(__file__).with_name('episode-recipe.py'))
recipe = importlib.util.module_from_spec(spec)
spec.loader.exec_module(recipe)


class EpisodeRecipeTest(unittest.TestCase):
    def test_export_is_allowlisted_and_hash_checked(self):
        with tempfile.TemporaryDirectory() as name:
            root = Path(name)
            source = root / 'output'
            (source/'keyframes').mkdir(parents=True)
            (source/'keyframes/face.png').write_bytes(b'reference')
            (source/'manifest.json').write_text(json.dumps({'takes':[{'image':'face.png'}]}))
            (source/'native.mp4').write_bytes(b'video')
            (source/'.env').write_text('PRIVATE=value')
            target = root/'recipe'
            self.assertEqual(recipe.export(source,target)['verifiedFiles'],2)
            self.assertFalse((target/'.env').exists())
            self.assertFalse((target/'native.mp4').exists())
            restored = root/'restored'
            self.assertFalse(recipe.restore(target,restored)['rendered'])
            with self.assertRaises(FileExistsError):
                recipe.restore(target,restored)
            (target/'keyframes/face.png').write_bytes(b'changed')
            with self.assertRaises(ValueError):
                recipe.verify(target)

    def test_reference_cannot_escape_recipe(self):
        with tempfile.TemporaryDirectory() as name:
            with self.assertRaises(ValueError):
                recipe.inside(Path(name),'../secret')


if __name__ == '__main__':
    unittest.main()
