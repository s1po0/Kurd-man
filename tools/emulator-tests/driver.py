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
import signal
import subprocess
import time
import xml.etree.ElementTree as ET

PACKAGE = "com.koorix.app"
COMPONENT = PACKAGE + "/com.kurdman.app.MainActivity"
EXPECTED_SOURCE = ("ASPECT: STONE KEEPER ASPECT DESCRIPTION: This rare trait grants two abilities "
                   "and one skill. The bearer can sense nearby stones, but cannot move them.")
CAPTURES = {"home-ckb.png", "home-en-light.png", "home-en-dark.png", "source-chooser.png",
            "source-selected.png", "translating.png", "results.png", "results-text.png", "studio.png",
            "studio-size-90.png", "studio-undo-100.png", "studio-export.png", "reopened-result.png",
            "manual-keep-alive.png", "manual-stopped.png", "pdf-export.png", "failure.png"}
PRODUCT = re.compile(r"Koorix-koorix-qa-source-p-[a-f0-9-]{36}\.ckb\.jpg\Z")


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


def verify_fixture_targets(targets):
    # This authored fixture has a description-supported custodian, no sports.
    # Label substrings alone hid an observed goalkeeper mistranslation; these
    # assertions are fixture contracts, not a general Sorani semantic score.
    if targets.count("تایبەتمەندی:") != 2 or targets.count("وەسفی تایبەتمەندی:") != 1:
        raise AssertionError("Expected both distinct guarded field labels exactly once")
    if "تایبەتمەندی: پارێزەری بەرد" not in targets:
        raise AssertionError("Description-supported fixture role did not reach real translation/export")
    if "ناتوان" not in targets:
        raise AssertionError("The fixture's explicit inability is not present in the Sorani result")


def product_rows(output):
    """Parse only a disposable fixture's owner-scoped public MediaStore query."""
    if output == "No result found.":
        return []
    rows = []
    for line in output.splitlines():
        match = re.fullmatch(r"Row: ([0-9]+) _id=([1-9][0-9]*), _display_name=([^,\r\n]+), "
                             r"relative_path=([^,\r\n]+), mime_type=([^,\r\n]+), is_pending=([01])", line)
        if match is None or int(match[1]) != len(rows):
            raise ValueError("Invalid public MediaStore product query")
        rows.append({"id": int(match[2]), "name": match[3], "path": match[4], "mime": match[5], "pending": int(match[6])})
    if not rows:
        raise ValueError("Missing public MediaStore query response")
    return rows


def single_public_product(rows, with_pdf=False):
    if with_pdf:
        images = [row for row in rows if row["mime"] == "image/jpeg"]
        row = single_public_product(images)
        documents = [item for item in rows if item is not row]
        if (len(rows) != 2 or len(documents) != 1 or documents[0]["name"] != row["name"][:-4] + ".pdf"
                or documents[0]["path"] != "Download/Koorix/" or documents[0]["mime"] != "application/pdf"
                or documents[0]["pending"] != 0):
            raise AssertionError("Expected only the one image plus its requested PDF, never a TXT/extra copy")
        return row
    if len(rows) != 1:
        raise AssertionError("Expected exactly one owned public image and zero TXT files")
    row = rows[0]
    if (not PRODUCT.fullmatch(row["name"]) or row["path"] != "Download/Koorix/"
            or row["mime"] != "image/jpeg" or row["pending"] != 0):
        raise AssertionError("Unexpected, duplicate, TXT, legacy-folder or unfinished public product")
    return row


def media_query_uri(output):
    # Android14's content insert is silent, even on success. Query only the
    # exact authored image; reject zero/multiple rows or unexpected columns.
    found = re.fullmatch(r"\s*Row: 0 _id=([1-9][0-9]*)\s*", output)
    return "content://media/external/images/media/" + found.group(1) if found else None


def source_chooser_focused(window_dump):
    # GET_CONTENT may correctly go straight to DocumentsUI's PickActivity,
    # not a class literally called DocumentsActivity or ChooserActivity.
    return bool(re.search(r"(?m)^\s*mCurrentFocus=.*(?:android/.*(?:Chooser|Resolver)Activity|"
                          r"com\.(?:google\.android|android)\.documentsui/|com\.android\.intentresolver/)", window_dump))


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

    def adb(self, *args, data=None, timeout=30, include_stderr=False):
        completed = subprocess.run(["adb", "-s", "emulator-5554", *map(str, args)],
                                   input=data, capture_output=True, check=True, timeout=timeout)
        return completed.stdout + (completed.stderr if include_stderr else b"")

    def shell(self, *args, data=None, timeout=30, include_stderr=False):
        return self.adb("shell", shlex.join(map(str, args)), data=data, timeout=timeout,
                        include_stderr=include_stderr).decode("utf-8", "replace").strip()

    def ui(self):
        output = self.shell("uiautomator", "dump", "/data/local/tmp/koorix-qa-window.xml", timeout=35)
        if "UI hierchary dumped to" not in output and "UI hierarchy dumped to" not in output:
            # A failed dump can leave an old XML file behind. Never pass a
            # state transition by reading a stale previous-screen hierarchy.
            raise subprocess.CalledProcessError(1, ["uiautomator", "dump"])
        return ET.fromstring(self.shell("cat", "/data/local/tmp/koorix-qa-window.xml"))

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
        print("::notice::REAL_APK_CHECK=" + code, flush=True)

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

    def publish_source(self):
        query = ("content", "query", "--uri", "content://media/external/images/media", "--projection", "_id",
                 "--where", "_display_name='koorix-qa-source.png' AND relative_path='Pictures/Koorix-QA/' AND mime_type='image/png'")
        before = self.shell(*query, include_stderr=True)
        if before != "No result found.":
            raise RuntimeError("Synthetic image collection is not empty: " + before[:200])
        inserted = self.shell("content", "insert", "--uri", "content://media/external/images/media",
                              "--bind", "_display_name:s:koorix-qa-source.png", "--bind", "mime_type:s:image/png",
                              "--bind", "relative_path:s:Pictures/Koorix-QA/", include_stderr=True)
        selected = self.shell(*query, include_stderr=True)
        uri = media_query_uri(selected)
        if uri is None:
            raise RuntimeError("Synthetic MediaStore query after insertion failed: " + (inserted + selected)[:300])
        source = (self.output / "synthetic-source.png").read_bytes()
        self.shell("content", "write", "--uri", uri, data=source)
        written = self.adb("exec-out", "content", "read", "--uri", uri)
        if hashlib.sha256(source).digest() != hashlib.sha256(written).digest():
            raise AssertionError("Synthetic public MediaStore bytes differ from the authored PNG")
        self.check("synthetic_media_bytes_verified")
        return uri

    def reveal(self, identifier, direction="down"):
        for _ in range(9):
            item = node(self.ui(), identifier=identifier)
            if item is not None:
                try:
                    x, y = center(item)
                    if 0 <= x < 1080 and 200 < y < 1750:
                        return item
                except ValueError:
                    pass
            start, end = (1400, 650) if direction == "down" else (650, 1400)
            self.shell("input", "swipe", "540", start, "540", end, "350")
        raise AssertionError("Control not reachable by ordinary scrolling: " + identifier)

    def public_product(self, with_pdf=False):
        def query(collection):
            output = self.shell("content", "query", "--uri", "content://media/external/" + collection,
                                "--projection", "_id:_display_name:relative_path:mime_type:is_pending",
                                "--where", "owner_package_name='com.koorix.app'", include_stderr=True)
            return product_rows(output)
        row = single_public_product(query("file"), with_pdf=with_pdf)
        gallery = single_public_product(query("images/media"))
        if gallery != row:
            raise AssertionError("Gallery and Files must expose the same one public image, not separate copies")
        return row

    def products(self):
        row = self.public_product()
        data = self.adb("exec-out", "cat", "/sdcard/Download/Koorix/" + row["name"])
        if not data.startswith(b"\xff\xd8\xff") or not 100 < len(data) < 2_000_000:
            raise ValueError("Over-budget, empty or invalid synthetic image export")
        (self.output / "synthetic-result.jpg").write_bytes(data)
        self.report["publicImageRows"] = 1
        self.report["publicTxtRows"] = 0
        self.report["publicFolder"] = row["path"]
        self.check("one_public_gallery_image_no_automatic_txt")
        return row["id"]

    def read_result_text(self):
        # Read the app's normal visible TextView. Never use private storage or
        # retain an automatic app TXT merely to keep the older test working.
        item = self.reveal("resultText")
        exported = pairs(item.get("text", ""))
        (self.output / "synthetic-ui-pairs.json").write_text(json.dumps({
            "source": "public_resultText_UI", "readerDataUsed": False, "alternateAppOrDebugVariant": False,
            "pairs": [{"source": a, "target": b} for a, b in exported]
        }, ensure_ascii=False, indent=2) + "\n")
        if words(" ".join(source for source, _ in exported)) != words(EXPECTED_SOURCE):
            raise AssertionError("Real OCR omitted, duplicated, reordered or changed synthetic source words")
        verify_fixture_targets(" ".join(target for _, target in exported))
        self.report["sourceBlocks"] = len(exported)
        self.report["targetBlocks"] = len(exported)
        self.capture("results-text.png")
        self.check("real_ocr_complete_source_no_duplicate_words")
        self.check("selected_live_translator_and_contextual_labels_retained_in_ui")

    def completed_ui(self):
        self.control("quickThemeButton", enabled=True)
        root = self.ui()
        if node(root, identifier="stopJobButton") is not None:
            raise AssertionError("Idle completion must not show a stale STOP-current-job action")
        if node(root, identifier="resultEmptyHint") is not None:
            raise AssertionError("Completed preview is still labelled empty")
        self.check("completed_preview_not_labelled_empty_and_idle_stop_absent")

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
        for setting in ("window_animation_scale", "transition_animation_scale", "animator_duration_scale"):
            self.shell("settings", "put", "global", setting, "0")
        self.shell("input", "keyevent", "82")
        self.adb("install", "--no-streaming", "-r", str(apk.resolve()), timeout=120)
        package = self.shell("dumpsys", "package", PACKAGE)
        if "versionName=1.8.4" not in package or "versionCode=20" not in package:
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
        # Exercise the real WebView against MDN's public static example. Its
        # photograph is not a translation-quality fixture; the goal here is to
        # verify actual same-origin page discovery, bounded image capture and
        # handoff to the real OCR job, not a claim about manga compatibility.
        self.tap(self.reveal("webReaderButton"))
        self.control("webReaderAddress", enabled=True)
        self.click("webReaderAddress")
        self.shell("input", "text", "https://mdn.github.io/dom-examples/history-api/index.html")
        self.shell("input", "keyevent", "4")  # dismiss keyboard
        self.click("webReaderGo")
        self.wait(lambda: any("Let's look at some creatures" in x.get("text", "")
                              for x in self.ui().iter("node")), "MDN example page loaded", timeout=90)
        self.click("webReaderTranslate")
        self.control("workspaceLanguageButton", timeout=150)
        self.control("pickButton", enabled=True, timeout=180)
        public = self.shell("content", "query", "--uri", "content://media/external/file",
                            "--projection", "_id:_display_name", "--where", "_display_name LIKE 'Koorix-web-chapter%'")
        if public != "No result found.":
            raise AssertionError("Web reader job published a public file without explicit export")
        self.check("webview_same_origin_public_example_capture_to_real_ocr_no_automatic_public_export")
        # Publish before opening Recent so DocumentsUI offers the authored file.
        # An adb am SEND extra is not a normal app share: its URI lacks the
        # framework's Extra.STREAM -> ClipData migration and actual read grant.
        # Select through the owner's one real GET_CONTENT input instead.
        self.publish_source()
        self.click("pickButton")
        def chooser():
            root = self.ui()
            focus = self.shell("dumpsys", "window")
            foreground = source_chooser_focused(focus)
            system_ui = any(item.get("package") in ("android", "com.android.intentresolver",
                              "com.android.documentsui", "com.google.android.documentsui") for item in root.iter("node"))
            return root if foreground and system_ui else None
        self.wait(chooser, "Actual foreground system source chooser")
        self.capture("source-chooser.png")
        self.check("one_original_system_source_chooser")
        selected = self.wait(lambda: node(self.ui(), text="koorix-qa-source.png"),
                             "Authored source visible in the actual system chooser")
        self.tap(selected)
        self.control("translateButton", enabled=True)
        self.check("ordinary_system_chooser_image_import")
        self.capture("source-selected.png")
        self.click("translateButton")
        # The busy frame may be too brief on a fast machine. Never fabricate it.
        initial = self.wait(lambda: self.ui(), "Fresh busy or completed result hierarchy", timeout=50)
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
            return sum(bool(PRODUCT.fullmatch(name)) for name in names) == 1
        self.wait(ready, "Synthetic translation did not export its real product", timeout=300)
        self.completed_ui()
        self.capture("results.png")
        product_id = self.products()
        self.read_result_text()
        self.tap(self.reveal("editResultButton", direction="up"))
        self.control("editorCanvas")
        self.capture("studio.png")
        self.check("real_saved_result_opens_native_studio")
        self.click("editorSectionStyleButton")
        self.tap(self.wait(lambda: node(self.ui(), text="A−"), "Studio size action"))
        self.wait(lambda: self.control("editorStatus").get("text") == "Text size: 90%", "Size90 status")
        self.capture("studio-size-90.png")
        self.click("editorUndoButton")
        self.wait(lambda: self.control("editorStatus").get("text") == "Text size: 100%", "Undo must also refresh the size status")
        root = self.ui()
        if not any(item.get("text", "").endswith("100%") and item.get("resource-id", "") != PACKAGE + ":id/editorStatus"
                   for item in root.iter("node")):
            raise AssertionError("Undo status and visible100% control disagree")
        self.capture("studio-undo-100.png")
        self.check("studio_undo_restores_controls_and_status_together")
        self.click("editorSaveButton")
        self.control("workspaceLanguageButton")
        self.completed_ui()
        if self.public_product()["id"] != product_id:
            raise AssertionError("Studio export created a second public image instead of updating the same result")
        self.capture("studio-export.png")
        self.check("studio_export_same_owned_media_row_canonical_folder_no_txt")
        self.shell("am", "force-stop", PACKAGE)
        self.launch()
        self.click("navResultsButton")
        self.completed_ui()
        if self.public_product()["id"] != product_id:
            raise AssertionError("Reopening a saved project republished a duplicate public image")
        self.capture("reopened-result.png")
        self.check("process_reopen_keeps_result_without_republication")
        # PdfDocument is a real Android service/native boundary, not supplied
        # by Robolectric. Exercise the existing optional PDF on this exact APK.
        self.click("navSettingsButton")
        pdf_option = self.reveal("createPdfCheck")
        if pdf_option.get("checked") != "false":
            raise AssertionError("Fresh synthetic install unexpectedly enabled automatic PDF")
        self.tap(pdf_option)
        self.click("navResultsButton")
        self.tap(self.reveal("editResultButton", direction="up"))
        self.control("editorCanvas")
        self.click("editorSaveButton")
        self.control("workspaceLanguageButton")
        self.completed_ui()
        pdf_row = self.public_product(with_pdf=True)
        if pdf_row["id"] != product_id:
            raise AssertionError("Requested PDF must not create a second Gallery image")
        pdf_data = self.adb("exec-out", "cat", "/sdcard/Download/Koorix/" + pdf_row["name"][:-4] + ".pdf")
        if not pdf_data.startswith(b"%PDF-") or b"%%EOF" not in pdf_data[-100:] or not 1000 < len(pdf_data) < 3_000_000:
            raise AssertionError("Requested real Android PDF is empty, unfinished or over budget")
        (self.output / "synthetic-result.pdf").write_bytes(pdf_data)
        studio_data = self.adb("exec-out", "cat", "/sdcard/Download/Koorix/" + pdf_row["name"])
        if not studio_data.startswith(b"\xff\xd8\xff") or not 100 < len(studio_data) < 2_000_000:
            raise AssertionError("Studio did not retain a valid actual JPEG product")
        (self.output / "synthetic-studio-result.jpg").write_bytes(studio_data)
        self.report["publicPdfRows"] = 1
        self.capture("pdf-export.png")
        self.check("requested_real_pdf_preserved_with_one_gallery_image_no_txt")
        self.click("navSettingsButton")
        self.tap(self.reveal("keepAliveToggleButton"))
        self.control("stopJobButton", enabled=True)
        service = r"ServiceRecord\{[^}]*com\.koorix\.app/com\.kurdman\.app\.KeepAliveService"
        self.wait(lambda: bool(re.search(service, self.shell("dumpsys", "activity", "services", PACKAGE))),
                  "Actual manual foreground protection did not start", timeout=30)
        self.capture("manual-keep-alive.png")
        self.click("stopJobButton")
        self.wait(lambda: node(self.ui(), identifier="stopJobButton") is None, "Manual STOP must hide after stopping", timeout=30)
        self.wait(lambda: re.search(service, self.shell("dumpsys", "activity", "services", PACKAGE)) is None,
                  "Manual STOP did not actually stop the foreground service", timeout=30)
        self.capture("manual-stopped.png")
        self.check("explicit_manual_protection_retains_stop_and_stops_actual_service")
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
    def runtime_budget_exhausted(_number, _frame):
        raise TimeoutError("Actual-APK driver exceeded its 15-minute total runtime budget")
    signal.signal(signal.SIGALRM, runtime_budget_exhausted)
    signal.alarm(15 * 60)
    try:
        device.run(args.apk)
    except Exception as problem:
        device.report["status"] = "failed"
        device.report["failure"] = type(problem).__name__ + ": " + str(problem)[:300]
        try:
            focus = device.shell("dumpsys", "window")
            device.report["focusedWindow"] = next((line.strip()[:300] for line in focus.splitlines()
                                                      if "mCurrentFocus=" in line), "unavailable")
        except Exception:
            pass
        try:
            device.capture("failure.png")
        except Exception:
            pass  # No screenshot is evidence when Android itself was unavailable.
        raise
    finally:
        signal.alarm(0)
        (args.out / "report.json").write_text(json.dumps(device.report, indent=2) + "\n")


if __name__ == "__main__":
    main()
