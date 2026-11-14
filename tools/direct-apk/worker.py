#!/usr/bin/env python3
"""Human/agent observe-act-observe session on an empty hosted Android emulator.
Uses the unchanged regular release APK. Never runs arbitrary shell commands,
reads app-private files, uploads reader inputs, or treats this as a release gate.
"""
import base64
import hashlib
import importlib.util
import json
import os
from pathlib import Path
import re
import signal
import struct
import subprocess
import time
import urllib.error
import urllib.parse
import urllib.request

ROOT = Path(__file__).resolve().parents[2]
REPOSITORY = "s1po0/Kurd-man"
CHECK_NAME = "Direct regular APK · observe / act / observe"
# Historical 1.8.2 delivered-APK pin retained for the older workflow contract;
# the current candidate is bound to its verified build digest at runtime.
APK_SHA256 = "d03ee91ff681e21e2e4cf4938106415f9e5e43d7837a03af976c62e3be97a300"
SESSION = "koorix-regular-184-observe-2"
ACTIONS = {"snapshot", "tap", "tap_id", "swipe", "back", "launch", "public_comic_url", "finish"}
MAX_COMMANDS = 32


def validate_command(value):
    if not isinstance(value, dict) or value.get("session") != SESSION:
        raise ValueError("Wrong inspection session")
    seq = value.get("sequence")
    if type(seq) is not int or not 0 <= seq <= MAX_COMMANDS:
        raise ValueError("Invalid bounded command sequence")
    action = value.get("action")
    if action not in ACTIONS:
        raise ValueError("Only public UI gestures and snapshot/finish are allowed")
    allowed = {"session", "sequence", "action", "expectedFrame"}
    if action == "tap":
        allowed |= {"x", "y"}
        coordinates(value, ("x", "y"))
    elif action == "swipe":
        allowed |= {"x", "y", "toX", "toY", "durationMs"}
        coordinates(value, ("x", "y", "toX", "toY"))
        if type(value.get("durationMs")) is not int or not 100 <= value["durationMs"] <= 1500:
            raise ValueError("Invalid swipe duration")
    elif action == "tap_id":
        allowed |= {"id"}
        if not isinstance(value.get("id"), str) or not re.fullmatch(r"[A-Za-z][A-Za-z0-9_]{0,63}", value["id"]):
            raise ValueError("Invalid native resource ID")
    if set(value) - allowed:
        raise ValueError("Unexpected control fields")
    expected = value.get("expectedFrame")
    if seq == 0:
        if action != "snapshot" or expected is not None:
            raise ValueError("The initial command must be a non-mutating snapshot")
    elif not isinstance(expected, str) or not re.fullmatch(r"[0-9a-f]{64}", expected):
        raise ValueError("Every later command needs the last observed frame digest")
    return value


def coordinates(value, names):
    for name in names:
        bound = 1080 if name.lower().endswith("x") else 1920
        if type(value.get(name)) is not int or not 0 <= value[name] < bound:
            raise ValueError("Gesture coordinates outside the fixed display")


class GitHub:
    def __init__(self, token):
        self.token = token

    def api(self, path, payload=None, method=None):
        data = None if payload is None else json.dumps(payload).encode()
        request = urllib.request.Request("https://api.github.com/repos/" + REPOSITORY + "/" + path,
                                         data=data, method=method or ("GET" if payload is None else "POST"),
                                         headers={"Authorization": "Bearer " + self.token,
                                                  "Accept": "application/vnd.github+json",
                                                  "Content-Type": "application/json", "User-Agent": "Koorix-direct-native-QA"})
        with urllib.request.urlopen(request, timeout=30) as response:
            raw = response.read(8_000_001)
        if len(raw) > 8_000_000:
            raise ValueError("Over-budget inspection API response")
        return json.loads(raw)

    def blob(self, data):
        if not 0 < len(data) < 3_000_000:
            raise ValueError("Over-budget synthetic inspection blob")
        result = self.api("git/blobs", {"content": base64.b64encode(data).decode(), "encoding": "base64"})
        expected = hashlib.sha1(f"blob {len(data)}\0".encode() + data).hexdigest()
        if result["sha"] != expected:
            raise ValueError("Public synthetic frame blob digest differs")
        return {"blob": result["sha"], "bytes": len(data), "sha256": hashlib.sha256(data).hexdigest()}

    def command(self, branch):
        query = urllib.parse.urlencode({"ref": branch})
        payload = self.api("contents/tools/direct-apk/control.json?" + query)
        if payload.get("size", 99999) > 2048:
            raise ValueError("Over-budget UI command")
        return validate_command(json.loads(base64.b64decode(payload["content"])))


def device_class():
    spec = importlib.util.spec_from_file_location("koorix_actual_device", ROOT / "tools/emulator-tests/driver.py")
    module = importlib.util.module_from_spec(spec)
    spec.loader.exec_module(module)
    return module.Device


def act(device, command):
    action = command["action"]
    if action == "tap":
        device.shell("input", "tap", command["x"], command["y"])
    elif action == "tap_id":
        device.tap(device.control(command["id"], enabled=True, timeout=25))
    elif action == "swipe":
        device.shell("input", "swipe", command["x"], command["y"], command["toX"], command["toY"], command["durationMs"])
    elif action == "back":
        device.shell("input", "keyevent", "4")
    elif action == "launch":
        device.launch()
    elif action == "public_comic_url":
        # The only permitted text input is this public CC-BY test page, after
        # the observer explicitly selects the address control. No private URL.
        device.shell("input", "text", "https://www.peppercarrot.com/en/webcomic/ep01_Potion-of-Flight.html")
    # snapshot/finish are not Android mutations.


def capture(device, github):
    # PNG and UI XML come from the real current Android surface, not a web mock.
    ui = None
    try:
        import xml.etree.ElementTree as ET
        ui = ET.tostring(device.ui(), encoding="utf-8")
    except (subprocess.SubprocessError, ET.ParseError):
        pass  # Busy UI may not idle; absence of XML is never a fabricated tree.
    image = device.adb("exec-out", "screencap", "-p")
    if not image.startswith(b"\x89PNG\r\n\x1a\n") or len(image) >= 3_000_000:
        raise ValueError("Invalid real screen PNG")
    if struct.unpack(">II", image[16:24]) != (1080, 1920):
        raise ValueError("Unexpected native display dimensions")
    focus = device.shell("dumpsys", "window")
    current = next((line.strip()[:300] for line in focus.splitlines() if "mCurrentFocus=" in line), "unavailable")
    result = {"image": github.blob(image), "focusedWindow": current,
              "observedAtEpoch": int(time.time()), "uiXml": None}
    if ui is not None and 10 < len(ui) < 300_000:
        result["uiXml"] = github.blob(ui)
    return result


def main():
    if os.environ.get("GITHUB_ACTIONS") != "true" or os.environ.get("RUNNER_ENVIRONMENT") != "github-hosted":
        raise RuntimeError("Only an empty hosted test emulator is authorized")
    if os.environ.get("GITHUB_REPOSITORY") != REPOSITORY:
        raise RuntimeError("Unexpected inspection repository")
    branch = os.environ["GITHUB_REF_NAME"]
    if branch != "arena/d0574002-kurd-man":
        raise RuntimeError("Not the current owner-authorized Arena branch")
    for secret in ("KOORIX_RELEASE_KEYSTORE", "KOORIX_RELEASE_STORE_PASSWORD", "KOORIX_RELEASE_KEY_PASSWORD", "TELEGRAM_BOT_TOKEN"):
        if os.environ.get(secret):
            raise RuntimeError("No signing/Telegram secrets belong in this inspection job")
    github = GitHub(os.environ.pop("GH_TOKEN"))
    out = ROOT / "android/app/build/outputs/direct-apk"
    out.mkdir(parents=True, exist_ok=True)
    apk = Path(os.environ["APK_FILE"])
    expected_sha = os.environ.get("APK_SHA256", "")
    if not re.fullmatch(r"[a-f0-9]{64}", expected_sha) or hashlib.sha256(apk.read_bytes()).hexdigest() != expected_sha:
        raise ValueError("Not the verified regular candidate APK")
    check = github.api("check-runs", {"name": CHECK_NAME, "head_sha": os.environ["GITHUB_SHA"], "status": "in_progress",
                                     "output": {"title": "Starting the actual regular APK", "summary": "Empty Android emulator; same-key unreleased regular APK."}})
    check_id = check["id"]
    state = {"scope": "Manual observe-act-observe, real installed regular candidate APK; public CC-BY Pepper&Carrot by David Revoy may appear; NOT physical-phone/general semantic acceptance",
             "session": SESSION, "apkSha256": expected_sha, "version": "1.8.4", "runId": int(os.environ["GITHUB_RUN_ID"]),
             "launchCommit": os.environ["GITHUB_SHA"], "branch": branch, "status": "starting", "sequence": -1,
             "readerDataUsed": False, "alternateAppOrDebugVariant": False, "actions": [], "frames": []}

    def record_frame(sequence):
        frame = capture(device, github)
        state.update(frame)
        state["frames"].append({"sequence": sequence, **frame})

    def publish():
        github.api(f"check-runs/{check_id}", {"output": {"title": "Actual Koorix 1.8.4 · " + state["status"],
                                                          "summary": json.dumps(state, ensure_ascii=False)}}, method="PATCH")
        (out / "session.json").write_text(json.dumps(state, ensure_ascii=False, indent=2) + "\n")

    def expire(_number, _frame):
        raise RuntimeError("Direct inspection reached its 20-minute total budget")
    signal.signal(signal.SIGALRM, expire)
    signal.alarm(20 * 60)
    device = device_class()(out, int(os.environ["EMULATOR_PID"]))
    try:
        device.wait(lambda: device.shell("getprop", "sys.boot_completed") == "1", "Android boot", timeout=240)
        state["api"] = int(device.shell("getprop", "ro.build.version.sdk"))
        state["abis"] = device.shell("getprop", "ro.product.cpu.abilist").split(",")
        state["nativeBridge"] = device.shell("getprop", "ro.dalvik.vm.native.bridge")
        if state["api"] != 34 or "arm64-v8a" not in state["abis"]:
            raise RuntimeError("Required official Android34 ARM64 compatibility unavailable")
        device.shell("wm", "size", "1080x1920")
        device.shell("wm", "density", "420")
        device.shell("svc", "power", "stayon", "true")
        for name in ("window_animation_scale", "transition_animation_scale", "animator_duration_scale"):
            device.shell("settings", "put", "global", name, "0")
        device.adb("install", "--no-streaming", "-r", str(apk.resolve()), timeout=120)
        package = device.shell("dumpsys", "package", "com.koorix.app")
        if "versionName=1.8.4" not in package or "versionCode=20" not in package:
            raise RuntimeError("Unexpected actual installed version")
        device.shell("pm", "grant", "com.koorix.app", "android.permission.POST_NOTIFICATIONS")
        device.shell("input", "keyevent", "82")
        device.launch()
        device.publish_source()  # Authored fixture only; never import a reader's page.
        record_frame(0)
        state.update({"status": "waiting-for-next-observed-action", "sequence": 0})
        publish()
        while True:
            try:
                command = github.command(branch)
            except (urllib.error.URLError, TimeoutError) as problem:
                # Transient API availability is not a UI failure. Fail closed on
                # authentication/not-found/rate-limit responses; retry transport/5xx
                # only, still bounded by the session's total alarm.
                if isinstance(problem, urllib.error.HTTPError) and problem.code < 500:
                    raise
                time.sleep(5)
                continue
            if command["sequence"] <= state["sequence"]:
                time.sleep(5)
                continue
            if command["sequence"] != state["sequence"] + 1:
                raise ValueError("Command sequence is not consecutive")
            if command.get("expectedFrame") != state["image"]["sha256"]:
                raise ValueError("Gesture must be based on the last actual observed frame")
            act(device, command)
            time.sleep(.6)
            record_frame(command["sequence"])
            state["sequence"] = command["sequence"]
            state["actions"].append({"sequence": command["sequence"], "action": command["action"],
                                     "frameSha256": state["image"]["sha256"]})
            if command["action"] == "finish":
                state["status"] = "finished-manual-inspection-not-a-test-verdict"
                publish()
                github.api(f"check-runs/{check_id}", {"status": "completed", "conclusion": "neutral"}, method="PATCH")
                break
            publish()
    except Exception as problem:
        state["status"] = "unavailable-or-interrupted"
        state["failure"] = type(problem).__name__ + ": " + str(problem)[:180]
        try:
            publish()
            github.api(f"check-runs/{check_id}", {"status": "completed", "conclusion": "failure"}, method="PATCH")
        except Exception:
            pass
        raise
    finally:
        signal.alarm(0)
        (out / "session.json").write_text(json.dumps(state, ensure_ascii=False, indent=2) + "\n")


if __name__ == "__main__":
    main()
