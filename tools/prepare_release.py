#!/usr/bin/env python3
"""Prepare an APK and latest.json for the fitlog-updates bucket; does not upload."""
from pathlib import Path
import argparse, hashlib, json, os, re, shutil, subprocess
from urllib.parse import urlsplit
parser=argparse.ArgumentParser()
parser.add_argument("--apk", required=True, type=Path)
parser.add_argument("--base-url", required=True, help="HTTPS public URL of fitlog-updates bucket")
parser.add_argument("--notes", default="")
parser.add_argument("--output", type=Path, default=Path(".local-tools/releases"))
args=parser.parse_args()
root=Path(__file__).resolve().parents[1]
apk=args.apk.resolve()
url=args.base_url.rstrip("/")
parts=urlsplit(url)
if parts.scheme!="https" or not parts.hostname or parts.username or parts.query or parts.fragment:
    parser.error("base-url must be an HTTPS bucket URL")
sdk=Path(os.environ.get("ANDROID_HOME",str(root.parent/"yuumi/.local-tools/android-sdk")))
candidates=sorted((sdk/"build-tools").glob("*/aapt"),reverse=True)
if not candidates: parser.error("aapt not found; set ANDROID_HOME")
badging=subprocess.check_output([str(candidates[0]),"dump","badging",str(apk)],text=True)
match=re.search(r"package: name='([^']+)' versionCode='(\d+)' versionName='([^']+)'",badging)
if not match or match[1]!="dev.lzk.fitlog": parser.error("unexpected application")
code=int(match[2]);version=match[3]
if apk.stat().st_size>50*1024*1024: parser.error("APK exceeds configured 50 MB bucket limit")
output=(root/args.output).resolve()
if not output.is_relative_to(root): parser.error("output must stay in FitLog project")
output.mkdir(parents=True,exist_ok=True)
name=f"FitLog-{code}.apk"
destination=output/name
if destination.exists() and destination.read_bytes()!=apk.read_bytes(): parser.error("different APK already exists for this versionCode")
if destination!=apk: shutil.copyfile(apk,destination)
manifest={"versionCode":code,"versionName":version,"apkUrl":url+"/"+name,"size":apk.stat().st_size,
          "sha256":hashlib.sha256(apk.read_bytes()).hexdigest(),"notes":args.notes}
(output/"latest.json").write_text(json.dumps(manifest,ensure_ascii=False,indent=2)+"\n",encoding="utf-8")
print("Prepared",name,"and latest.json in",output)
