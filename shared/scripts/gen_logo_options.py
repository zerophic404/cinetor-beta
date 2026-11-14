#!/usr/bin/env python3
"""Generate Cinetor logo option SVGs + PNG previews + a comparison sheet."""
import math
import os
import subprocess
import sys

from PIL import Image, ImageDraw, ImageFont

OUT = sys.argv[1] if len(sys.argv) > 1 else os.path.expanduser("~/Desktop/Cinetor-Logos")
os.makedirs(OUT, exist_ok=True)

TILE_DARK = """
  <linearGradient id="tile" x1="0" y1="0" x2="1" y2="1">
    <stop offset="0" stop-color="#22143F"/><stop offset="1" stop-color="#0A0A14"/>
  </linearGradient>
  <linearGradient id="sheen" x1="0" y1="0" x2="0" y2="1">
    <stop offset="0" stop-color="#FFFFFF" stop-opacity="0.22"/>
    <stop offset="0.5" stop-color="#FFFFFF" stop-opacity="0.03"/>
    <stop offset="1" stop-color="#FFFFFF" stop-opacity="0"/>
  </linearGradient>
  <linearGradient id="edge" x1="0" y1="0" x2="1" y2="1">
    <stop offset="0" stop-color="#FFFFFF" stop-opacity="0.55"/>
    <stop offset="0.5" stop-color="#FFFFFF" stop-opacity="0.08"/>
    <stop offset="1" stop-color="#FFFFFF" stop-opacity="0.25"/>
  </linearGradient>
  <linearGradient id="aurora" x1="0" y1="0" x2="1" y2="1">
    <stop offset="0" stop-color="#8B5CF6"/><stop offset="0.55" stop-color="#22D3EE"/><stop offset="1" stop-color="#F472B6"/>
  </linearGradient>
  <linearGradient id="white" x1="0" y1="0" x2="1" y2="1">
    <stop offset="0" stop-color="#FFFFFF"/><stop offset="1" stop-color="#DCE6FF"/>
  </linearGradient>
  <filter id="glow" x="-30%" y="-30%" width="160%" height="160%">
    <feGaussianBlur stdDeviation="14" result="b"/>
    <feMerge><feMergeNode in="b"/><feMergeNode in="SourceGraphic"/></feMerge>
  </filter>
"""


def tile(fill="url(#tile)", extra_defs=""):
    return extra_defs, f"""
  <rect x="16" y="16" width="480" height="480" rx="112" fill="{fill}"/>
  <rect x="16" y="16" width="480" height="240" rx="112" fill="url(#sheen)"/>
  <rect x="17.5" y="17.5" width="477" height="477" rx="110.5" fill="none" stroke="url(#edge)" stroke-width="3"/>"""


def pt(cx, cy, r, deg):
    a = math.radians(deg)
    return cx + r * math.cos(a), cy + r * math.sin(a)


def arc(cx, cy, r, gap_deg):
    """C-shaped arc open to the right with total gap gap_deg."""
    x1, y1 = pt(cx, cy, r, -gap_deg / 2)
    x2, y2 = pt(cx, cy, r, gap_deg / 2)
    return f"M {x1:.1f} {y1:.1f} A {r} {r} 0 1 0 {x2:.1f} {y2:.1f}"


def svg(defs, body):
    return f"""<svg xmlns="http://www.w3.org/2000/svg" viewBox="0 0 512 512" width="512" height="512">
<defs>{TILE_DARK}{defs}</defs>{body}
</svg>"""


V = {}

# 1 Aperture Play
d, t = tile()
V["1-aperture-play"] = ("Aperture Play", svg(d, t + f"""
  <path d="{arc(256,256,150,80)}" fill="none" stroke="url(#aurora)" stroke-width="56" stroke-linecap="round" filter="url(#glow)"/>
  <path d="M212 186 L212 326 Q212 340 224 333 L336 266 Q348 256 336 246 L224 179 Q212 172 212 186 Z" fill="url(#white)"/>"""))

# 2 Glass Prism
d, t = tile()
tri = "M0 -116 L0 116 Q0 132 14 124 L196 16 Q210 0 196 -16 L14 -124 Q0 -132 0 -116 Z"
V["2-glass-prism"] = ("Glass Prism", svg(d, t + f"""
  <g style="mix-blend-mode:screen">
    <path d="{tri}" transform="translate(150 240)" fill="#8B5CF6" opacity="0.85"/>
    <path d="{tri}" transform="translate(166 256)" fill="#22D3EE" opacity="0.8"/>
    <path d="{tri}" transform="translate(150 272)" fill="#F472B6" opacity="0.8"/>
  </g>
  <path d="{tri}" transform="translate(158 256) scale(0.62) translate(40 0)" fill="#FFFFFF" opacity="0.92"/>"""))

# 3 Film C
d, t = tile()
perfs = []
for deg in range(55, 310, 26):
    x, y = pt(256, 256, 140, deg)
    perfs.append(f'<rect x="{x-9:.1f}" y="{y-12:.1f}" width="18" height="24" rx="5" fill="#12102A" transform="rotate({deg} {x:.1f} {y:.1f})"/>')
V["3-film-reel-c"] = ("Film Reel C", svg(d, t + f"""
  <path d="{arc(256,256,140,96)}" fill="none" stroke="url(#aurora)" stroke-width="84" stroke-linecap="butt"/>
  {''.join(perfs)}
  <path d="M226 206 L226 306 L310 256 Z" fill="url(#white)" stroke="url(#white)" stroke-width="14" stroke-linejoin="round"/>"""))

# 4 Orbit
d, t = tile()
V["4-orbit"] = ("Orbit", svg(d + """
  <linearGradient id="orb" x1="0" y1="0" x2="1" y2="0">
    <stop offset="0" stop-color="#22D3EE"/><stop offset="1" stop-color="#E879F9"/>
  </linearGradient>
  <clipPath id="front"><rect x="0" y="256" width="512" height="256"/></clipPath>""", t + """
  <g transform="rotate(-24 256 256)"><ellipse cx="256" cy="256" rx="196" ry="66" fill="none" stroke="url(#orb)" stroke-width="16" opacity="0.55"/></g>
  <path d="M196 160 L196 352 Q196 372 214 362 L364 272 Q380 256 364 240 L214 150 Q196 140 196 160 Z" fill="url(#aurora)" filter="url(#glow)"/>
  <g transform="rotate(-24 256 256)"><ellipse cx="256" cy="256" rx="196" ry="66" fill="none" stroke="url(#orb)" stroke-width="16" clip-path="url(#front)"/></g>
  <circle cx="420" cy="150" r="10" fill="#FFFFFF"/>"""))

# 5 Lens C
d, t = tile()
V["5-lens-c"] = ("Lens C", svg(d + """
  <linearGradient id="sun" x1="0" y1="0" x2="1" y2="1">
    <stop offset="0" stop-color="#FBBF24"/><stop offset="0.5" stop-color="#F43F5E"/><stop offset="1" stop-color="#A855F7"/>
  </linearGradient>
  <radialGradient id="lens" cx="0.4" cy="0.35" r="0.7">
    <stop offset="0" stop-color="#FFFFFF"/><stop offset="0.45" stop-color="#A5F3FC"/><stop offset="1" stop-color="#0E7490"/>
  </radialGradient>""", t + f"""
  <path d="{arc(256,256,136,70)}" fill="none" stroke="url(#sun)" stroke-width="64" stroke-linecap="round"/>
  <circle cx="256" cy="256" r="70" fill="url(#lens)" filter="url(#glow)"/>
  <path d="M238 222 L238 290 L294 256 Z" fill="#0B1B2E" stroke="#0B1B2E" stroke-width="8" stroke-linejoin="round"/>"""))

# 6 Noir Red
d, t = tile(fill="#050505")
V["6-noir-red"] = ("Noir Red", svg(d + """
  <linearGradient id="red" x1="0" y1="0" x2="0" y2="1">
    <stop offset="0" stop-color="#FF2D3B"/><stop offset="1" stop-color="#B00610"/>
  </linearGradient>""", t + f"""
  <path d="{arc(256,256,146,84)}" fill="none" stroke="url(#red)" stroke-width="62" stroke-linecap="butt"/>
  <path d="M220 196 L220 316 L322 256 Z" fill="#FFFFFF" stroke="#FFFFFF" stroke-width="12" stroke-linejoin="round"/>"""))


def render(svg_path, png_path, w):
    subprocess.run(["rsvg-convert", "-w", str(w), "-h", str(w), svg_path, "-o", png_path], check=True)


FONT = "/usr/share/fonts/TTF/Montserrat-ExtraBold.ttf"
FONT_L = "/usr/share/fonts/TTF/Montserrat-Medium.ttf"
f_word = ImageFont.truetype(FONT, 64)
f_label = ImageFont.truetype(FONT, 30)
f_small = ImageFont.truetype(FONT_L, 18)


def spaced(draw, xy, text, font, fill, spacing):
    x, y = xy
    for ch in text:
        draw.text((x, y), ch, font=font, fill=fill)
        x += draw.textlength(ch, font=font) + spacing
    return x


CARD_W, CARD_H = 900, 470
cards = []
for key, (name, s) in V.items():
    sp = os.path.join(OUT, f"{key}.svg")
    open(sp, "w").write(s)
    pp = os.path.join(OUT, f"{key}.png")
    render(sp, pp, 512)
    icon = Image.open(pp).convert("RGBA")

    card = Image.new("RGBA", (CARD_W, CARD_H), (14, 14, 24, 255))
    dr = ImageDraw.Draw(card)
    dr.rounded_rectangle((0, 0, CARD_W - 1, CARD_H - 1), 28, outline=(255, 255, 255, 40), width=2)
    # big icon
    card.alpha_composite(icon.resize((280, 280), Image.LANCZOS), (30, 70))
    # lockup
    card.alpha_composite(icon.resize((96, 96), Image.LANCZOS), (340, 80))
    spaced(dr, (450, 92), "CINETOR", f_word, (245, 245, 250), 6)
    # small sizes on dark
    x = 340
    for sz in (72, 48, 32):
        card.alpha_composite(icon.resize((sz, sz), Image.LANCZOS), (x, 220 + (72 - sz)))
        x += sz + 24
    # light background chip + round (adaptive) mask
    light = Image.new("RGBA", (300, 120), (238, 242, 248, 255))
    ImageDraw.Draw(light).text((112, 42), "light bg", font=f_small, fill=(60, 70, 90))
    light.alpha_composite(icon.resize((96, 96), Image.LANCZOS), (10, 12))
    mask = Image.new("L", light.size, 0)
    ImageDraw.Draw(mask).rounded_rectangle((0, 0, 299, 119), 20, fill=255)
    card.paste(light, (560, 196), mask)
    circ = icon.resize((96, 96), Image.LANCZOS)
    cm = Image.new("L", (96, 96), 0)
    ImageDraw.Draw(cm).ellipse((0, 0, 95, 95), fill=255)
    card.paste(circ, (340, 330), cm)
    dr.text((450, 362), "round mask (Android)", font=f_small, fill=(170, 175, 200))
    dr.text((30, 22), f"{key.split('-')[0]}.  {name}", font=f_label, fill=(255, 255, 255))
    cards.append(card)

cols, pad = 2, 30
rows = math.ceil(len(cards) / cols)
sheet = Image.new("RGBA", (cols * CARD_W + (cols + 1) * pad, rows * CARD_H + (rows + 1) * pad + 70), (6, 6, 12, 255))
ImageDraw.Draw(sheet).text((pad, 22), "Cinetor — logo options (pick a number)", font=f_label, fill=(255, 255, 255))
for i, c in enumerate(cards):
    r, k = divmod(i, cols)
    sheet.alpha_composite(c, (pad + k * (CARD_W + pad), 70 + pad + r * (CARD_H + pad)))
sheet.convert("RGB").save(os.path.join(OUT, "00-ALL-LOGOS.png"), quality=95)
print("ok", OUT)
