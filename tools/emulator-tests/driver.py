#!/usr/bin/env python3
"""Observe the real regular APK on a disposable CI emulator using only synthetic data.
No instrumentation/debug APK, run-as, reader inputs or app-private storage access.
"""
import argparse
import hashlib
import json
import os
from pathlib import Path
import re
import shlex
import subprocess
import time
import xml.etree.ElementTree as ET

PACKAGE = "com.koorix.app"
COMPONENT = PACKAGE + "/com.kurdman.app.MainActivity"
EXPECTED_SOURCE = ("ASPECT: STONE KEEPER ASPECT DESCRIPTION: This rare trait grants two abilities "
                   "and one skill. The bearer can sense nearby stones, but cannot move them.")
CAPTURES = {"home-ckb.png", "home-en-light.png", "home-en-dark.png", "source-chooser.png",
            "source-selected.png", "translating.png", "results.png", "studio.png", "failure.png"}
PRODUCT = re.compile(r"Koorix-koorix-qa-source-p-[a-f0-9-]{36}\.ckb\.(?:jpg|txt)\Z")


def words(text):
    return " ".join(re.findall(r"\w+", text.lower(), re.UNICODE))


def pairs(text):
    result = []
    for block in text.strip().split("\n\n"):
        if not block.strip():
            continue
        source, separator, target = block.partition("\n→ ")
        if not separator or not source.strip() or not target.strip():
            raise ValueError("Malformed synthetic source/target export")
        result.append((source.strip(), target.strip()))
    if not result:
        raise ValueError("Missing synthetic OCR results")
    return result


def node(root, identifier=None, text=None):
    for item in root.iter("node"):
        if identifier is not None and not item.get("resource-id", "").endswith(":id/" + identifier):
            continue
        if text is not None and item.get("text") != text:
            continue
        if identifier is None and text is None:
            continue
        return item
    return None


def center(item):
    found = re.fullmatch(r"\[(\d+),(\d+)\]\[(\d+),(\d+)\]", item.get("bounds", ""))
    if found is None:
        raise ValueError("Missing real control bounds")
    left, top, right, bottom = map(int, found.groups())
    if right <= left or bottom <= top or item.get("enabled") != "true":
        raise ValueError("Control is hidden or disabled")
    return (left + right) // 2, (top + bottom) // 2


class Device:
    def __init__(self, output, emulator_pid=None):
        self.output = output
        self.emulator_pid = emulator_pid
        self.report = {"scope": "Installed regular signed APK on API34 Android emulator; synthetic source only; NOT physical-device/reader-page/general semantic acceptance",
                       "status": "running", "checks": [], "meaningReviewedByNativeHuman": False,
                       "readerDataUsed": False, "alternateAppOrDebugVariant": False}

    def adb(self, *args, data=None, timeout=30):
        completed = subprocess.run(["adb", "-s", "emulator-5554", *map(str, args)],
                                   input=data, capture_output=True, check=True, timeout=timeout)
        return completed.stdout

    def shell(self, *args, data=None, timeout=30):
        return self.adb("shell", shlex.join(map(str, args)), data=data, timeout=timeout).decode("utf-8", "replace").strip()

    def ui(self):
        self.shell("uiautomator", "dump", "/sdcard/koorix-qa-window.xml", timeout=35)
        return ET.fromstring(self.shell("cat", "/sdcard/koorix-qa-window.xml"))

    def wait(self, predicate, label, timeout=90):
        deadline = time.monotonic() + timeout
        last = None
        while time.monotonic() < deadline:
            if self.emulator_pid is not None:
                try:
                    os.kill(self.emulator_pid, 0)
                except ProcessLookupError as problem:
                    raise RuntimeError("Android emulator exited before " + label) from problem
            try:
                result = predicate()
                # ElementTree leaf controls are falsey despite being real nodes.
                if result is not None and result is not False:
                    return result
            except (subprocess.SubprocessError, ET.ParseError) as problem:
                last = type(problem).__name__
            time.sleep(.5)
        raise TimeoutError(label + (" (" + last + ")" if last else ""))

    def control(self, identifier, enabled=None, timeout=90):
        def find():
            found = node(self.ui(), identifier=identifier)
            if found is None or (enabled is not None and found.get("enabled") != str(enabled).lower()):
                return None
            return found
        return self.wait(find, "Missing control " + identifier, timeout)

    def tap(self, item):
        x, y = center(item)
        self.shell("input", "tap", x, y)

    def click(self, identifier):
        self.tap(self.control(identifier, enabled=True))

    def check(self, code):
        self.report["checks"].append(code)
        print("Real APK check: " + code, flush=True)

    def capture(self, name):
        if name not in CAPTURES:
            raise ValueError("Not an allowlisted synthetic emulator capture")
        data = self.adb("exec-out", "screencap", "-p")
        if not data.startswith(b"\x89PNG\r\n\x1a\n") or not 100 < len(data) < 3_000_000:
            raise ValueError("Invalid or over-budget emulator PNG")
        (self.output / name).write_bytes(data)

    def launch(self):
        self.shell("am", "start", "-W", "-n", COMPONENT, timeout=60)
        self.control("workspaceLanguageButton")

    def theme(self, dark):
        expected = "Enable light mode" if dark else "Enable dark mode"
        item = self.control("quickThemeButton", enabled=True)
        if item.get("content-desc") != expected:
            self.tap(item)
        self.wait(lambda: self.control("quickThemeButton").get("content-desc") == expected,
                  "Native theme action did not change", timeout=30)

    def import_source(self):
        inserted = self.shell("content", "insert", "--uri", "content://media/external/images/media",
                              "--bind", "_display_name:s:koorix-qa-source.png", "--bind", "mime_type:s:image/png",
                              "--bind", "relative_path:s:Pictures/Koorix-QA/")
        found = re.search(r"content://media/external/images/media/\d+", inserted)
        if not found:
            raise RuntimeError("Synthetic MediaStore insertion failed")
        uri = found.group()
        self.shell("content", "write", "--uri", uri, data=(self.output / "synthetic-source.png").read_bytes())
        self.shell("am", "start", "-W", "-n", COMPONENT, "-a", "android.intent.action.SEND", "-t", "image/png",
                   "--eu", "android.intent.extra.STREAM", uri, "--grant-read-uri-permission", timeout=60)
        self.control("translateButton", enabled=True)
        self.check("ordinary_media_share_import")

    def products(self):
        listing = self.shell("ls", "-1", "/sdcard/Download/Koorix")
        names = [name for name in listing.splitlines() if PRODUCT.fullmatch(name)]
        images = [name for name in names if name.endswith(".jpg")]
        texts = [name for name in names if name.endswith(".txt")]
        if len(images) != 1 or len(texts) != 1:
            raise ValueError("Expected one synthetic public image/text result")
        for original, target in [(images[0], "synthetic-result.jpg"), (texts[0], "synthetic-pairs.txt")]:
            data = self.adb("exec-out", "cat", "/sdcard/Download/Koorix/" + original)
            if not 10 < len(data) < (2_000_000 if target.endswith("jpg") else 50_000):
                raise ValueError("Over-budget or empty synthetic export")
            (self.output / target).write_bytes(data)
        exported = pairs((self.output / "synthetic-pairs.txt").read_text())
        if words(" ".join(source for source, _ in exported)) != words(EXPECTED_SOURCE):
            raise AssertionError("Real OCR omitted, duplicated, reordered or changed synthetic source words")
        targets = " ".join(target for _, target in exported)
        if "تایبەتمەندی:" not in targets or "وەسفی تایبەتمەندی:" not in targets:
            raise AssertionError("Guarded field labels did not reach real translation/export")
        self.report["sourceBlocks"] = len(exported)
        self.report["targetBlocks"] = len(exported)
        self.check("real_ocr_complete_source_no_duplicate_words")
        self.check("selected_live_translator_and_contextual_labels_exported")

    def run(self, apk):
        self.wait(lambda: self.shell("getprop", "sys.boot_completed") == "1", "Android boot timeout", timeout=240)
        self.report["api"] = int(self.shell("getprop", "ro.build.version.sdk"))
        self.report["abis"] = self.shell("getprop", "ro.product.cpu.abilist").split(",")
        self.report["nativeBridge"] = self.shell("getprop", "ro.dalvik.vm.native.bridge")
        self.report["apkSha256"] = hashlib.sha256(apk.read_bytes()).hexdigest()
        if self.report["api"] != 34 or "arm64-v8a" not in self.report["abis"]:
            raise RuntimeError("Official emulator does not advertise required ARM64 compatibility; no alternate APK is substituted")
        self.shell("wm", "size", "1080x1920")
        self.shell("wm", "density", "420")
        self.shell("input", "keyevent", "82")
        self.adb("install", "--no-streaming", "-r", str(apk.resolve()), timeout=120)
        package = self.shell("dumpsys", "package", PACKAGE)
        if "versionName=1.8.2" not in package or "versionCode=18" not in package:
            raise AssertionError("Installed APK is not the expected regular update")
        self.check("same_verified_arm64_regular_apk_installed")
        self.shell("pm", "grant", PACKAGE, "android.permission.POST_NOTIFICATIONS")
        self.launch()
        self.capture("home-ckb.png")
        self.click("workspaceLanguageButton")
        self.tap(self.wait(lambda: node(self.ui(), text="English"), "English language action"))
        self.wait(lambda: self.control("workspaceLanguageButton").get("text") == "English", "English recreation")
        self.theme(False)
        self.capture("home-en-light.png")
        self.theme(True)
        self.capture("home-en-dark.png")
        self.shell("am", "force-stop", PACKAGE)
        self.launch()
        if self.control("quickThemeButton").get("content-desc") != "Enable light mode":
            raise AssertionError("Actual process relaunch lost the selected theme")
        self.check("native_theme_switch_and_process_relaunch_persistence")
        self.theme(False)
        self.click("pickButton")
        self.wait(lambda: "ChooserActivity" in self.shell("dumpsys", "activity", "activities")
                  or "documentsui" in self.shell("dumpsys", "activity", "activities"), "System source chooser")
        self.capture("source-chooser.png")
        self.shell("input", "keyevent", "4")
        self.control("workspaceLanguageButton")
        self.check("one_original_system_source_chooser")
        self.import_source()
        self.capture("source-selected.png")
        self.click("translateButton")
        # The busy frame may be too brief on a fast machine. Never fabricate it.
        initial = self.ui()
        stop = node(initial, identifier="stopJobButton")
        if stop is not None and stop.get("enabled") == "true":
            theme = node(initial, identifier="quickThemeButton")
            if theme is None or theme.get("enabled") != "false":
                raise AssertionError("Protected job did not disable theme recreation")
            self.capture("translating.png")
            self.check("busy_stop_visible_and_theme_recreation_disabled")
        # Export readiness is observable without depending on whether a long
        # result page places its studio button above the screen fold.
        def ready():
            names = self.shell("ls", "-1", "/sdcard/Download/Koorix").splitlines()
            return sum(bool(PRODUCT.fullmatch(name)) for name in names) == 2
        self.wait(ready, "Synthetic translation did not export its real products", timeout=300)
        self.capture("results.png")
        self.products()
        # Scroll only this disposable synthetic result surface, no private access.
        for _ in range(7):
            item = node(self.ui(), identifier="editResultButton")
            if item is not None:
                try:
                    center(item)
                    self.tap(item)
                    break
                except ValueError:
                    pass
            self.shell("input", "swipe", "540", "1400", "540", "650", "350")
        self.control("editorCanvas")
        self.capture("studio.png")
        self.check("real_saved_result_opens_native_studio")
        crashes = self.adb("logcat", "-b", "crash", "-d").decode("utf-8", "replace")
        if PACKAGE in crashes:
            raise AssertionError("Regular APK crash observed on the emulator")
        self.check("no_regular_app_crash_observed")
        self.report["status"] = "passed"


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--apk", required=True, type=Path)
    parser.add_argument("--out", required=True, type=Path)
    parser.add_argument("--emulator-pid", type=int)
    args = parser.parse_args()
    if os.environ.get("GITHUB_ACTIONS") != "true" or os.environ.get("RUNNER_ENVIRONMENT") != "github-hosted":
        raise RuntimeError("Only an empty GitHub-hosted emulator is authorized, never a connected owner's phone")
    args.out.mkdir(parents=True, exist_ok=True)
    device = Device(args.out, args.emulator_pid)
    try:
        device.run(args.apk)
    except Exception as problem:
        device.report["status"] = "failed"
        device.report["failure"] = type(problem).__name__ + ": " + str(problem)[:300]
        try:
            device.capture("failure.png")
        except Exception:
            pass  # No screenshot is evidence when Android itself was unavailable.
        raise
    finally:
        (args.out / "report.json").write_text(json.dumps(device.report, indent=2) + "\n")


if __name__ == "__main__":
    main()
