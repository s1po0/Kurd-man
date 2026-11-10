#!/usr/bin/env python3
"""Collect only named synthetic native reading-studio captures from Android CI.
No reader files, arbitrary screenshots, device accounts or signing material.
"""
import argparse
import base64
import hashlib
import json
from pathlib import Path
import re
import subprocess

ROOT=Path(__file__).resolve().parents[2]
NAMES={f"main-{tab}-en-{mode}" for tab in ("home","style","work","settings","reports") for mode in ("light","dark")}
NAMES|={f"main-home-{lang}-{mode}" for lang in ("ckb","ar") for mode in ("light","dark")}
NAMES|={"main-home-small","main-home-landscape","main-home-tablet","main-settings-large-type"}
NAMES|={f"{page}-{mode}" for page in ("library","studio","story-settings") for mode in ("light","dark")}
NAMES|={"review-light"}
EXPECTED={f"{name}-sdk{sdk}.png" for name in NAMES for sdk in (26,34)}

def blob(repo,data):
    result=subprocess.run(["gh","api","--method","POST",f"repos/{repo}/git/blobs","--input","-"],
        input=json.dumps({"content":base64.b64encode(data).decode(),"encoding":"base64"}),text=True,capture_output=True,check=True,timeout=45)
    sha=json.loads(result.stdout)["sha"]
    if not re.fullmatch(r"[a-f0-9]{40}",sha):raise ValueError("Invalid public blob identifier")
    return sha

def main():
    parser=argparse.ArgumentParser(description=__doc__);parser.add_argument("--repository",required=True);parser.add_argument("--commit",required=True);parser.add_argument("--run",required=True,type=int);args=parser.parse_args()
    if not re.fullmatch(r"[\w.-]+/[\w.-]+",args.repository)or not re.fullmatch(r"[a-f0-9]{40}",args.commit):raise ValueError("Invalid repository/commit")
    captures=[]
    for path in sorted((ROOT/"android/app/build/outputs/ui-tests").glob("*.png")):
        if path.is_symlink()or path.name not in EXPECTED:raise ValueError("Not an allowlisted synthetic UI capture")
        data=path.read_bytes()
        if not data.startswith(b"\x89PNG\r\n\x1a\n")or not 100<len(data)<1_000_000:raise ValueError("Invalid or budget-exceeding native UI PNG")
        sha=blob(args.repository,data);captures.append({"name":path.name,"bytes":len(data),"sha256":hashlib.sha256(data).hexdigest(),"blob":sha});print(f"Synthetic native UI {path.name}: {sha}")
    report={"scope":"Actual Android Java Views/XML under native Robolectric/Skia with synthetic data; NOT physical-device/TalkBack/IME acceptance","commit":args.commit,"run":args.run,"captures":captures}
    sha=blob(args.repository,(json.dumps(report,indent=2)+"\n").encode());print(f"::notice::NATIVE_READING_STUDIO_REPORT_BLOB={sha} captures={len(captures)}")
    if {c["name"]for c in captures}!=EXPECTED:raise SystemExit("Native UI capture set incomplete; production distribution is blocked")

if __name__=="__main__":main()
