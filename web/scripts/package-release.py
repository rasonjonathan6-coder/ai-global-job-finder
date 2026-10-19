#!/usr/bin/env python3
"""Package web/dist into a clean Netlify manual-deploy ZIP.

The archive holds the deployable build output at its root (index.html, hashed assets,
manifest, service worker, Workbox runtime, icons) plus a `_redirects` SPA fallback. Source
files, .envrc-local and credentials are never included because only dist/ is read.

Run `npm run build` first, then `npm run release:package`.
"""

from __future__ import annotations

import hashlib
import shutil
import sys
import zipfile
from pathlib import Path

ROOT = Path(__file__).resolve().parent.parent
DIST = ROOT / "dist"
RELEASE = ROOT / "release"
VERSION = "1.0.0"
ZIP = RELEASE / f"ai-global-job-finder-web-v{VERSION}.zip"

REQUIRED = [
    "index.html",
    "manifest.webmanifest",
    "sw.js",
    "registerSW.js",
    "favicon.svg",
    "icons/icon-192.png",
    "icons/icon-512.png",
    "icons/icon-maskable-512.png",
]
REQUIRED_GLOBS = ["assets/*.js", "assets/*.css", "workbox-*.js"]


def main() -> int:
    if not DIST.is_dir():
        print(f"error: {DIST} not found — run `npm run build` first", file=sys.stderr)
        return 1

    files = sorted(p for p in DIST.rglob("*") if p.is_file())
    rels = {p.relative_to(DIST).as_posix() for p in files}

    missing = [name for name in REQUIRED if name not in rels]
    for pattern in REQUIRED_GLOBS:
        if not any(Path(r).match(pattern) for r in rels):
            missing.append(pattern)
    if missing:
        print(f"error: dist is missing required files: {missing}", file=sys.stderr)
        return 1

    RELEASE.mkdir(exist_ok=True)
    fixed_time = (2026, 10, 13, 0, 0, 0)
    with zipfile.ZipFile(ZIP, "w", compression=zipfile.ZIP_DEFLATED, compresslevel=9) as z:
        for p in files:
            arc = p.relative_to(DIST).as_posix()
            info = zipfile.ZipInfo(arc, date_time=fixed_time)
            info.compress_type = zipfile.ZIP_DEFLATED
            info.external_attr = (0o644 & 0xFFFF) << 16
            z.writestr(info, p.read_bytes())
        info = zipfile.ZipInfo("_redirects", date_time=fixed_time)
        info.compress_type = zipfile.ZIP_DEFLATED
        info.external_attr = (0o644 & 0xFFFF) << 16
        z.writestr(info, "/*    /index.html   200\n")

    digest = hashlib.sha256(ZIP.read_bytes()).hexdigest()
    (RELEASE / f"{ZIP.name}.sha256").write_text(f"{digest}  {ZIP.name}\n")

    print(f"archive: {ZIP}")
    print(f"entries: {len(files) + 1}")
    print(f"size:    {ZIP.stat().st_size} bytes")
    print(f"sha256:  {digest}")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
