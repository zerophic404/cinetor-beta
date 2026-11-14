#!/usr/bin/env python3
"""Refresh web/static/brand from shared/brand/out/web (local only; needs Pillow).
iOS startup images are re-encoded as JPEG to keep the repo and Pages artifact small."""
import os, shutil
from PIL import Image

ROOT = os.path.dirname(os.path.dirname(os.path.dirname(os.path.abspath(__file__))))
SRC = os.path.join(ROOT, "shared/brand/out/web")
DST = os.path.join(ROOT, "web/static/brand")
os.makedirs(os.path.join(DST, "splash"), exist_ok=True)
for f in ["favicon.svg", "favicon.ico", "icon-192.png", "icon-512.png", "maskable-512.png", "apple-touch-icon.png", "splash.svg"]:
    shutil.copy(os.path.join(SRC, f), os.path.join(DST, f))
Image.open(os.path.join(SRC, "og-image.png")).convert("RGB").save(os.path.join(DST, "og-image.jpg"), quality=86, optimize=True, progressive=True)
for f in sorted(os.listdir(os.path.join(SRC, "splash"))):
    Image.open(os.path.join(SRC, "splash", f)).convert("RGB").save(
        os.path.join(DST, "splash", f.replace(".png", ".jpg")), quality=82, optimize=True, progressive=True)
for f in ["favicon.svg", "favicon.ico"]:
    shutil.copy(os.path.join(SRC, f), os.path.join(ROOT, "web/static", f))
print("brand synced")
