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

    def test_media_insert_accepts_only_public_external_image_collections(self):
        for volume in ("external", "external_primary"):
            uri = "content://media/" + volume + "/images/media/123"
            self.assertEqual(uri, driver.media_insert_uri("Inserted " + uri + "\n"))
        for invalid in ("Error while accessing provider", "content://private/images/media/123",
                        "content://media/internal/images/media/123", "file:///sdcard/private"):
            self.assertIsNone(driver.media_insert_uri(invalid))

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
