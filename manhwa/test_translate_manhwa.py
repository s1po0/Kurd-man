"""Offline tests — no network, no tesseract, no Pillow needed.
Run:  python -m unittest test_translate_manhwa -v   (from manhwa/)
"""
import tempfile, unittest
from pathlib import Path
from translate_manhwa import (TranslationCache, Translator, load_glossary,
                              parse_google_payload, parse_tesseract_tsv,
                              split_long_text, normalize)

TSV = ("level\tpage_num\tblock_num\tpar_num\tline_num\tword_num\tleft\ttop\twidth\theight\tconf\ttext\n"
       "5\t1\t1\t1\t1\t1\t10\t20\t40\t15\t95\tI\n"
       "5\t1\t1\t1\t1\t2\t55\t20\t50\t15\t92\twill\n"
       "5\t1\t1\t1\t1\t3\t110\t20\t70\t15\t90\tprotect\n"
       "5\t1\t1\t1\t1\t4\t185\t20\t40\t15\t88\tyou!\n"
       "5\t1\t2\t1\t1\t1\t30\t200\t60\t15\t20\tnoise\n"          # low conf -> dropped
       "5\t1\t3\t1\t1\t1\t30\t300\t45\t15\t97\tRun!\n")

class TestCore(unittest.TestCase):
    def test_google_payload(self):
        self.assertEqual(parse_google_payload([[["سلاو ", "Hello"], ["دونیا", "world"]]]), "سلاو دونیا")

    def test_split_long(self):
        parts = split_long_text("One. " * 600, limit=200)
        self.assertTrue(all(len(p) <= 200 for p in parts)); self.assertGreater(len(parts), 5)

    def test_tsv_grouping(self):
        segs = parse_tesseract_tsv(TSV)
        self.assertEqual([s.text for s in segs], ["I will protect you!", "Run!"])
        self.assertEqual(segs[0].box, (10, 20, 215, 15))

    def test_glossary_and_mock(self):
        g = load_glossary(Path(__file__).parent / "glossary.json")
        self.assertEqual(g[normalize("Who are you?!").lower()], "تۆ کێیت؟!")

    def test_cache_prevents_burn(self):
        """Same sentence twice -> ONE provider 'request', second is free from cache."""
        calls = []
        with tempfile.TemporaryDirectory() as d:
            tr = Translator(providers=["mock"], cache=TranslationCache(Path(d) / "c.sqlite"),
                            glossary={}, delay=0)
            real = tr._provider("mock")
            tr._funcs["mock"] = lambda t, *a, **k: (calls.append(t), f"CKB({t})")[1]
            out = tr.translate_many(["Attack!", "Attack!", "  Attack!  "])
            tr.translate_one("Attack!")  # second run: cache, not provider
        self.assertEqual(out, ["CKB(Attack!)"] * 3)
        self.assertEqual(len(calls), 1, f"provider called {len(calls)}x, must be 1")

    def test_provider_chain_fallback(self):
        def boom(t, *a, **k): raise RuntimeError("burned")
        with tempfile.TemporaryDirectory() as d:
            tr = Translator(providers=["google-free", "mock"], cache=TranslationCache(Path(d) / "c.sqlite"),
                            glossary={}, delay=0)
            tr._funcs["google-free"] = boom
            tr._funcs["mock"] = lambda t, *a, **k: "باشە"
            self.assertEqual(tr.translate_one("Okay."), "باشە")

if __name__ == "__main__":
    unittest.main()
