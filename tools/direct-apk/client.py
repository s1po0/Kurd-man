#!/usr/bin/env python3
"""Local observation client. Downloads hash-verified real frames, never uploads images.
Actions are explicit small JSON commands, committed to the current Arena branch.
No public ADB listener or device credentials are used.
"""
import argparse
import base64
import hashlib
import json
import os
from pathlib import Path
import subprocess
import time

from worker import APK_SHA256, CHECK_NAME, REPOSITORY, ROOT, SESSION, validate_command

CACHE = ROOT / ".cache/direct-apk-session"


def api(path):
    result = subprocess.run(["gh", "api", f"repos/{REPOSITORY}/{path}"],
                            capture_output=True, timeout=40,
                            env={**os.environ, "GODEBUG": "tlsmlkem=0"}, check=True)
    if len(result.stdout) > 8_000_000:
        raise ValueError("Over-budget native observation response")
    return json.loads(result.stdout)


def fetch_blob(metadata, target):
    data = base64.b64decode(api("git/blobs/" + metadata["blob"])["content"], validate=False)
    if len(data) != metadata["bytes"] or len(data) >= 3_000_000:
        raise ValueError("Native observation size differs")
    if hashlib.sha256(data).hexdigest() != metadata["sha256"]:
        raise ValueError("Native observation SHA256 differs")
    if hashlib.sha1(f"blob {len(data)}\0".encode() + data).hexdigest() != metadata["blob"]:
        raise ValueError("Native observation Git blob digest differs")
    target.write_bytes(data)


def watch(commit):
    CACHE.mkdir(parents=True, exist_ok=True)
    seen = set()
    deadline = time.monotonic() + 30 * 60
    failure_count = 0
    print("Waiting for the actual regular APK Android session", flush=True)
    while time.monotonic() < deadline:
        try:
            checks = api("commits/" + commit + "/check-runs?per_page=100")["check_runs"]
            matching = [check for check in checks if check["name"] == CHECK_NAME]
            if not matching:
                runs = api("actions/runs?head_sha=" + commit + "&per_page=20")["workflow_runs"]
                for run in runs:
                    if run["name"] == "Koorix direct APK inspection" and run["status"] == "completed":
                        raise RuntimeError("Direct Android session did not start: run " + str(run["id"]) + " " + str(run["conclusion"]))
                time.sleep(5)
                continue
            check = max(matching, key=lambda item: item["id"])
            summary = check.get("output", {}).get("summary", "")
            if not summary.startswith("{"):
                time.sleep(5)
                continue
            state = json.loads(summary)
            if state["session"] != SESSION or state["launchCommit"] != commit or state["apkSha256"] != APK_SHA256:
                raise ValueError("Wrong APK/session/launch commit")
            if state.get("readerDataUsed") is not False or state.get("alternateAppOrDebugVariant") is not False:
                raise ValueError("Unexpected observation scope")
            for frame in state["frames"]:
                sequence = frame["sequence"]
                if sequence in seen:
                    continue
                image = CACHE / f"frame-{sequence:02}.png"
                fetch_blob(frame["image"], image)
                if frame.get("uiXml"):
                    fetch_blob(frame["uiXml"], CACHE / f"frame-{sequence:02}.xml")
                (CACHE / f"frame-{sequence:02}.json").write_text(json.dumps(frame, indent=2) + "\n")
                seen.add(sequence)
                print(f"OBSERVED sequence={sequence} check={check['id']} actualPNG={image}", flush=True)
            (CACHE / "latest.json").write_text(json.dumps(state, ensure_ascii=False, indent=2) + "\n")
            failure_count = 0
            if check["status"] == "completed":
                print("SESSION_ENDED " + state["status"] + " conclusion=" + str(check["conclusion"]), flush=True)
                return 0 if state["status"] == "finished-manual-inspection-not-a-test-verdict" else 1
        except subprocess.CalledProcessError as problem:
            error = problem.stderr.decode(errors="replace") if isinstance(problem.stderr, bytes) else str(problem.stderr)
            if "401" in error or "authentication" in error.lower():
                raise RuntimeError("GitHub connection needs attention; reconnect GitHub in Arena") from problem
            failure_count += 1
            if failure_count >= 6:
                raise RuntimeError("Repeated GitHub observation transport failure") from problem
            print("Native observation API temporarily unavailable; not a fabricated frame", flush=True)
        time.sleep(4)
    raise TimeoutError("No completed direct observation within the local 30-minute watch budget")


def prepare(args):
    state = json.loads((CACHE / "latest.json").read_text())
    if state["status"] != "waiting-for-next-observed-action" or state["session"] != SESSION:
        raise ValueError("There is no active matching observed Android session")
    command = {"session": SESSION, "sequence": state["sequence"] + 1,
               "action": args.action, "expectedFrame": state["image"]["sha256"]}
    fields = {"tap_id": ("id",), "tap": ("x", "y"),
              "swipe": ("x", "y", "toX", "toY", "durationMs")}.get(args.action, ())
    for key in fields:
        value = getattr(args, key)
        if value is None:
            raise ValueError("Missing --" + key)
        command[key] = value
    validate_command(command)
    (ROOT / "tools/direct-apk/control.json").write_text(json.dumps(command, indent=2) + "\n")
    print("Prepared one explicit observed-screen gesture: " + json.dumps(command))


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    commands = parser.add_subparsers(dest="command", required=True)
    observe = commands.add_parser("watch")
    observe.add_argument("--commit", required=True)
    action = commands.add_parser("act")
    action.add_argument("action", choices=("snapshot", "tap_id", "tap", "swipe", "back", "launch", "finish"))
    action.add_argument("--id")
    for field in ("x", "y", "toX", "toY", "durationMs"):
        action.add_argument("--" + field, type=int)
    args = parser.parse_args()
    if args.command == "watch":
        raise SystemExit(watch(args.commit))
    prepare(args)


if __name__ == "__main__":
    main()
