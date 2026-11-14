"""Offline safety contracts only: these tests are NOT installed-APK evidence."""
import hashlib
import json
from pathlib import Path
import struct
import subprocess
import unittest
from unittest.mock import patch
import xml.etree.ElementTree as ET

import worker


class ControlContracts(unittest.TestCase):
    def valid(self, action="snapshot", **fields):
        return {"session": worker.SESSION, "sequence": 1, "action": action,
                "expectedFrame": "a" * 64, **fields}

    def test_initial_command_does_not_mutate_android(self):
        self.assertEqual(worker.validate_command({"session": worker.SESSION, "sequence": 0, "action": "snapshot"})["action"], "snapshot")
        with self.assertRaises(ValueError):
            worker.validate_command({"session": worker.SESSION, "sequence": 0, "action": "launch"})

    def test_current_observed_frame_required(self):
        for digest in (None, "", "a" * 63, "x" * 64, 1):
            with self.subTest(digest=digest), self.assertRaises(ValueError):
                worker.validate_command(self.valid(expectedFrame=digest))

    def test_no_cross_session_or_boolean_sequence(self):
        for fields in ({"session": "other"}, {"sequence": True}, {"sequence": -1}, {"sequence": worker.MAX_COMMANDS + 1}):
            with self.subTest(fields=fields), self.assertRaises(ValueError):
                worker.validate_command(self.valid(**fields))

    def test_sequences_and_snapshots_are_bounded(self):
        for sequence in (1, worker.MAX_COMMANDS):
            self.assertEqual(worker.validate_command(self.valid(sequence=sequence))["sequence"], sequence)

    def test_no_arbitrary_shell_install_private_read_or_uninstall(self):
        for action in ("shell", "uninstall", "clear", "pull", "run-as", "install", "download", "connect"):
            with self.subTest(action=action), self.assertRaises(ValueError):
                worker.validate_command(self.valid(action=action))
        with self.assertRaises(ValueError):
            worker.validate_command(self.valid(command="pm clear com.koorix.app"))

    def test_tap_coordinates_exclude_boolean_and_out_of_display(self):
        for x, y in ((-1, 0), (1080, 0), (0, 1920), (True, 20), (1.0, 20)):
            with self.subTest(x=x, y=y), self.assertRaises(ValueError):
                worker.validate_command(self.valid("tap", x=x, y=y))
        worker.validate_command(self.valid("tap", x=1079, y=1919))

    def test_swipe_is_bounded_and_complete(self):
        worker.validate_command(self.valid("swipe", x=500, y=1400, toX=500, toY=500, durationMs=350))
        for fields in ({"durationMs": 1501}, {"toX": 1080}, {"toY": -1}):
            command = self.valid("swipe", x=500, y=1400, toX=500, toY=500, durationMs=350)
            command.update(fields)
            with self.subTest(fields=fields), self.assertRaises(ValueError):
                worker.validate_command(command)

    def test_resource_identifier_is_not_a_shell_string(self):
        worker.validate_command(self.valid("tap_id", id="pickButton"))
        for identifier in ("pickButton;id", "../data", "a b", "a" * 65, "", None):
            with self.subTest(identifier=identifier), self.assertRaises(ValueError):
                worker.validate_command(self.valid("tap_id", id=identifier))

    def test_public_comic_action_has_only_one_fixed_public_url(self):
        events = []
        class Device:
            def shell(self, *args):
                events.append(args)
        command = self.valid("public_comic_url")
        worker.validate_command(command)
        worker.act(Device(), command)
        self.assertEqual(events, [("input", "text", "https://www.peppercarrot.com/en/webcomic/ep01_Potion-of-Flight.html")])
        with self.assertRaises(ValueError):
            worker.validate_command(self.valid("public_comic_url", url="https://private.example/"))

    def test_snapshot_and_finish_have_no_device_command(self):
        class NoDevice:
            def __getattr__(self, _name):
                raise AssertionError("No device mutation is permitted")
        for action in ("snapshot", "finish"):
            worker.act(NoDevice(), self.valid(action))

    def test_actual_gestures_use_only_fixed_adb_arguments(self):
        events = []
        class Device:
            def shell(self, *args):
                events.append(args)
        worker.act(Device(), self.valid("tap", x=10, y=20))
        worker.act(Device(), self.valid("back"))
        worker.act(Device(), self.valid("swipe", x=10, y=20, toX=30, toY=40, durationMs=350))
        self.assertEqual(events, [("input", "tap", 10, 20), ("input", "keyevent", "4"),
                                  ("input", "swipe", 10, 20, 30, 40, 350)])

    def test_capture_marks_missing_busy_ui_instead_of_reusing_a_tree(self):
        class Device:
            def ui(self):
                raise subprocess.CalledProcessError(1, ["uiautomator"])
            def adb(self, *args):
                return b"\x89PNG\r\n\x1a\n" + b"\0" * 8 + struct.pack(">II", 1080, 1920)
            def shell(self, *args):
                return "mCurrentFocus=real-window"
        class Github:
            def blob(self, data):
                return {"sha256": hashlib.sha256(data).hexdigest(), "bytes": len(data)}
        self.assertIsNone(worker.capture(Device(), Github())["uiXml"])

    def test_capture_rejects_wrong_native_display(self):
        class Device:
            def ui(self):
                return ET.Element("hierarchy")
            def adb(self, *args):
                return b"\x89PNG\r\n\x1a\n" + b"\0" * 8 + struct.pack(">II", 720, 1280)
        with self.assertRaisesRegex(ValueError, "dimensions"):
            worker.capture(Device(), None)

    def test_entry_refuses_non_hosted_device_before_any_network(self):
        with patch.dict("os.environ", {}, clear=True), self.assertRaisesRegex(RuntimeError, "empty hosted"):
            worker.main()

    def test_standalone_job_installs_and_exposes_adb_before_emulator(self):
        script = (worker.ROOT / "tools/direct-apk/run.sh").read_text()
        self.assertIn('"$SDKMANAGER" --install platform-tools emulator', script)
        export = script.index('export PATH="$ANDROID_HOME/platform-tools:')
        start = script.index('env -u GH_TOKEN "$ANDROID_HOME/emulator/emulator"')
        self.assertLess(export, start)

    def test_api_token_is_not_in_sdk_emulator_or_cleanup_environment(self):
        script = (worker.ROOT / "tools/direct-apk/run.sh").read_text()
        self.assertLess(script.index("unset GH_TOKEN"), script.index('"$SDKMANAGER" --install'))
        self.assertIn('GH_TOKEN="$INSPECTION_API_TOKEN" python3 tools/direct-apk/worker.py', script)
        self.assertIn('os.environ.pop("GH_TOKEN")', Path(worker.__file__).read_text())

    def test_no_rebuild_signing_or_delivery_in_inspection_workflow(self):
        workflow = (worker.ROOT / ".github/workflows/koorix-direct-apk.yml").read_text()
        for prohibited in ("secrets.", "assembleKoorix", "store.py restore", "gh release create", "sendDocument"):
            self.assertNotIn(prohibited, workflow)
        self.assertIn("persist-credentials: false", workflow)
        self.assertIn(worker.APK_SHA256, workflow)


if __name__ == "__main__":
    unittest.main()
