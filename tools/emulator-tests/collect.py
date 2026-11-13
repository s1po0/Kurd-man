#!/usr/bin/env python3
"""Publish strictly named synthetic installed-APK evidence, never arbitrary screenshots/logs."""
import argparse
import base64
import hashlib
import json
from pathlib import Path
import re
import subprocess

ROOT = Path(__file__).resolve().parents[2]
CAPTURES = {"home-ckb.png", "home-en-light.png", "home-en-dark.png", "source-chooser.png",
            "source-selected.png", "translating.png", "results.png", "results-text.png", "studio.png",
            "studio-size-90.png", "studio-undo-100.png", "studio-export.png", "reopened-result.png",
            "manual-keep-alive.png", "manual-stopped.png", "pdf-export.png", "web-browser.png", "web-no-text.png", "web-rendered.png", "web-original.png", "failure.png"}
ALLOWED = CAPTURES | {"synthetic-source.png", "synthetic-result.jpg", "synthetic-studio-result.jpg", "synthetic-result.pdf", "synthetic-ui-pairs.json", "report.json", "emulator-diagnostics.txt"}
REQUIRED = (CAPTURES - {"translating.png", "failure.png"}) | {"synthetic-source.png",
            "synthetic-result.jpg", "synthetic-studio-result.jpg", "synthetic-result.pdf", "synthetic-ui-pairs.json", "report.json"}


def validate(path):
    if path.is_symlink() or path.name not in ALLOWED or not path.is_file():
        raise ValueError("Not an allowlisted synthetic emulator file")
    data = path.read_bytes()
    limit = 50_000 if path.suffix in (".txt", ".json") else 3_000_000
    if not 10 < len(data) < limit:
        raise ValueError("Empty or over-budget emulator evidence")
    if path.suffix == ".png" and not data.startswith(b"\x89PNG\r\n\x1a\n"):
        raise ValueError("Invalid emulator PNG")
    if path.suffix == ".jpg" and not data.startswith(b"\xff\xd8\xff"):
        raise ValueError("Invalid synthetic output JPEG")
    if path.suffix == ".pdf" and (not data.startswith(b"%PDF-") or b"%%EOF" not in data[-100:]):
        raise ValueError("Invalid synthetic PDF product")
    if path.suffix == ".json":
        report = json.loads(data)
        if report.get("readerDataUsed") is not False or report.get("alternateAppOrDebugVariant") is not False:
            raise ValueError("Evidence must identify the synthetic regular-APK-only scope")
    return data


def blob(repo, data):
    completed = subprocess.run(["gh", "api", "--method", "POST", f"repos/{repo}/git/blobs", "--input", "-"],
                               input=json.dumps({"content": base64.b64encode(data).decode(), "encoding": "base64"}),
                               text=True, capture_output=True, check=True, timeout=45)
    sha = json.loads(completed.stdout)["sha"]
    if not re.fullmatch("[a-f0-9]{40}", sha):
        raise ValueError("Invalid public evidence blob ID")
    return sha


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--repository", required=True)
    parser.add_argument("--commit", required=True)
    parser.add_argument("--run", required=True, type=int)
    args = parser.parse_args()
    if not re.fullmatch(r"[\w.-]+/[\w.-]+", args.repository) or not re.fullmatch("[a-f0-9]{40}", args.commit):
        raise ValueError("Invalid repository/commit")
    directory = ROOT / "android/app/build/outputs/emulator-tests"
    evidence = []
    for path in sorted(directory.iterdir() if directory.is_dir() else []):
        data = validate(path)
        evidence.append({"name": path.name, "bytes": len(data), "sha256": hashlib.sha256(data).hexdigest(),
                         "blob": blob(args.repository, data)})
    report = {"scope": "Actual installed regular signed ARM64 Koorix under Android emulator, authored synthetic translation fixture and public MDN example image; NOT physical-device/general semantic/reader-page proof",
              "commit": args.commit, "run": args.run, "files": evidence}
    sha = blob(args.repository, (json.dumps(report, indent=2) + "\n").encode())
    print(f"::notice::INSTALLED_APK_EMULATOR_REPORT_BLOB={sha} files={len(evidence)}")
    if not REQUIRED.issubset({item["name"] for item in evidence}):
        raise SystemExit("Installed-APK emulator evidence incomplete; no acceptance claimed")
    result = json.loads((directory / "report.json").read_text())
    if result["status"] != "passed":
        raise SystemExit("Installed-APK emulator check did not pass")


if __name__ == "__main__":
    main()
