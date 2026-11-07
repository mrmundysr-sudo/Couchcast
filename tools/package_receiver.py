#!/usr/bin/env python3
"""Zips /receiver into the Roku sideload package the iPhone app uploads (same layout the Android build makes) and
writes receiver-info.json (title + version) next to it. `--check` fails if the committed copies are stale."""
import json, os, sys, zipfile, io

ROOT = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))
SRC = os.path.join(ROOT, "receiver")
OUT = os.path.join(ROOT, "ios", "Couchcast", "Resources")

def manifest():
    kv = {}
    for line in open(os.path.join(SRC, "manifest"), encoding="utf-8"):
        line = line.strip()
        if line and not line.startswith("#") and "=" in line:
            k, v = line.split("=", 1); kv[k.strip()] = v.strip()
    return kv

def build_zip():
    files = sorted(os.path.relpath(os.path.join(d, f), SRC).replace(os.sep, "/") for d, _, fs in os.walk(SRC) for f in fs)
    buf = io.BytesIO()
    with zipfile.ZipFile(buf, "w", zipfile.ZIP_DEFLATED) as z:
        for rel in files:
            zi = zipfile.ZipInfo(rel, date_time=(1980, 1, 1, 0, 0, 0)); zi.compress_type = zipfile.ZIP_DEFLATED
            zi.external_attr = 0o644 << 16
            z.writestr(zi, open(os.path.join(SRC, rel), "rb").read())
    return buf.getvalue()

def info():
    m = manifest()
    return json.dumps({"title": m.get("title", ""), "version": [int(m.get(k, "0") or 0) for k in ("major_version", "minor_version", "build_version")]}) + "\n"

def contents(zbytes):
    with zipfile.ZipFile(io.BytesIO(zbytes)) as z:
        return {n: z.read(n) for n in z.namelist()}

if __name__ == "__main__":
    zp, ip = os.path.join(OUT, "couchcast-receiver.zip"), os.path.join(OUT, "receiver-info.json")
    if "--check" in sys.argv:
        ok = os.path.exists(zp) and os.path.exists(ip) and open(ip).read() == info() and contents(open(zp, "rb").read()) == contents(build_zip())
        print("receiver package is up to date" if ok else "STALE: run python3 tools/package_receiver.py"); sys.exit(0 if ok else 1)
    os.makedirs(OUT, exist_ok=True)
    open(zp, "wb").write(build_zip()); open(ip, "w").write(info()); print("wrote", zp, ip)
