"""Offline tests — no network, no tesseract, no Pillow needed.
Run:  python -m unittest test_translate_manhwa -v   (from manhwa/)
"""
import tempfile, unittest
from pathlib import Path
import translate_manhwa as tm
from translate_manhwa import (Segment, TranslationCache, Translator, _shape_with_native,
                              _text_kwargs, _wrap, assess_render_plan, assess_segments,
                              assign_render_boxes, dedupe_segments, dedupe_words,
                              filter_segments, find_sorani_font, is_probable_ocr_junk,
                              load_glossary, make_tiles, merge_same_bubble_segments,
                              parse_google_payload, parse_tesseract_tsv,
                              renderable_segments, split_long_text, normalize)

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

    def test_artwork_junk_and_same_bubble_merging(self):
        bubble = {"confidence": 95, "render_box": (40, 40, 240, 90),
                  "bubble_detected": True, "bubble_id": 7}
        segs = [
            Segment("PY", (300, 300, 30, 16), confidence=80),  # live-sample junk
            Segment("Hello", (60, 60, 70, 18), **bubble),
            Segment("there", (70, 86, 70, 18), **bubble),
            Segment("No!", (60, 220, 45, 18), confidence=96,
                    render_box=(40, 205, 130, 55), bubble_detected=True, bubble_id=8),
        ]
        self.assertTrue(is_probable_ocr_junk(segs[0]))
        self.assertEqual([s.text for s in filter_segments(segs)], ["Hello", "there", "No!"])
        merged = merge_same_bubble_segments(filter_segments(segs))
        self.assertEqual([s.text for s in merged], ["Hello there", "No!"])
        self.assertEqual(len(renderable_segments(merged)), 2)
        self.assertTrue(assess_render_plan(500, 600, merged)["ok"])

    def test_render_plan_rejects_unbacked_and_overlapping_boxes(self):
        unbacked = [Segment("Hello there", (50, 50, 160, 30), confidence=95)]
        bad = assess_render_plan(500, 600, unbacked)
        self.assertFalse(bad["ok"]); self.assertIn("no_bubble_backed_segments", bad["reasons"])
        overlapping = [
            Segment("One", (60, 60, 60, 20), confidence=95, render_box=(40, 40, 220, 90),
                    bubble_detected=True, bubble_id=1),
            Segment("Two", (70, 70, 60, 20), confidence=95, render_box=(45, 45, 220, 90),
                    bubble_detected=True, bubble_id=2),
        ]
        plan = assess_render_plan(500, 600, overlapping)
        self.assertFalse(plan["ok"]); self.assertIn("overlapping_render_boxes", plan["reasons"])

    def test_arabic_words_are_never_split_by_character(self):
        class FakeDraw:
            def textlength(self, text, font=None, **_kwargs):
                return len(text) * 10
        word = "دەقێکیکوردی"
        self.assertEqual(_wrap(FakeDraw(), word, object(), 50), [word])
        latin = _wrap(FakeDraw(), "abcdefghij", object(), 50)
        self.assertTrue(all(len(part) <= 5 for part in latin))

    def test_native_raqm_shaping_never_double_shapes(self):
        # GitHub's Pillow has Raqm/HarfBuzz: it must receive logical-order
        # Unicode. Reshaper output fed through Raqm was the live
        # "letter-by-letter" bug, so native mode is a strict pass-through.
        self.assertEqual(_shape_with_native("سلاو دونیا", True), "سلاو دونیا")
        self.assertEqual(_text_kwargs("سلاو", True), {"direction": "rtl", "language": "ckb"})
        self.assertEqual(_text_kwargs("hello", True), {})
        self.assertEqual(_text_kwargs("سلاو", False), {})

    def test_sorani_font_selection_is_explicit(self):
        with tempfile.TemporaryDirectory() as d:
            font = Path(d) / "NotoNaskhArabic-Regular.ttf"; font.write_bytes(b"font")
            self.assertEqual(find_sorani_font([str(Path(d) / "missing.ttf"), str(font)]), str(font))
            self.assertIsNone(find_sorani_font([str(Path(d) / "DejaVuSans.ttf")]))

    def test_bubble_detector_accepts_outlined_oval_not_blank_panel(self):
        try:
            import cv2  # noqa: F401
            from PIL import Image, ImageDraw
        except Exception:
            self.skipTest("OpenCV/Pillow not installed")
        with tempfile.TemporaryDirectory() as d:
            path = Path(d) / "sample.png"
            img = Image.new("RGB", (500, 500), (25, 35, 65)); draw = ImageDraw.Draw(img)
            draw.ellipse([50, 50, 350, 190], fill="white", outline="black", width=4)
            draw.rectangle([0, 300, 500, 430], fill="white")
            img.save(path)
            segs = assign_render_boxes(path, [
                Segment("Hello there", (130, 105, 120, 20), confidence=96),
                Segment("Panel junk", (210, 350, 100, 20), confidence=96),
            ])
        self.assertTrue(segs[0].bubble_detected)
        self.assertFalse(segs[1].bubble_detected)

    def test_bubble_crop_ocr_replaces_segment_spilling_into_artwork(self):
        try:
            import cv2  # noqa: F401
            from PIL import Image, ImageDraw
        except Exception:
            self.skipTest("OpenCV/Pillow not installed")
        img = Image.new("RGB", (500, 500), (25, 35, 65)); draw = ImageDraw.Draw(img)
        draw.ellipse([50, 50, 350, 190], fill="white", outline="black", width=4)
        # Tile OCR grouped bubble words with artwork to the right: its box
        # spills outside the bubble and would fail placement, exactly like
        # the rejected page's lower-left bubble.
        dirty = Segment("POISON junk", (130, 105, 280, 40), confidence=90)
        existing = [dirty]
        seen = {}
        def fake_ocr(path, src):
            with Image.open(path) as crop:
                seen["corner"] = crop.convert("L").getpixel((2, 2))
            return [Segment("Clean text", (300, 150, 240, 60), confidence=98)]
        original = tm._ocr_tile_segments
        tm._ocr_tile_segments = fake_ocr
        try:
            with tempfile.TemporaryDirectory() as d:
                extra = tm._ocr_uncovered_bubbles(img, existing, "en", Path(d))
        finally:
            tm._ocr_tile_segments = original
        self.assertEqual([s.text for s in extra], ["Clean text"])
        self.assertEqual(existing, [])  # dirty spilling segment was replaced
        # Outside-contour artwork is whitened before OCR, and the reading is
        # tied directly to the physical bubble so later strict assignment
        # cannot drop it and leave the bubble in English.
        self.assertGreater(seen["corner"], 240)
        self.assertTrue(extra[0].bubble_detected)
        self.assertTrue(extra[0].placement_confirmed)
        self.assertIsNotNone(extra[0].bubble_id)
        self.assertEqual(renderable_segments(extra), extra)

    def test_dedupe_prefers_bubble_backed_reading_over_longer_junk(self):
        dirty = Segment("POISON EVASION ORB? junkjunk", (80, 100, 240, 80), confidence=99)
        clean = Segment("POISON EVASION ORB?", (85, 105, 230, 70), confidence=95,
                        render_box=(90, 95, 220, 90), bubble_detected=True,
                        bubble_id=3, placement_confirmed=True)
        self.assertEqual([s.text for s in dedupe_segments([dirty, clean])],
                         ["POISON EVASION ORB?"])

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
