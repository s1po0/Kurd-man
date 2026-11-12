#!/usr/bin/env python3
"""Publish only allowlisted, synthetic native-glyph fixtures as public Git blobs.
No reader pages, credentials, signing material or arbitrary directories are read.
Called by the existing Android CI job; gh handles its preconfigured auth.
"""
import argparse
import base64
import hashlib
import json
from pathlib import Path
import re
import subprocess

ROOT = Path(__file__).resolve().parents[2]
ALLOWED = re.compile(r"(?:(?:circle|rectangle|triangle|quadrilateral)-(?:ckb|en)|concave-ckb|dark-panel-ckb)-sdk(?:26|34)\.png\Z")


def blob(repository, data):
    payload = json.dumps({"content": base64.b64encode(data).decode(), "encoding": "base64"})
    result = subprocess.run(["gh", "api", "--method", "POST", f"repos/{repository}/git/blobs", "--input", "-"],
                            input=payload, capture_output=True, text=True, check=True, timeout=45)
    sha = json.loads(result.stdout)["sha"]
    if not re.fullmatch(r"[a-f0-9]{40}", sha):
        raise ValueError("Unexpected public blob identifier")
    return sha


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--repository", required=True)
    parser.add_argument("--commit", required=True)
    parser.add_argument("--run", required=True, type=int)
    args = parser.parse_args()
    if not re.fullmatch(r"[\w.-]+/[\w.-]+", args.repository) or not re.fullmatch(r"[a-f0-9]{40}", args.commit):
        raise ValueError("Invalid public repository/commit")
    directory = ROOT / "android/app/build/outputs/bubble-tests"
    fixtures = []
    for path in sorted(directory.glob("*.png")):
        if not ALLOWED.fullmatch(path.name) or path.is_symlink():
            raise ValueError("Not an allowlisted synthetic fixture")
        data = path.read_bytes()
        if not data.startswith(b"\x89PNG\r\n\x1a\n") or not 100 < len(data) < 500_000:
            raise ValueError("Invalid/budget-exceeding native fixture")
        sha = blob(args.repository, data)
        fixtures.append({"name": path.name, "bytes": len(data), "sha256": hashlib.sha256(data).hexdigest(), "blob": sha})
        print(f"Synthetic bubble fixture {path.name} blob={sha}")
    evidence = {"scope": "Synthetic native Android/Skia glyph fixtures, NOT device/real-page/semantic acceptance",
                "commit": args.commit, "run": args.run, "fixtures": fixtures}
    report = blob(args.repository, (json.dumps(evidence, indent=2) + "\n").encode())
    print(f"::notice::SYNTHETIC_BUBBLE_REPORT_BLOB={report} fixtures={len(fixtures)}")
    # An incomplete run may still publish diagnostics, but cannot become proof.
    expected = {f"{shape}-{lang}-sdk{sdk}.png" for shape in ("circle", "rectangle", "triangle", "quadrilateral")
                for lang in ("ckb", "en") for sdk in (26, 34)}
    expected |= {f"concave-ckb-sdk{sdk}.png" for sdk in (26, 34)}
    expected |= {f"dark-panel-ckb-sdk{sdk}.png" for sdk in (26, 34)}
    if {f["name"] for f in fixtures} != expected:
        raise SystemExit("Synthetic fixture set incomplete; production distribution is blocked")


if __name__ == "__main__":
    main()
