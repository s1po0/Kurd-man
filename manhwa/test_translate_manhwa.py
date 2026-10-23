"""Offline tests — no network, no tesseract, no Pillow needed.
Run:  python -m unittest test_translate_manhwa -v   (from manhwa/)
"""
import tempfile, unittest
from pathlib import Path
from translate_manhwa import (Segment, TranslationCache, Translator, assess_segments,
                              dedupe_segments, dedupe_words, load_glossary, make_tiles,
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

    def test_tsv_geometry_ignores_one_giant_block(self):
        # Every word deliberately has the same Tesseract block/par numbers.
        # The old parser merged this into one segment; geometry must find 3.
        rows = [
            ("I", 10, 20, 20, 16), ("will", 40, 20, 42, 16), ("protect", 92, 20, 72, 16),
            ("you", 50, 42, 38, 16), ("always", 98, 42, 62, 16),
            ("Run", 30, 240, 38, 16), ("away", 78, 240, 52, 16),
            ("Hide", 520, 240, 48, 16), ("now", 578, 240, 42, 16),
        ]
        tsv = "level\tpage_num\tblock_num\tpar_num\tline_num\tword_num\tleft\ttop\twidth\theight\tconf\ttext\n" + "".join(
            f"5\t1\t1\t1\t1\t{i}\t{x}\t{y}\t{w}\t{h}\t96\t{text}\n"
            for i, (text, x, y, w, h) in enumerate(rows, 1))
        self.assertEqual([s.text for s in parse_tesseract_tsv(tsv)],
                         ["I will protect you always", "Run away", "Hide now"])

    def test_tiles_cover_tall_image_without_core_gaps(self):
        tiles = make_tiles(800, 5000, tile_height=1400, overlap=280)
        self.assertGreater(len(tiles), 3)
        self.assertEqual((tiles[0].y0, tiles[-1].y1), (0, 5000))
        for prev, nxt in zip(tiles, tiles[1:]):
            self.assertGreater(prev.y1, nxt.y0)       # tiles overlap
            self.assertEqual(prev.core_y1, nxt.core_y0)  # ownership has no seam gap

    def test_dedupe_and_quality_gate(self):
        dup = dedupe_segments([
            Segment("Run!", (20, 30, 80, 20), confidence=70),
            Segment("Run!", (22, 31, 80, 20), confidence=97),
        ])
        self.assertEqual(len(dup), 1)
        self.assertEqual(dup[0].confidence, 97)
        bad = assess_segments(800, 6000, [Segment("Hi", (10, 10, 80, 20), confidence=95)])
        self.assertFalse(bad["ok"])
        self.assertIn("too_few_segments_for_long_image", bad["reasons"])
        good = [Segment("Hello there", (50, 100 + i * 400, 220, 60), confidence=95) for i in range(8)]
        self.assertTrue(assess_segments(800, 6000, good)["ok"])

    def test_dedupe_words_from_two_ocr_passes(self):
        words = dedupe_words([
            {"text": "Run", "x": 10, "y": 10, "w": 40, "h": 16, "conf": 55},
            {"text": "Run", "x": 11, "y": 10, "w": 40, "h": 16, "conf": 96},
            {"text": "Run", "x": 90, "y": 10, "w": 40, "h": 16, "conf": 90},
        ])
        self.assertEqual([(w["x"], w["conf"]) for w in words], [(11, 96), (90, 90)])

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
