#!/usr/bin/env python3
"""Cinetor brand asset generator.

Reproducibly builds every icon / splash / marketing asset from the chosen
red+black logo geometry (see shared/brand-source-logo.svg, variant 6-noir-red).

  python3 shared/scripts/gen_icons.py           # generate everything
  python3 shared/scripts/gen_icons.py --check   # verify outputs (sizes, XML)

Deps: rsvg-convert (librsvg) + Pillow. Fonts: Montserrat (system TTF).
Outputs:  shared/brand/src/*.svg   and   shared/brand/out/<platform>/...
"""
from __future__ import annotations

import io
import json
import math
import os
import shutil
import subprocess
import sys
import xml.etree.ElementTree as ET
from functools import lru_cache

from PIL import Image, ImageChops, ImageDraw, ImageFilter, ImageFont

HERE = os.path.dirname(os.path.abspath(__file__))
SHARED = os.path.dirname(HERE)
BRAND = os.path.join(SHARED, "brand")
SRC = os.path.join(BRAND, "src")
OUT = os.path.join(BRAND, "out")
DESKTOP_COPY = os.path.expanduser("~/Desktop/Cinetor-Logos/BRAND-PREVIEW.png")

FONT_DIR = "/usr/share/fonts/TTF"
F_XBOLD = os.path.join(FONT_DIR, "Montserrat-ExtraBold.ttf")
F_SEMI = os.path.join(FONT_DIR, "Montserrat-SemiBold.ttf")
F_MED = os.path.join(FONT_DIR, "Montserrat-Medium.ttf")

# ---------------------------------------------------------------- brand ----
BLACK = "#050505"
RED = "#E50914"        # primary brand red (UI)
RED_HI = "#FF2D3B"     # gradient top / highlight
RED_LO = "#B00610"     # gradient bottom
RED_DEEP = "#3A0006"
WHITE = "#FFFFFF"
TAGLINE = "Movies · Series · Anime · Drama · Live TV"

# Logo geometry in the 512 box
CX = CY = 256.0
RING_R = 146.0
RING_W = 62.0
GAP = 41.9                       # half-gap (deg) of the C opening, facing right
RING_D = "M 364.5 158.3 A 146 146 0 1 0 364.5 353.7"
TRI_D = "M220 196 L220 316 L322 256 Z"
TRI_STROKE = 12
RING_LEN = RING_R * math.radians(360 - 2 * GAP)          # ≈ 704
RING_OUTER = RING_R + RING_W / 2                         # 177
MARK_BBOX = (CX - RING_OUTER, CY - RING_OUTER,          # x0,y0,x1,y1
             CX + RING_OUTER * math.cos(math.radians(GAP)), CY + RING_OUTER)
TRI_PIVOT = ((220 + 220 + 322) / 3.0, 256.0)

RED_GRAD = (f'<linearGradient id="red" x1="0" y1="0" x2="0" y2="1">'
            f'<stop offset="0" stop-color="{RED_HI}"/><stop offset="1" stop-color="{RED_LO}"/>'
            f'</linearGradient>')


def svg_doc(body: str, defs: str = "", vb="0 0 512 512", w=512, h=512, extra="") -> str:
    return (f'<svg xmlns="http://www.w3.org/2000/svg" viewBox="{vb}" width="{w}" height="{h}"{extra}>\n'
            f'<defs>{defs}</defs>\n{body}\n</svg>\n')


def mark_body(ring="url(#red)", tri=WHITE, ring_w=RING_W, tri_stroke=TRI_STROKE) -> str:
    return (f'<path d="{RING_D}" fill="none" stroke="{ring}" stroke-width="{ring_w}" stroke-linecap="butt"/>\n'
            f'<path d="{TRI_D}" fill="{tri}" stroke="{tri}" stroke-width="{tri_stroke}" stroke-linejoin="round"/>')


TILE_DEFS = (RED_GRAD +
             '<linearGradient id="sheen" x1="0" y1="0" x2="0" y2="1">'
             '<stop offset="0" stop-color="#FFFFFF" stop-opacity="0.22"/>'
             '<stop offset="0.5" stop-color="#FFFFFF" stop-opacity="0.03"/>'
             '<stop offset="1" stop-color="#FFFFFF" stop-opacity="0"/></linearGradient>'
             '<linearGradient id="edge" x1="0" y1="0" x2="1" y2="1">'
             '<stop offset="0" stop-color="#FFFFFF" stop-opacity="0.55"/>'
             '<stop offset="0.5" stop-color="#FFFFFF" stop-opacity="0.08"/>'
             '<stop offset="1" stop-color="#FFFFFF" stop-opacity="0.25"/></linearGradient>')


def logo_svg() -> str:
    body = (f'<rect x="16" y="16" width="480" height="480" rx="112" fill="{BLACK}"/>\n'
            '<rect x="16" y="16" width="480" height="240" rx="112" fill="url(#sheen)"/>\n'
            '<rect x="17.5" y="17.5" width="477" height="477" rx="110.5" fill="none" stroke="url(#edge)" stroke-width="3"/>\n'
            + mark_body())
    return svg_doc(body, TILE_DEFS)


def logo_small_svg() -> str:
    """Hinted variant for 16–32 px: full-bleed tile, no sheen/edge, heavier strokes."""
    body = (f'<rect x="0" y="0" width="512" height="512" rx="104" fill="{BLACK}"/>\n'
            f'<g transform="translate(256 256) scale(1.12) translate(-256 -256)">'
            f'{mark_body(ring_w=74, tri_stroke=22)}</g>')
    return svg_doc(body, RED_GRAD)


def logo_round_svg() -> str:
    body = (f'<circle cx="256" cy="256" r="240" fill="{BLACK}"/>\n'
            '<clipPath id="c"><circle cx="256" cy="256" r="240"/></clipPath>'
            '<rect x="16" y="16" width="480" height="240" fill="url(#sheen)" clip-path="url(#c)"/>\n'
            '<circle cx="256" cy="256" r="238.5" fill="none" stroke="url(#edge)" stroke-width="3"/>\n'
            f'<g transform="translate(256 256) scale(0.88) translate(-256 -256)">{mark_body()}</g>')
    return svg_doc(body, TILE_DEFS)


def mark_svg() -> str:
    return svg_doc(mark_body(), RED_GRAD)


def mark_mono_svg(color=WHITE) -> str:
    return svg_doc(mark_body(ring=color, tri=color))


def maskable_svg() -> str:
    # full-bleed black; mark well inside the 80% (r=204.8) safe circle
    body = (f'<rect width="512" height="512" fill="#000000"/>\n'
            f'<g transform="translate(256 256) scale(0.86) translate(-256 -256)">{mark_body()}</g>')
    return svg_doc(body, RED_GRAD)


def adaptive_fg_preview_svg() -> str:
    """SVG twin of the Android foreground vector (108 viewport) for previews."""
    s, tx, ty = fg_transform()
    body = (f'<rect width="108" height="108" fill="{BLACK}"/>'
            f'<g transform="translate({tx:.4f} {ty:.4f}) scale({s:.5f})">{mark_body()}</g>')
    return svg_doc(body, RED_GRAD, vb="0 0 108 108", w=108, h=108)


def wordmark_svg(fill=WHITE) -> str:
    # NOTE: live text (no outline tooling available offline). Needs Montserrat
    # ExtraBold where rendered; PNG renders in out/wordmark/ are the canonical form.
    body = (f'<text x="0" y="100" font-family="Montserrat, \'Montserrat ExtraBold\', Arial, sans-serif" '
            f'font-weight="800" font-size="128" letter-spacing="28" fill="{fill}">CINETOR</text>')
    return svg_doc(body, vb="0 0 900 130", w=900, h=130)


# ---- splash -----------------------------------------------------------------
SPLASH_VB = (512, 640)


def splash_svg(static_t: float | None = None) -> str:
    """Animated splash (CSS keyframes, ≤1.2 s). If static_t is given, emit a
    non-animated snapshot of that moment (used for previews)."""
    word = ('<text id="word" x="256" y="566" text-anchor="middle" '
            'font-family="Montserrat, system-ui, -apple-system, Segoe UI, Roboto, sans-serif" '
            'font-weight="800" font-size="46" letter-spacing="11" fill="#FFFFFF">CINETOR</text>')
    defs = (RED_GRAD +
            '<filter id="glow" x="-40%" y="-40%" width="180%" height="180%">'
            '<feGaussianBlur stdDeviation="18"/></filter>')
    L = f"{RING_LEN:.1f}"
    if static_t is None:
        style = f"""<style>
  #ring{{stroke-dasharray:{L} {L};animation:draw 700ms cubic-bezier(.65,0,.35,1) both}}
  #glow{{opacity:.35;animation:pulse 1200ms ease-out both}}
  #play{{transform-box:fill-box;transform-origin:40% 50%;animation:pop 400ms cubic-bezier(.34,1.56,.64,1) 500ms both}}
  #word{{animation:fade 450ms ease-out 700ms both}}
  @keyframes draw{{from{{stroke-dashoffset:{L}}}to{{stroke-dashoffset:0}}}}
  @keyframes pulse{{0%,40%{{opacity:0}}70%{{opacity:.9}}100%{{opacity:.35}}}}
  @keyframes pop{{from{{transform:scale(0);opacity:0}}to{{transform:scale(1);opacity:1}}}}
  @keyframes fade{{from{{opacity:0;transform:translateY(10px)}}to{{opacity:1;transform:none}}}}
  @media (prefers-reduced-motion:reduce){{#ring,#glow,#play,#word{{animation:none}}}}
</style>"""
        ring_attr = glow_attr = play_attr = word_attr = ""
    else:
        t = static_t * 1000
        def ease(x):  # cubic in-out
            x = max(0.0, min(1.0, x)); return 4*x*x*x if x < .5 else 1 - (-2*x + 2)**3 / 2
        def back(x):
            x = max(0.0, min(1.0, x)); c1 = 1.70158; c3 = c1 + 1
            return 1 + c3*(x-1)**3 + c1*(x-1)**2
        p = ease(t / 700)
        g = 0 if t < 480 else (0.9 * (t-480)/360 if t < 840 else 0.9 - 0.55*(t-840)/360)
        g = max(0, min(0.9, g))
        sc = back((t-500)/400) if t > 500 else 0
        wo = max(0, min(1, (t-700)/450))
        style = ""
        ring_attr = f' stroke-dasharray="{L} {L}" stroke-dashoffset="{RING_LEN*(1-p):.1f}"'
        glow_attr = f' opacity="{g:.2f}"'
        px, py = TRI_PIVOT
        play_attr = (f' transform="translate({px} {py}) scale({max(sc,0.0001):.3f}) translate({-px} {-py})"'
                     f' opacity="{min(1, max(0, sc)):.2f}"')
        word_attr = f' opacity="{wo:.2f}" transform="translate(0 {10*(1-wo):.1f})"'
        word = word.replace('<text id="word"', f'<text id="word"{word_attr}')
    body = (f'{style}<rect width="512" height="640" fill="#000000"/>\n'
            f'<g transform="translate(96 80) scale(0.625)">\n'
            f'<path id="glow" d="{RING_D}" fill="none" stroke="{RED}" stroke-width="{RING_W+20}" filter="url(#glow)"{glow_attr}/>\n'
            f'<path id="ring" d="{RING_D}" fill="none" stroke="url(#red)" stroke-width="{RING_W}"{ring_attr}/>\n'
            f'<path id="play" d="{TRI_D}" fill="#FFFFFF" stroke="#FFFFFF" stroke-width="{TRI_STROKE}" stroke-linejoin="round"{play_attr}/>\n'
            f'</g>\n{word}')
    return svg_doc(body, defs, vb="0 0 512 640", w=512, h=640)


# ------------------------------------------------------- android vectors ----
NS_A = "http://schemas.android.com/apk/res/android"
NS_AAPT = "http://schemas.android.com/aapt"


def fg_transform(view=108.0, outer_r=27.0):
    """Scale so ring outer radius = outer_r dp (66dp safe zone => r<=33)."""
    s = outer_r / RING_OUTER
    return s, view / 2 - CX * s, view / 2 - CY * s


def _ring_path_xml(name="", gradient=True, color="#FFFFFFFF", indent="        ", trim_end=None):
    nm = f' android:name="{name}"' if name else ""
    te = f' android:trimPathEnd="{trim_end}"' if trim_end is not None else ""
    if gradient:
        bb0, bb1 = CY - RING_R, CY + RING_R
        return (f'{indent}<path{nm}\n{indent}    android:pathData="{RING_D}"\n'
                f'{indent}    android:fillColor="#00000000"\n'
                f'{indent}    android:strokeWidth="{RING_W:g}"\n{indent}    android:strokeLineCap="butt"{te}>\n'
                f'{indent}    <aapt:attr name="android:strokeColor">\n'
                f'{indent}        <gradient android:type="linear" android:startX="{CX:g}" android:startY="{bb0:g}"'
                f' android:endX="{CX:g}" android:endY="{bb1:g}">\n'
                f'{indent}            <item android:offset="0" android:color="#FFFF2D3B"/>\n'
                f'{indent}            <item android:offset="1" android:color="#FFB00610"/>\n'
                f'{indent}        </gradient>\n{indent}    </aapt:attr>\n{indent}</path>\n')
    return (f'{indent}<path{nm}\n{indent}    android:pathData="{RING_D}"\n'
            f'{indent}    android:fillColor="#00000000"\n{indent}    android:strokeColor="{color}"\n'
            f'{indent}    android:strokeWidth="{RING_W:g}"\n{indent}    android:strokeLineCap="butt"{te}/>\n')


def _tri_path_xml(color="#FFFFFFFF", indent="        ", name="", alpha=None):
    nm = f' android:name="{name}"' if name else ""
    al = f'\n{indent}    android:fillAlpha="{alpha}"\n{indent}    android:strokeAlpha="{alpha}"' if alpha is not None else ""
    return (f'{indent}<path{nm}\n{indent}    android:pathData="{TRI_D}"\n'
            f'{indent}    android:fillColor="{color}"\n{indent}    android:strokeColor="{color}"\n'
            f'{indent}    android:strokeWidth="{TRI_STROKE}"\n{indent}    android:strokeLineJoin="round"{al}/>\n')


def vector_xml(kind: str) -> str:
    if kind == "notification":
        view, size = 24.0, "24dp"
        x0, y0, x1, y1 = MARK_BBOX
        s = 22.0 / (y1 - y0)
        tx, ty = view / 2 - (x0 + x1) / 2 * s, view / 2 - CY * s
    else:
        view, size = 108.0, "108dp"
        s, tx, ty = fg_transform()
    head = (f'<?xml version="1.0" encoding="utf-8"?>\n'
            f'<!-- Generated by shared/scripts/gen_icons.py — do not edit by hand. -->\n'
            f'<vector xmlns:android="{NS_A}"' + (f'\n    xmlns:aapt="{NS_AAPT}"' if kind == "foreground" else "") +
            f'\n    android:width="{size}"\n    android:height="{size}"\n'
            f'    android:viewportWidth="{view:g}"\n    android:viewportHeight="{view:g}"'
            + ('\n    android:tint="#FFFFFFFF"' if kind == "notification" else "") + '>\n'
            f'    <group\n        android:translateX="{tx:.4f}"\n        android:translateY="{ty:.4f}"\n'
            f'        android:scaleX="{s:.6f}"\n        android:scaleY="{s:.6f}">\n')
    if kind == "foreground":
        body = _ring_path_xml(gradient=True) + _tri_path_xml()
    else:
        body = _ring_path_xml(gradient=False) + _tri_path_xml()
    return head + body + '    </group>\n</vector>\n'


def avd_splash_xml() -> str:
    s, tx, ty = fg_transform(outer_r=28.0)
    px, py = TRI_PIVOT
    return f'''<?xml version="1.0" encoding="utf-8"?>
<!-- Generated by shared/scripts/gen_icons.py — Android 12+ SplashScreen animated icon.
     Use: <item name="windowSplashScreenAnimatedIcon">@drawable/avd_splash</item>
          <item name="windowSplashScreenAnimationDuration">1000</item>
          <item name="windowSplashScreenBackground">#000000</item>
     Ring draws in (trimPathEnd 0->1, 0-700ms); play triangle pops (500-900ms). -->
<animated-vector xmlns:android="{NS_A}"
    xmlns:aapt="{NS_AAPT}">
    <aapt:attr name="android:drawable">
        <vector
            android:width="108dp"
            android:height="108dp"
            android:viewportWidth="108"
            android:viewportHeight="108">
            <group
                android:name="mark"
                android:translateX="{tx:.4f}"
                android:translateY="{ty:.4f}"
                android:scaleX="{s:.6f}"
                android:scaleY="{s:.6f}">
{_ring_path_xml(name="ring", gradient=True, indent="                ", trim_end=0)}                <group
                    android:name="play"
                    android:pivotX="{px:.2f}"
                    android:pivotY="{py:.2f}"
                    android:scaleX="0"
                    android:scaleY="0">
{_tri_path_xml(indent="                    ", name="tri", alpha=0)}                </group>
            </group>
        </vector>
    </aapt:attr>
    <target android:name="ring">
        <aapt:attr name="android:animation">
            <objectAnimator
                android:propertyName="trimPathEnd"
                android:valueFrom="0"
                android:valueTo="1"
                android:valueType="floatType"
                android:duration="700"
                android:interpolator="@android:interpolator/fast_out_slow_in"/>
        </aapt:attr>
    </target>
    <target android:name="play">
        <aapt:attr name="android:animation">
            <set android:ordering="together">
                <objectAnimator
                    android:propertyName="scaleX"
                    android:valueFrom="0"
                    android:valueTo="1"
                    android:valueType="floatType"
                    android:startOffset="500"
                    android:duration="400"
                    android:interpolator="@android:anim/overshoot_interpolator"/>
                <objectAnimator
                    android:propertyName="scaleY"
                    android:valueFrom="0"
                    android:valueTo="1"
                    android:valueType="floatType"
                    android:startOffset="500"
                    android:duration="400"
                    android:interpolator="@android:anim/overshoot_interpolator"/>
            </set>
        </aapt:attr>
    </target>
    <target android:name="tri">
        <aapt:attr name="android:animation">
            <set android:ordering="together">
                <objectAnimator
                    android:propertyName="fillAlpha"
                    android:valueFrom="0"
                    android:valueTo="1"
                    android:valueType="floatType"
                    android:startOffset="500"
                    android:duration="250"/>
                <objectAnimator
                    android:propertyName="strokeAlpha"
                    android:valueFrom="0"
                    android:valueTo="1"
                    android:valueType="floatType"
                    android:startOffset="500"
                    android:duration="250"/>
            </set>
        </aapt:attr>
    </target>
</animated-vector>
'''


ADAPTIVE_XML = f'''<?xml version="1.0" encoding="utf-8"?>
<!-- Generated by shared/scripts/gen_icons.py -->
<adaptive-icon xmlns:android="{NS_A}">
    <background android:drawable="@color/ic_launcher_background"/>
    <foreground android:drawable="@drawable/ic_launcher_foreground"/>
    <monochrome android:drawable="@drawable/ic_launcher_monochrome"/>
</adaptive-icon>
'''
BG_COLOR_XML = f'''<?xml version="1.0" encoding="utf-8"?>
<!-- Generated by shared/scripts/gen_icons.py -->
<resources>
    <color name="ic_launcher_background">{BLACK}</color>
</resources>
'''


# ------------------------------------------------------------ rendering ----
def render(svg: str, w: int, h: int | None = None) -> Image.Image:
    args = ["rsvg-convert", "-w", str(w)] + (["-h", str(h)] if h else []) + ["-f", "png"]
    png = subprocess.run(args, input=svg.encode(), capture_output=True, check=True).stdout
    return Image.open(io.BytesIO(png)).convert("RGBA")


@lru_cache(maxsize=64)
def logo_png(w: int) -> Image.Image:
    return render(logo_small_svg() if w <= 32 else logo_svg(), w)


@lru_cache(maxsize=32)
def mark_png(w: int) -> Image.Image:
    return render(mark_svg(), w)


def hexrgb(h):
    h = h.lstrip("#"); return tuple(int(h[i:i+2], 16) for i in (0, 2, 4))


def font(path, size):
    return ImageFont.truetype(path, size)


def wordmark(cap_h: int, color=WHITE, spacing_em=0.20, accent=False) -> Image.Image:
    """Montserrat ExtraBold 'CINETOR', tracked out; tightly cropped RGBA.
    cap_h = desired cap height in px. Rendered at 3x then downsampled."""
    ss = 3
    f = font(F_XBOLD, 10)
    cap_ratio = (f.getbbox("H")[3] - f.getbbox("H")[1]) / 10
    size = int(round(cap_h * ss / cap_ratio))
    f = font(F_XBOLD, size)
    sp = spacing_em * size
    text = "CINETOR"
    width = int(sum(f.getlength(c) for c in text) + sp * (len(text) - 1) + size)
    im = Image.new("L", (width, int(size * 1.6)), 0)
    d = ImageDraw.Draw(im)
    x = size * 0.2
    for i, c in enumerate(text):
        d.text((x, size * 0.15), c, font=f, fill=255)
        x += f.getlength(c) + (sp if i < len(text) - 1 else 0)
    bbox = im.getbbox(); im = im.crop(bbox)
    if accent:
        bar_h = max(ss, int(cap_h * ss * 0.09))
        gap = int(cap_h * ss * 0.28)
        canvas = Image.new("L", (im.width, im.height + gap + bar_h), 0)
        canvas.paste(im, (0, 0))
        acc = Image.new("L", canvas.size, 0)
        bw = int(im.width * 0.22)
        ImageDraw.Draw(acc).rectangle([(im.width - bw) // 2, im.height + gap,
                                       (im.width + bw) // 2, im.height + gap + bar_h], fill=255)
        tw, th = canvas.width // ss, canvas.height // ss
        a1 = canvas.resize((tw, th), Image.LANCZOS)
        a2 = acc.resize((tw, th), Image.LANCZOS)
        out = Image.new("RGBA", (tw, th), hexrgb(color) + (0,))
        out.putalpha(a1)
        red = Image.new("RGBA", (tw, th), hexrgb(RED) + (0,)); red.putalpha(a2)
        return Image.alpha_composite(out, red)
    tw, th = max(1, im.width // ss), max(1, im.height // ss)
    a = im.resize((tw, th), Image.LANCZOS)
    out = Image.new("RGBA", (tw, th), hexrgb(color) + (0,))
    out.putalpha(a)
    return out


def lockup(icon_h: int, color=WHITE) -> Image.Image:
    icon = logo_png(icon_h)
    wm = wordmark(int(icon_h * 0.30), color)
    gap = int(icon_h * 0.22)
    im = Image.new("RGBA", (icon_h + gap + wm.width, icon_h), (0, 0, 0, 0))
    im.alpha_composite(icon, (0, 0))
    im.alpha_composite(wm, (icon_h + gap, (icon_h - wm.height) // 2))
    return im


def radial_mask(w, h, cx, cy, rx, ry, power=1.6, strength=1.0) -> Image.Image:
    """L mask: 255*strength at (cx,cy) fading to 0 at ellipse radius."""
    g = Image.radial_gradient("L").point(
        lambda v: int(255 * strength * max(0.0, 1 - v / 255.0) ** power))
    g = g.resize((max(2, int(rx * 2)), max(2, int(ry * 2))), Image.BILINEAR)
    m = Image.new("L", (w, h), 0)
    m.paste(g, (int(cx - rx), int(cy - ry)))
    return m


def solid(w, h, color):
    return Image.new("RGB", (w, h), hexrgb(color) if isinstance(color, str) else color)


def cinematic_bg(w, h, focus=(0.5, 0.5), spread=1.0, grain=True) -> Image.Image:
    base = solid(w, h, "#000000")
    fx, fy = focus[0] * w, focus[1] * h
    m1 = radial_mask(w, h, fx, fy, w * 0.80 * spread, h * 1.25 * spread, power=1.25, strength=1.0)
    base = Image.composite(solid(w, h, "#6E000E"), base, m1)
    m2 = radial_mask(w, h, fx, fy, w * 0.42 * spread, h * 0.7 * spread, power=1.8, strength=0.6)
    base = Image.composite(solid(w, h, "#C2081A"), base, m2)
    # bottom vignette for depth (letterbox feel)
    vg = Image.linear_gradient("L").resize((w, h)).point(lambda v: int(max(0, v - 90) * 1.2))
    base = Image.composite(solid(w, h, "#000000"), base, vg)
    if grain:
        n = Image.effect_noise((w // 2, h // 2), 40).resize((w, h)).convert("RGB")
        base = ImageChops.add(base, ImageChops.subtract(n, solid(w, h, (128, 128, 128))), scale=9.0)
    return base


def add_glow(canvas: Image.Image, shape: Image.Image, pos, color=RED, radius=40, strength=1.0):
    """Red bloom behind a shape (uses its alpha)."""
    a = shape.split()[3]
    pad = radius * 3
    big = Image.new("L", (a.width + 2 * pad, a.height + 2 * pad), 0)
    big.paste(a, (pad, pad))
    small = big.resize((max(1, big.width // 4), max(1, big.height // 4)), Image.BILINEAR)
    small = small.filter(ImageFilter.GaussianBlur(radius / 4))
    blur = small.resize(big.size, Image.BILINEAR).point(lambda v: min(255, int(v * strength)))
    layer = Image.new("RGBA", big.size, hexrgb(color) + (0,)); layer.putalpha(blur)
    c = canvas.convert("RGBA")
    full = Image.new("RGBA", c.size, hexrgb(color) + (0,))
    full.paste(layer, (pos[0] - pad, pos[1] - pad))
    return Image.alpha_composite(c, full)


def paste_center(canvas, im, cx, cy):
    canvas.alpha_composite(im, (int(cx - im.width / 2), int(cy - im.height / 2)))


def tagline_img(size: int, color="#CFCFCF") -> Image.Image:
    f = font(F_MED, size)
    parts = TAGLINE.split(" · ")
    sep = "  •  "
    w = int(sum(f.getlength(p) for p in parts) + f.getlength(sep) * (len(parts) - 1) + 4)
    im = Image.new("RGBA", (w, int(size * 1.5)), (0, 0, 0, 0))
    d = ImageDraw.Draw(im)
    x = 0
    for i, p in enumerate(parts):
        d.text((x, 0), p, font=f, fill=color); x += f.getlength(p)
        if i < len(parts) - 1:
            d.text((x, 0), sep, font=f, fill=RED); x += f.getlength(sep)
    return im.crop(im.getbbox())


def hero(w, h, focus=(0.5, 0.45), tag=True, scale=1.0) -> Image.Image:
    bg = cinematic_bg(w, h, focus=focus).convert("RGBA")
    icon_h = int(h * 0.30 * scale)
    lk = lockup(icon_h)
    if lk.width > w * 0.8:
        lk = lk.resize((int(w * 0.8), int(lk.height * w * 0.8 / lk.width)), Image.LANCZOS)
    tg = tagline_img(max(12, int(h * 0.042 * scale))) if tag else None
    total = lk.height + (int(h * 0.09) + tg.height if tg else 0)
    y = (h - total) // 2
    x = (w - lk.width) // 2
    bg = add_glow(bg, lk.crop((0, 0, lk.height, lk.height)), (x, y), radius=int(icon_h * 0.25), strength=0.9)
    bg.alpha_composite(lk, (x, y))
    if tg:
        bg.alpha_composite(tg, ((w - tg.width) // 2, y + lk.height + int(h * 0.09)))
    return bg.convert("RGB")


# ------------------------------------------------------------- manifest ----
LEGACY = {"mdpi": 48, "hdpi": 72, "xhdpi": 96, "xxhdpi": 144, "xxxhdpi": 192}
IOS = [  # (w, h, device-width, device-height, dpr)
    (1290, 2796, 430, 932, 3), (1179, 2556, 393, 852, 3), (1284, 2778, 428, 926, 3),
    (1170, 2532, 390, 844, 3), (1125, 2436, 375, 812, 3), (1242, 2688, 414, 896, 3),
    (828, 1792, 414, 896, 2), (750, 1334, 375, 667, 2), (640, 1136, 320, 568, 2),
    (2048, 2732, 1024, 1366, 2), (1668, 2388, 834, 1194, 2), (1640, 2360, 820, 1180, 2),
    (1536, 2048, 768, 1024, 2),
]
ICO_DESKTOP = [16, 24, 32, 48, 64, 128, 256]
ICO_WEB = [16, 32, 48]


def expected():
    """path (relative to OUT/brand) -> spec. spec: (w,h) | 'xml' | 'svg' | ('ico',[sizes]) | 'icns' | 'json'"""
    e = {}
    for n in ["logo", "logo-small", "logo-round", "mark", "mark-mono", "maskable", "wordmark",
              "splash", "adaptive-foreground-preview"]:
        e[f"src/{n}.svg"] = "svg"
    a = "out/android/"
    for d, px in LEGACY.items():
        e[f"{a}mipmap-{d}/ic_launcher.png"] = (px, px)
        e[f"{a}mipmap-{d}/ic_launcher_round.png"] = (px, px)
    for x in ["mipmap-anydpi-v26/ic_launcher.xml", "mipmap-anydpi-v26/ic_launcher_round.xml",
              "drawable/ic_launcher_foreground.xml", "drawable/ic_launcher_monochrome.xml",
              "drawable/ic_stat_cinetor.xml", "drawable/avd_splash.xml",
              "values/ic_launcher_background.xml"]:
        e[a + x] = "xml"
    e[a + "drawable-xhdpi/tv_banner.png"] = (320, 180)
    w = "out/web/"
    e[w + "favicon.svg"] = "svg"
    e[w + "splash.svg"] = "svg"
    e[w + "favicon.ico"] = ("ico", ICO_WEB)
    e[w + "icon-192.png"] = (192, 192)
    e[w + "icon-512.png"] = (512, 512)
    e[w + "maskable-512.png"] = (512, 512)
    e[w + "apple-touch-icon.png"] = (180, 180)
    e[w + "og-image.png"] = (1200, 630)
    e[w + "ios-startup.json"] = "json"
    for iw, ih, *_ in IOS:
        e[f"{w}splash/apple-splash-{iw}x{ih}.png"] = (iw, ih)
    dk = "out/desktop/"
    e[dk + "icon.ico"] = ("ico", ICO_DESKTOP)
    e[dk + "icon.icns"] = "icns"
    e[dk + "32x32.png"] = (32, 32)
    e[dk + "128x128.png"] = (128, 128)
    e[dk + "128x128@2x.png"] = (256, 256)
    e[dk + "icon.png"] = (512, 512)
    m = "out/marketing/"
    e[m + "feature-graphic.png"] = (1024, 500)
    e[m + "github-social.png"] = (1280, 640)
    e[m + "play-store-icon-512.png"] = (512, 512)
    wm = "out/wordmark/"
    e[wm + "wordmark.png"] = "png"
    e[wm + "wordmark-accent.png"] = "png"
    e[wm + "wordmark-black.png"] = "png"
    e[wm + "lockup.png"] = "png"
    e[wm + "lockup-on-black.png"] = "png"
    e["out/PREVIEW.png"] = "png"
    return e


def P(rel):
    p = os.path.join(BRAND, rel)
    os.makedirs(os.path.dirname(p), exist_ok=True)
    return p


def write(rel, text):
    with open(P(rel), "w", encoding="utf-8") as fh:
        fh.write(text)


def save(rel, im: Image.Image, **kw):
    im.save(P(rel), optimize=True, **kw)


# -------------------------------------------------------------- builders ----
def build_src():
    write("src/logo.svg", logo_svg())
    write("src/logo-small.svg", logo_small_svg())
    write("src/logo-round.svg", logo_round_svg())
    write("src/mark.svg", mark_svg())
    write("src/mark-mono.svg", mark_mono_svg())
    write("src/maskable.svg", maskable_svg())
    write("src/wordmark.svg", wordmark_svg())
    write("src/splash.svg", splash_svg())
    write("src/adaptive-foreground-preview.svg", adaptive_fg_preview_svg())


def build_wordmarks():
    save("out/wordmark/wordmark.png", wordmark(160))
    save("out/wordmark/wordmark-accent.png", wordmark(160, accent=True))
    save("out/wordmark/wordmark-black.png", wordmark(160, color="#0A0A0A"))
    lk = lockup(400)
    save("out/wordmark/lockup.png", lk)
    pad = 120
    bl = Image.new("RGBA", (lk.width + 2 * pad, lk.height + 2 * pad), (0, 0, 0, 255))
    bl.alpha_composite(lk, (pad, pad))
    save("out/wordmark/lockup-on-black.png", bl.convert("RGB"))


def build_android():
    a = "out/android/"
    write(a + "drawable/ic_launcher_foreground.xml", vector_xml("foreground"))
    write(a + "drawable/ic_launcher_monochrome.xml", vector_xml("monochrome"))
    write(a + "drawable/ic_stat_cinetor.xml", vector_xml("notification"))
    write(a + "drawable/avd_splash.xml", avd_splash_xml())
    write(a + "mipmap-anydpi-v26/ic_launcher.xml", ADAPTIVE_XML)
    write(a + "mipmap-anydpi-v26/ic_launcher_round.xml", ADAPTIVE_XML)
    write(a + "values/ic_launcher_background.xml", BG_COLOR_XML)
    round_svg = logo_round_svg()
    for d, px in LEGACY.items():
        save(f"{a}mipmap-{d}/ic_launcher.png", logo_png(px))
        save(f"{a}mipmap-{d}/ic_launcher_round.png", render(round_svg, px))
    save(a + "drawable-xhdpi/tv_banner.png", tv_banner())
    # Play listing icon: full-bleed square (Play applies its own mask)
    full = (f'<rect width="512" height="512" fill="{BLACK}"/>'
            '<rect width="512" height="256" fill="url(#sheen)"/>'
            f'<g transform="translate(256 256) scale(0.9) translate(-256 -256)">{mark_body()}</g>')
    save("out/marketing/play-store-icon-512.png", render(svg_doc(full, TILE_DEFS), 512).convert("RGB"))


def tv_banner() -> Image.Image:
    S = 4
    w, h = 320 * S, 180 * S
    g = Image.linear_gradient("L").rotate(90).resize((w, h))  # left 255 -> right 0 after rotate
    g = g.transpose(Image.FLIP_LEFT_RIGHT).point(lambda v: int(v ** 1.4 / 255 ** 0.4))
    bg = Image.composite(solid(w, h, "#5C000A"), solid(w, h, "#000000"), g)
    m = radial_mask(w, h, w * 0.85, h * 0.5, w * 0.45, h * 0.9, power=2, strength=0.5)
    bg = Image.composite(solid(w, h, "#B00610"), bg, m).convert("RGBA")
    icon_h = int(h * 0.50)
    icon = logo_png(icon_h)
    wm = wordmark(int(icon_h * 0.27))
    gap = int(icon_h * 0.20)
    tw = icon_h + gap + wm.width
    x = (w - tw) // 2
    y = (h - icon_h) // 2
    bg.alpha_composite(icon, (x, y))
    bg.alpha_composite(wm, (x + icon_h + gap, y + (icon_h - wm.height) // 2))
    return bg.resize((320, 180), Image.LANCZOS).convert("RGB")


def build_web():
    w = "out/web/"
    write(w + "favicon.svg", logo_svg())
    write(w + "splash.svg", splash_svg())
    base = logo_png(256)
    frames = [logo_png(s) for s in ICO_WEB]
    frames[-1].save(P(w + "favicon.ico"), format="ICO", sizes=[(s, s) for s in ICO_WEB],
                    append_images=frames[:-1])
    save(w + "icon-192.png", logo_png(192))
    save(w + "icon-512.png", logo_png(512))
    save(w + "maskable-512.png", render(maskable_svg(), 512).convert("RGB"))
    # apple-touch: iOS rounds corners itself and shows transparency as black -> full bleed
    at = (f'<rect width="512" height="512" fill="{BLACK}"/><rect width="512" height="256" fill="url(#sheen)"/>'
          f'<g transform="translate(256 256) scale(0.84) translate(-256 -256)">{mark_body()}</g>')
    save(w + "apple-touch-icon.png", render(svg_doc(at, TILE_DEFS), 180).convert("RGB"))
    save(w + "og-image.png", hero(1200, 630, focus=(0.5, 0.42)))
    links, items = [], []
    for iw, ih, dw, dh, r in IOS:
        save(f"{w}splash/apple-splash-{iw}x{ih}.png", ios_startup(iw, ih))
        media = (f"(device-width: {dw}px) and (device-height: {dh}px) and "
                 f"(-webkit-device-pixel-ratio: {r}) and (orientation: portrait)")
        src = f"/splash/apple-splash-{iw}x{ih}.png"
        items.append({"src": src, "sizes": f"{iw}x{ih}", "media": media})
        links.append(f'<link rel="apple-touch-startup-image" href="{src}" media="{media}">')
    doc = {"_comment": "Generated by shared/scripts/gen_icons.py. Copy out/web/splash/* to the site's /splash/ "
                       "and inject `html` lines into <head>.",
           "startupImages": items, "html": links,
           "headExtras": ['<meta name="apple-mobile-web-app-capable" content="yes">',
                          '<meta name="apple-mobile-web-app-status-bar-style" content="black-translucent">',
                          '<link rel="apple-touch-icon" href="/apple-touch-icon.png">',
                          '<link rel="icon" href="/favicon.svg" type="image/svg+xml">',
                          '<link rel="icon" href="/favicon.ico" sizes="any">',
                          '<meta name="theme-color" content="#000000">']}
    write(w + "ios-startup.json", json.dumps(doc, indent=2, ensure_ascii=False) + "\n")


def ios_startup(w, h) -> Image.Image:
    bg = solid(w, h, "#000000")
    m = radial_mask(w, h, w / 2, h / 2, w * 0.75, w * 0.75, power=2.4, strength=0.55)
    bg = Image.composite(solid(w, h, "#5A0008"), bg, m)
    n = Image.effect_noise((w // 2, h // 2), 30).resize((w, h)).convert("RGB")   # dither vs banding
    bg = ImageChops.add(bg, ImageChops.subtract(n, solid(w, h, (128, 128, 128))), scale=14.0).convert("RGBA")
    icon = logo_png(int(w * 0.22))
    paste_center(bg, icon, w / 2, h / 2)
    return bg.convert("RGB")


def build_desktop():
    d = "out/desktop/"
    frames = [logo_png(s) for s in ICO_DESKTOP]
    frames[-1].save(P(d + "icon.ico"), format="ICO", sizes=[(s, s) for s in ICO_DESKTOP],
                    append_images=frames[:-1])
    icns_frames = [logo_png(s) for s in (16, 32, 64, 128, 256, 512)]
    logo_png(1024).save(P(d + "icon.icns"), format="ICNS", append_images=icns_frames)
    save(d + "32x32.png", logo_png(32))
    save(d + "128x128.png", logo_png(128))
    save(d + "128x128@2x.png", logo_png(256))
    save(d + "icon.png", logo_png(512))


def build_marketing():
    save("out/marketing/feature-graphic.png", hero(1024, 500, focus=(0.5, 0.42), scale=1.05))
    save("out/marketing/github-social.png", hero(1280, 640, focus=(0.5, 0.42)))


# --------------------------------------------------------------- preview ----
def rounded_mask(size, r):
    m = Image.new("L", (size, size), 0)
    ImageDraw.Draw(m).rounded_rectangle([0, 0, size - 1, size - 1], r, fill=255)
    return m


def masked(im, mask):
    o = im.convert("RGBA").copy(); o.putalpha(ImageChops.multiply(o.split()[3], mask)); return o


def phone_mock(H=760) -> Image.Image:
    W = int(H * 0.47)
    k = H / 760.0
    ph = Image.new("RGBA", (W, H), (0, 0, 0, 0))
    d = ImageDraw.Draw(ph)
    d.rounded_rectangle([0, 0, W - 1, H - 1], int(46 * k), fill="#1A1A1A")
    sw, sh = W - int(20 * k), H - int(20 * k)
    wall = Image.linear_gradient("L").resize((sw, sh))
    scr = Image.composite(solid(sw, sh, "#2A0A12"), solid(sw, sh, "#121826"), wall).convert("RGBA")
    sd = ImageDraw.Draw(scr)
    f_small = font(F_SEMI, int(13 * k))
    sd.text((int(24 * k), int(14 * k)), "9:41", font=f_small, fill="white")
    sd.ellipse([sw // 2 - 7 * k, 14 * k, sw // 2 + 7 * k, 28 * k], fill="#000")
    f_clock = font(F_MED, int(56 * k))
    sd.text((sw // 2, int(110 * k)), "21:30", font=f_clock, fill="white", anchor="mm")
    sd.text((sw // 2, int(150 * k)), "Sat, Nov 14", font=font(F_MED, int(14 * k)), fill="#DDDDDD", anchor="mm")
    cols, isz = 4, int(54 * k)
    gx = (sw - cols * isz) // (cols + 1)
    palette = ["#3B82F6", "#22C55E", "#F59E0B", "#A855F7", "#14B8A6", "#EF4444", "#64748B",
               "#EAB308", "#0EA5E9", "#F97316", "#10B981", "#8B5CF6"]
    names = ["Phone", "Maps", "Photos", "Music", "Chat", "Mail", "Settings", "Notes",
             "Weather", "Files", "Clock", "Store"]
    ic = render(logo_round_svg(), isz)
    f_lab = font(F_MED, int(11 * k))
    ai = 0
    for row in range(4):
        for c in range(cols):
            x = gx + c * (isz + gx); y = int(300 * k) + row * (isz + int(34 * k))
            if row == 1 and c == 1:
                scr.alpha_composite(ic, (x, y)); lab = "Cinetor"
            else:
                sd.ellipse([x, y, x + isz, y + isz], fill=palette[ai % len(palette)])
                sd.ellipse([x + isz * .32, y + isz * .32, x + isz * .68, y + isz * .68], fill=(255, 255, 255, 170))
                lab = names[ai % len(names)]; ai += 1
            sd.text((x + isz // 2, y + isz + int(12 * k)), lab, font=f_lab, fill="white", anchor="mm")
    # dock
    sd.rounded_rectangle([16 * k, sh - 100 * k, sw - 16 * k, sh - 24 * k], int(32 * k), fill=(255, 255, 255, 40))
    for c in range(cols):
        x = gx + c * (isz + gx); y = int(sh - 89 * k)
        if c == 3:
            scr.alpha_composite(render(logo_round_svg(), isz), (x, y))
        else:
            sd.ellipse([x, y, x + isz, y + isz], fill=palette[(c * 5 + 3) % len(palette)])
    scr = masked(scr, rounded_mask_rect(sw, sh, int(38 * k)))
    ph.alpha_composite(scr, (int(10 * k), int(10 * k)))
    return ph


def rounded_mask_rect(w, h, r):
    m = Image.new("L", (w, h), 0)
    ImageDraw.Draw(m).rounded_rectangle([0, 0, w - 1, h - 1], r, fill=255)
    return m


def build_preview():
    W, H = 2620, 1460
    sheet = Image.new("RGBA", (W, H), hexrgb("#0E0E10") + (255,))
    d = ImageDraw.Draw(sheet)
    f_t = font(F_XBOLD, 40); f_l = font(F_SEMI, 20)
    d.text((60, 44), "CINETOR — BRAND ASSETS", font=f_t, fill="white")
    d.text((60, 100), "Generated by shared/scripts/gen_icons.py  ·  red #E50914 / #FF2D3B → #B00610  ·  black #050505",
           font=f_l, fill="#9A9A9A")

    def label(x, y, t):
        d.text((x, y), t, font=f_l, fill="#BBBBBB")

    S = 220
    x0, y0 = 60, 170
    # 1 app icon
    sheet.alpha_composite(logo_png(S), (x0, y0)); label(x0, y0 + S + 12, "App icon (logo.svg)")
    # 2 adaptive: circle + squircle masks
    fg = render(adaptive_fg_preview_svg(), S)
    circ = Image.new("L", (S, S), 0); ImageDraw.Draw(circ).ellipse([0, 0, S - 1, S - 1], fill=255)
    x = x0 + S + 50
    sheet.alpha_composite(masked(fg, circ), (x, y0)); label(x, y0 + S + 12, "Adaptive · circle")
    x += S + 50
    sheet.alpha_composite(masked(fg, rounded_mask(S, int(S * 0.3))), (x, y0)); label(x, y0 + S + 12, "Adaptive · squircle")
    # 3 themed mono
    x += S + 50
    tile = Image.new("RGBA", (S, S), (0, 0, 0, 0))
    ImageDraw.Draw(tile).ellipse([0, 0, S - 1, S - 1], fill="#2C3A4F")
    s, tx, ty = fg_transform()
    mono = render(svg_doc(f'<g transform="translate({tx} {ty}) scale({s})">{mark_body(ring="#D3E3FD", tri="#D3E3FD")}</g>',
                          vb="0 0 108 108", w=108, h=108), S)
    tile.alpha_composite(mono); sheet.alpha_composite(tile, (x, y0)); label(x, y0 + S + 12, "Themed (monochrome)")
    # 4 notification + favicon sizes
    x += S + 50
    d.rounded_rectangle([x, y0, x + S, y0 + S], 24, fill="#1C1C1F")
    nt = render(svg_doc(mark_body(ring="#FFF", tri="#FFF")), 48)
    sheet.alpha_composite(nt, (x + 20, y0 + 20))
    for i, sz in enumerate([16, 32, 48]):
        sheet.alpha_composite(logo_png(sz), (x + 20 + i * 64, y0 + 130))
    label(x, y0 + S + 12, "Notif · favicon 16/32/48")
    # 5 maskable
    x += S + 50
    mk = Image.open(P("out/web/maskable-512.png")).convert("RGBA").resize((S, S), Image.LANCZOS)
    sheet.alpha_composite(masked(mk, circ), (x, y0)); label(x, y0 + S + 12, "PWA maskable")
    # row 2: TV banner, OG image
    y1 = y0 + S + 70
    tv = Image.open(P("out/android/drawable-xhdpi/tv_banner.png")).convert("RGBA").resize((640, 360), Image.LANCZOS)
    sheet.alpha_composite(tv, (x0, y1)); label(x0, y1 + 372, "Android TV banner 320×180 (2×)")
    og = Image.open(P("out/web/og-image.png")).convert("RGBA").resize((686, 360), Image.LANCZOS)
    sheet.alpha_composite(og, (x0 + 690, y1)); label(x0 + 690, y1 + 372, "OG image 1200×630")
    # splash frames
    fw, fh = 180, 225
    for i, t in enumerate([0.30, 0.65, 1.2]):
        fr = render(splash_svg(static_t=t), fw, fh)
        fx, fy = x0 + 1340 + i * (fw + 16), y1 + 430
        sheet.alpha_composite(fr, (fx, fy)); label(fx, fy + fh + 8, f"splash {int(t*1000)}ms")
    # row 3: lockup, feature graphic; phone in right column
    y2 = y1 + 430
    lk = Image.open(P("out/wordmark/lockup-on-black.png")).convert("RGBA")
    lk = lk.resize((620, int(lk.height * 620 / lk.width)), Image.LANCZOS)
    sheet.alpha_composite(lk, (x0, y2)); label(x0, y2 + lk.height + 10, "Lockup")
    fg2 = Image.open(P("out/marketing/feature-graphic.png")).convert("RGBA").resize((614, 300), Image.LANCZOS)
    sheet.alpha_composite(fg2, (x0 + 680, y2)); label(x0 + 680, y2 + 310, "Play feature graphic 1024×500")
    ph = phone_mock(H - y0 - 60)
    sheet.alpha_composite(ph, (W - ph.width - 60, y0))
    save("out/PREVIEW.png", sheet.convert("RGB"))
    os.makedirs(os.path.dirname(DESKTOP_COPY), exist_ok=True)
    shutil.copyfile(P("out/PREVIEW.png"), DESKTOP_COPY)


# ----------------------------------------------------------------- check ----
def check() -> int:
    errs, n = [], 0
    for rel, spec in expected().items():
        p = os.path.join(BRAND, rel); n += 1
        if not os.path.isfile(p) or os.path.getsize(p) == 0:
            errs.append(f"MISSING {rel}"); continue
        try:
            if spec in ("xml", "svg"):
                ET.parse(p)
            elif spec == "json":
                json.load(open(p))
            elif spec == "png":
                Image.open(p).verify()
            elif spec == "icns":
                with Image.open(p) as im:
                    if im.format != "ICNS": errs.append(f"BADFMT {rel}")
            elif isinstance(spec, tuple) and spec[0] == "ico":
                with Image.open(p) as im:
                    got = sorted(s[0] for s in im.ico.sizes())
                    if got != sorted(spec[1]): errs.append(f"ICO SIZES {rel}: {got} != {spec[1]}")
            else:
                with Image.open(p) as im:
                    if im.size != spec: errs.append(f"SIZE {rel}: {im.size} != {spec}")
        except Exception as ex:  # noqa
            errs.append(f"INVALID {rel}: {ex}")
    # android specific sanity: vectors reference aapt ns correctly, AVD targets exist
    avd = ET.parse(os.path.join(BRAND, "out/android/drawable/avd_splash.xml")).getroot() \
        if os.path.isfile(os.path.join(BRAND, "out/android/drawable/avd_splash.xml")) else None
    if avd is not None:
        names = {el.get(f"{{{NS_A}}}name") for el in avd.iter()}
        for t in avd.findall("target"):
            if t.get(f"{{{NS_A}}}name") not in names - {None} or \
               sum(1 for el in avd.iter() if el.get(f"{{{NS_A}}}name") == t.get(f"{{{NS_A}}}name")) < 2:
                errs.append(f"AVD target without node: {t.get(f'{{{NS_A}}}name')}")
    for e in errs:
        print("FAIL", e)
    print(f"check: {n - len(errs)}/{n} OK" if not errs else f"check: {len(errs)} problem(s) / {n} files")
    return 1 if errs else 0


def main():
    if "--check" in sys.argv:
        sys.exit(check())
    os.makedirs(SRC, exist_ok=True); os.makedirs(OUT, exist_ok=True)
    steps = [("src", build_src), ("wordmarks", build_wordmarks), ("android", build_android),
             ("web", build_web), ("desktop", build_desktop), ("marketing", build_marketing),
             ("preview", build_preview)]
    only = [a.split("=", 1)[1] for a in sys.argv if a.startswith("--only=")]
    for name, fn in steps:
        if only and name not in only[0].split(","):
            continue
        print(f"[gen] {name}…", flush=True); fn()
    print("[gen] done")


if __name__ == "__main__":
    main()
