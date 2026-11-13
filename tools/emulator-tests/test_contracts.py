"""Offline driver/allowlist contracts only, never a claim of APK/device acceptance."""
import importlib.util
import json
from pathlib import Path
import tempfile
import unittest
import xml.etree.ElementTree as ET


def load(name):
    spec = importlib.util.spec_from_file_location("qa_" + name, Path(__file__).with_name(name + ".py"))
    result = importlib.util.module_from_spec(spec)
    spec.loader.exec_module(result)
    return result


driver = load("driver")
collector = load("collect")


class Contracts(unittest.TestCase):
    def test_pairs_keep_all_source_words_and_original_order(self):
        text = "ASPECT: STONE KEEPER\n→ تایبەتمەندی: بەردپارێز\n\nASPECT DESCRIPTION:\n→ وەسفی تایبەتمەندی:\n\nThis rare trait grants two abilities and one skill.\n→ دەقی نموونە"
        exported = driver.pairs(text)
        self.assertEqual(3, len(exported))
        self.assertEqual("ASPECT DESCRIPTION:", exported[1][0])
        self.assertIn("two abilities and one skill", exported[2][0])
        for invalid in ("", "a\n→ ", "\n→ ب", "a -> ب", "a\n→ ب\n\nstray source"):
            with self.subTest(invalid=invalid), self.assertRaises(ValueError):
                driver.pairs(invalid)

    def test_source_contract_does_not_hide_duplicates_or_negation_changes(self):
        expected = driver.words(driver.EXPECTED_SOURCE)
        for changed in (driver.EXPECTED_SOURCE + " The bearer can sense", driver.EXPECTED_SOURCE.replace("cannot", "can"),
                        driver.EXPECTED_SOURCE.replace("two abilities and one skill", "one ability and two skills")):
            self.assertNotEqual(expected, driver.words(changed))
        self.assertEqual(expected, driver.words(driver.EXPECTED_SOURCE.upper().replace(":", " : ")))

    def test_media_query_requires_one_exact_public_image_id(self):
        self.assertEqual("content://media/external/images/media/123",
                         driver.media_query_uri("Row: 0 _id=123\n"))
        for invalid in ("", "No result found.", "Row: 0 _id=0", "Row: 0 _id=-1",
                        "Row: 0 _id=123\nRow: 1 _id=124", "Row: 0 _id=123, private=bad",
                        "Error while accessing provider", "content://private/images/media/123"):
            with self.subTest(invalid=invalid):
                self.assertIsNone(driver.media_query_uri(invalid))

    def test_silent_android_content_insert_is_followed_by_exact_query_and_readback(self):
        class SyntheticDevice(driver.Device):
            def __init__(self, output):
                super().__init__(output)
                self.queries = 0
                self.written = None

            def shell(self, *args, data=None, **kwargs):
                if args[:2] == ("content", "query"):
                    self.queries += 1
                    return "No result found." if self.queries == 1 else "Row: 0 _id=123"
                if args[:2] == ("content", "insert"):
                    return ""  # Actual Android14 Content.InsertCommand does not print the URI.
                if args[:2] == ("content", "write"):
                    self.written = (args[-1], data)
                    return ""
                raise AssertionError("Fixture publication must not launch/share into the app")

            def adb(self, *args, **kwargs):
                if args[:3] == ("exec-out", "content", "read"):
                    return (self.output / "synthetic-source.png").read_bytes()
                raise AssertionError("Unexpected ADB command")

            def check(self, code):
                self.report["checks"].append(code)  # Never emit REAL_APK notices from this mock.

        with tempfile.TemporaryDirectory() as folder:
            output = Path(folder)
            source = b"\x89PNG\r\n\x1a\n" + b"synthetic" * 30
            (output / "synthetic-source.png").write_bytes(source)
            device = SyntheticDevice(output)
            self.assertEqual("content://media/external/images/media/123", device.publish_source())
            self.assertEqual(2, device.queries)
            self.assertEqual(("content://media/external/images/media/123", source), device.written)
            self.assertIn("synthetic_media_bytes_verified", device.report["checks"])

            stale = SyntheticDevice(output)
            stale.queries = 1
            with self.assertRaisesRegex(RuntimeError, "not empty"):
                stale.publish_source()
            self.assertIsNone(stale.written)

            class CorruptedDevice(SyntheticDevice):
                def adb(self, *args, **kwargs):
                    return b"different public image bytes"
            corrupted = CorruptedDevice(output)
            with self.assertRaisesRegex(AssertionError, "bytes differ"):
                corrupted.publish_source()
            self.assertNotIn("synthetic_media_bytes_verified", corrupted.report["checks"])

    def test_focus_accepts_real_system_pick_activity_not_activity_history(self):
        for component in ("com.google.android.documentsui/com.android.documentsui.picker.PickActivity",
                          "com.android.documentsui/.files.FilesActivity", "android/com.android.internal.app.ChooserActivity",
                          "com.android.intentresolver/.ChooserActivity"):
            self.assertTrue(driver.source_chooser_focused("  mCurrentFocus=Window{123 u0 " + component + "}"))
        for wrong in ("Activity history: android/com.android.internal.app.ChooserActivity",
                      "mCurrentFocus=Window{123 u0 com.koorix.app/com.kurdman.app.MainActivity}",
                      "mCurrentFocus=null"):
            self.assertFalse(driver.source_chooser_focused(wrong))

    def test_xml_leaf_controls_are_not_treated_as_missing(self):
        root = ET.fromstring('<hierarchy><node resource-id="com.koorix.app:id/quickThemeButton" enabled="true" bounds="[10,20][60,80]" text="Dark" /></hierarchy>')
        item = driver.node(root, identifier="quickThemeButton")
        self.assertIsNotNone(item)
        self.assertEqual((35, 50), driver.center(item))
        self.assertIs(item, driver.Device(Path("unused")).wait(lambda: item, "leaf", timeout=.1))
        self.assertIsNone(driver.node(root, identifier="unknown"))

    def test_hidden_or_disabled_controls_are_never_tapped(self):
        for bounds, enabled in (("[0,0][0,0]", "true"), ("[2,2][5,8]", "false"), ("bad", "true")):
            item = ET.Element("node", {"bounds": bounds, "enabled": enabled})
            with self.assertRaises(ValueError):
                driver.center(item)

    def test_public_product_name_is_fixed_to_authored_fixture(self):
        name = "Koorix-koorix-qa-source-p-12345678-1234-1234-1234-123456789abc.ckb.jpg"
        self.assertIsNotNone(driver.PRODUCT.fullmatch(name))
        for bad in ("../../secret", "Koorix-reader-p-12345678-1234-1234-1234-123456789abc.ckb.jpg", name + ".txt"):
            self.assertIsNone(driver.PRODUCT.fullmatch(bad))

    def test_capture_allowlist_matches_collector(self):
        self.assertEqual(driver.CAPTURES, collector.CAPTURES)
        self.assertTrue(collector.REQUIRED.issubset(collector.ALLOWED))
        self.assertNotIn("failure.png", collector.REQUIRED)
        self.assertNotIn("translating.png", collector.REQUIRED)

    def test_collector_rejects_arbitrary_files_symlinks_and_private_scope(self):
        with tempfile.TemporaryDirectory() as folder:
            base = Path(folder)
            valid = base / "home-en-light.png"
            valid.write_bytes(b"\x89PNG\r\n\x1a\n" + b"x" * 200)
            self.assertEqual(valid.read_bytes(), collector.validate(valid))
            wrong = base / "reader.png"
            wrong.write_bytes(valid.read_bytes())
            with self.assertRaises(ValueError):
                collector.validate(wrong)
            link = base / "studio.png"
            link.symlink_to(valid)
            with self.assertRaises(ValueError):
                collector.validate(link)
            report = base / "report.json"
            report.write_text(json.dumps({"readerDataUsed": True, "alternateAppOrDebugVariant": False}))
            with self.assertRaises(ValueError):
                collector.validate(report)
            report.write_text(json.dumps({"readerDataUsed": False, "alternateAppOrDebugVariant": False}))
            self.assertTrue(collector.validate(report))


if __name__ == "__main__":
    unittest.main()
