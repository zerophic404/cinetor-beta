#!/usr/bin/env python3
"""Pillow image renderers for the Cinetor mock catalog.

Used as a library by gen_mock.py (which decides ids/titles/palettes and writes
the computed dominant/accent colors into the JSON). Every render function writes
one WebP and returns nothing; `colors_of(path)` extracts dominant + accent from
the saved file so JSON colors always match the pixels.

Images are rendered one at a time (low memory). Deterministic given the rng.
Run standalone (`python3 gen_images.py --demo`) to render a few samples.
"""
import colorsys
import math
import os
import random

from PIL import Image, ImageChops, ImageDraw, ImageFilter, ImageFont

FONT_DIR = "/usr/share/fonts/TTF"
WEBP_Q = 70
_font_cache = {}


def font(weight, size):
    key = (weight, size)
    if key not in _font_cache:
        p = os.path.join(FONT_DIR, f"Montserrat-{weight}.ttf")
        try:
            _font_cache[key] = ImageFont.truetype(p, size)
        except OSError:
            _font_cache[key] = ImageFont.load_default()
    return _font_cache[key]


# ---------------------------------------------------------------- colors
def hsv(h, s, v):
    r, g, b = colorsys.hsv_to_rgb(h % 1.0, max(0, min(1, s)), max(0, min(1, v)))
    return (int(r * 255), int(g * 255), int(b * 255))


def hex_of(rgb):
    return "#{:02X}{:02X}{:02X}".format(*rgb[:3])


def make_palette(rng, hue=None, red_bias=0.45, sat=1.0):
    """Palette dict. Lean red/black, but varied hues so dynamic tint works."""
    if hue is None:
        if rng.random() < red_bias:
            hue = rng.uniform(-0.03, 0.025)  # crimson..scarlet
        else:
            hue = rng.random()
    off = rng.choice([0.07, -0.07, 0.5, 0.12, -0.12])
    return {
        "hue": hue % 1.0,
        "deep": hsv(hue, 0.85 * sat, 0.10),
        "mid": hsv(hue + 0.02, 0.85 * sat, 0.42),
        "bright": hsv(hue - 0.015, 0.78 * sat, 0.96),
        "alt": hsv(hue + off, 0.75 * sat, 0.90),
        "red": (255, 45, 59),
    }


def colors_of(path):
    """(dominant, accent) '#RRGGBB' from a rendered image file."""
    with Image.open(path) as im:
        small = im.convert("RGB").resize((40, 60), Image.BILINEAR)
    q = small.quantize(colors=8, method=Image.Quantize.MEDIANCUT)
    pal = q.getpalette()[: 8 * 3]
    counts = sorted(q.getcolors(), reverse=True)
    cols = [(n, tuple(pal[i * 3: i * 3 + 3])) for n, i in counts]

    def lum(c):
        return 0.2126 * c[0] + 0.7152 * c[1] + 0.0722 * c[2]

    def satv(c):
        h, s, v = colorsys.rgb_to_hsv(*(x / 255 for x in c))
        return s, v

    dominant = None
    for n, c in cols:
        s, v = satv(c)
        if 28 < lum(c) < 235 and s > 0.15:
            dominant = c
            break
    if dominant is None:
        dominant = cols[0][1]
    accent = max((c for _, c in cols), key=lambda c: satv(c)[0] * satv(c)[1] + 0.0001 * lum(c))
    # lift the accent to a vivid tone of the same hue so glows/tints pop on black
    ah, as_, av = colorsys.rgb_to_hsv(*(x / 255 for x in accent))
    if as_ > 0.12:
        accent = tuple(int(x * 255) for x in colorsys.hsv_to_rgb(ah, max(as_, 0.65), max(av, 0.88)))
    return hex_of(dominant), hex_of(accent)


# ---------------------------------------------------------------- building blocks
def gradient(w, h, c1, c2, angle=90):
    """Linear gradient from c1 to c2. angle 90 = top->bottom."""
    g = Image.linear_gradient("L")  # 256x256 top black -> bottom white
    if angle != 90:
        g = g.rotate(angle - 90, resample=Image.BICUBIC).crop((64, 64, 192, 192))
    g = g.resize((w, h), Image.BICUBIC)
    return Image.composite(Image.new("RGB", (w, h), c2), Image.new("RGB", (w, h), c1), g)


def blobs(base, pal, rng, n=4, strength=0.85):
    w, h = base.size
    sw, sh = max(8, w // 4), max(8, h // 4)
    layer = Image.new("RGB", (sw, sh), (0, 0, 0))
    d = ImageDraw.Draw(layer)
    choices = [pal["mid"], pal["bright"], pal["alt"], pal["red"], pal["mid"]]
    for _ in range(n):
        c = rng.choice(choices)
        r = rng.uniform(0.18, 0.45) * max(sw, sh)
        x, y = rng.uniform(-0.1, 1.1) * sw, rng.uniform(-0.1, 1.0) * sh
        dim = rng.uniform(0.6, 1.0)
        d.ellipse([x - r, y - r * rng.uniform(0.6, 1.2), x + r, y + r], fill=tuple(int(v * dim) for v in c))
    layer = layer.filter(ImageFilter.GaussianBlur(max(sw, sh) / 6)).resize((w, h), Image.BICUBIC)
    return Image.blend(base, ImageChops.screen(base, layer), strength)


def vignette(img, amount=0.75):
    w, h = img.size
    r = Image.radial_gradient("L").resize((w, h), Image.BICUBIC)  # center black -> edge white
    mask = r.point(lambda v: int(255 - min(255, v * amount * 1.15)))
    dark = Image.new("RGB", (w, h), (0, 0, 0))
    return Image.composite(img, dark, mask)


def grain(img, sigma=7):
    w, h = img.size
    n = Image.effect_noise((w, h), sigma).convert("RGB")
    return ImageChops.add(img, n, scale=1.0, offset=-128)


def glow_circle(img, cx, cy, r, color, glow=2.2):
    w, h = img.size
    ov = Image.new("RGB", (w, h), (0, 0, 0))
    d = ImageDraw.Draw(ov)
    gr = r * glow
    d.ellipse([cx - gr, cy - gr, cx + gr, cy + gr], fill=tuple(int(c * 0.45) for c in color))
    ov = ov.filter(ImageFilter.GaussianBlur(r * 0.9))
    d = ImageDraw.Draw(ov)
    d.ellipse([cx - r, cy - r, cx + r, cy + r], fill=color)
    ov = ov.filter(ImageFilter.GaussianBlur(max(1, r * 0.05)))
    return ImageChops.screen(img, ov)


def motif(img, pal, rng, kind):
    w, h = img.size
    horizon = h * rng.uniform(0.55, 0.75)
    if kind in ("orb", "orb_mountains"):
        img = glow_circle(img, rng.uniform(0.25, 0.75) * w, horizon - rng.uniform(0.1, 0.3) * h,
                          rng.uniform(0.08, 0.16) * max(w, h), pal["bright"])
    if kind == "rays":
        ov = Image.new("RGB", (w, h), (0, 0, 0))
        d = ImageDraw.Draw(ov)
        ox = rng.uniform(0.2, 0.8) * w
        for _ in range(rng.randint(4, 7)):
            a = rng.uniform(-0.9, 0.9)
            spread = rng.uniform(0.04, 0.1)
            L = h * 1.6
            p1 = (ox + math.sin(a - spread) * L, math.cos(a - spread) * L)
            p2 = (ox + math.sin(a + spread) * L, math.cos(a + spread) * L)
            d.polygon([(ox, -10), p1, p2], fill=tuple(int(c * 0.35) for c in pal["bright"]))
        ov = ov.filter(ImageFilter.GaussianBlur(max(w, h) / 40))
        img = ImageChops.screen(img, ov)
    d = ImageDraw.Draw(img)
    if kind in ("mountains", "orb_mountains"):
        for layer in range(3):
            base_y = horizon + layer * h * 0.08
            pts = [(0, h)]
            x = -20
            while x < w + 40:
                pts.append((x, base_y - rng.uniform(0, 0.18) * h * (1 - layer * 0.25)))
                x += rng.uniform(0.08, 0.2) * w
            pts.append((w + 40, h))
            v = 0.07 - layer * 0.025
            d.polygon(pts, fill=hsv(pal["hue"], 0.7, max(0.01, v)))
    elif kind == "skyline":
        x = -5
        while x < w:
            bw = rng.uniform(0.05, 0.12) * w
            bh = rng.uniform(0.15, 0.5) * h
            top = h - bh - (h - horizon) * 0.3
            d.rectangle([x, top, x + bw, h], fill=(6, 6, 8))
            for _ in range(int(bw * bh / 900)):
                wx = rng.uniform(x + 2, x + bw - 4)
                wy = rng.uniform(top + 4, h - 4)
                if rng.random() < 0.35:
                    d.rectangle([wx, wy, wx + 2, wy + 3], fill=rng.choice([pal["bright"], pal["alt"], (255, 220, 160)]))
            x += bw + rng.uniform(0, 6)
    elif kind == "figure":
        cx = rng.uniform(0.3, 0.7) * w
        s = h * rng.uniform(0.22, 0.32)
        gy = horizon + s * 0.4
        d.rectangle([0, gy, w, h], fill=hsv(pal["hue"], 0.6, 0.04))
        d.ellipse([cx - s * 0.11, gy - s * 1.05, cx + s * 0.11, gy - s * 0.8], fill=(4, 4, 5))
        d.polygon([(cx - s * 0.2, gy - s * 0.78), (cx + s * 0.2, gy - s * 0.78),
                   (cx + s * 0.28, gy), (cx - s * 0.28, gy)], fill=(4, 4, 5))
    elif kind == "rings":
        cx, cy = rng.uniform(0.3, 0.7) * w, rng.uniform(0.3, 0.6) * h
        ov = Image.new("RGB", (w, h), (0, 0, 0))
        od = ImageDraw.Draw(ov)
        for i in range(rng.randint(3, 6)):
            r = (i + 1) * max(w, h) * rng.uniform(0.05, 0.09)
            od.ellipse([cx - r, cy - r * 0.6, cx + r, cy + r * 0.6], outline=rng.choice([pal["bright"], pal["alt"], pal["red"]]),
                       width=max(2, w // 120))
        ov2 = ov.filter(ImageFilter.GaussianBlur(max(w, h) / 80))
        img = ImageChops.screen(ImageChops.screen(img, ov2), ov)
    return img


MOTIFS = ["orb", "mountains", "orb_mountains", "skyline", "figure", "rays", "rings"]


def scene(w, h, pal, rng, kind=None):
    img = gradient(w, h, pal["mid"], pal["deep"] if rng.random() < 0.6 else (4, 4, 6), angle=rng.choice([90, 90, 60, 120]))
    img = blobs(img, pal, rng, n=rng.randint(4, 6), strength=1.0)
    # horizon glow band
    band = Image.new("RGB", (w, h), (0, 0, 0))
    hy = h * rng.uniform(0.5, 0.7)
    ImageDraw.Draw(band).ellipse([-w * 0.2, hy - h * 0.12, w * 1.2, hy + h * 0.12], fill=tuple(int(c * 0.55) for c in pal["bright"]))
    img = ImageChops.screen(img, band.filter(ImageFilter.GaussianBlur(h / 10)))
    img = motif(img, pal, rng, kind or rng.choice(MOTIFS))
    img = vignette(img, rng.uniform(0.45, 0.65))
    return img


def bottom_scrim(img, frac=0.5, strength=235):
    w, h = img.size
    g = Image.linear_gradient("L").resize((w, int(h * frac)), Image.BICUBIC)
    g = g.point(lambda v: int(v * strength / 255))
    black = Image.new("RGB", g.size, (0, 0, 0))
    region = img.crop((0, h - g.size[1], w, h))
    img.paste(Image.composite(black, region, g), (0, h - g.size[1]))
    return img


def wrap(draw, text, fnt, maxw):
    words, lines, cur = text.split(), [], ""
    for wd in words:
        t = (cur + " " + wd).strip()
        if draw.textlength(t, font=fnt) <= maxw or not cur:
            cur = t
        else:
            lines.append(cur)
            cur = wd
    if cur:
        lines.append(cur)
    return lines


def fit_title(draw, text, maxw, maxlines, start, minsize, weight="Black"):
    size = start
    while size > minsize:
        f = font(weight, size)
        lines = wrap(draw, text, f, maxw)
        if len(lines) <= maxlines and all(draw.textlength(l, font=f) <= maxw for l in lines):
            return f, lines
        size -= 2
    f = font(weight, minsize)
    return f, wrap(draw, text, f, maxw)[:maxlines]


def spaced(draw, xy, text, fnt, fill, spacing=2, anchor_center=False):
    total = sum(draw.textlength(ch, font=fnt) + spacing for ch in text) - spacing
    x, y = xy
    if anchor_center:
        x -= total / 2
    for ch in text:
        draw.text((x, y), ch, font=fnt, fill=fill)
        x += draw.textlength(ch, font=fnt) + spacing
    return total


def save(img, path, q=WEBP_Q):
    os.makedirs(os.path.dirname(path), exist_ok=True)
    img.save(path, "WEBP", quality=q, method=4)


# ---------------------------------------------------------------- renderers
def render_poster(path, title, tagline, pal, rng, label=None):
    w, h = 300, 450
    img = scene(w, h, pal, rng)
    img = bottom_scrim(img, 0.55)
    img = grain(img)
    d = ImageDraw.Draw(img)
    f, lines = fit_title(d, title.upper(), w - 36, 3, 40, 16)
    lh = f.size * 1.08
    y = h - 46 - lh * len(lines)
    for ln in lines:
        tw = d.textlength(ln, font=f)
        d.text(((w - tw) / 2, y), ln, font=f, fill=(255, 255, 255))
        y += lh
    d.rectangle([w / 2 - 16, h - 38, w / 2 + 16, h - 36], fill=pal["red"])
    spaced(d, (w / 2, h - 30), tagline.upper()[:34], font("SemiBold", 9), (190, 190, 190), 2, True)
    if label:
        spaced(d, (w / 2, 16), label.upper(), font("Bold", 8), (230, 230, 230), 3, True)
    save(img, path)


def render_backdrop(path, pal, rng, w=960, h=540):
    img = scene(w, h, pal, rng)
    # left-to-right darkening so app overlays (title/buttons) stay readable
    g = Image.linear_gradient("L").rotate(90).resize((w, h)).point(lambda v: int(150 + v * 105 / 255))
    img = Image.composite(img, Image.new("RGB", (w, h), (0, 0, 0)), g)
    img = grain(img, 6)
    save(img, path, 68)


def render_still(path, pal, rng):
    img = scene(480, 270, pal, rng)
    img = grain(img, 6)
    save(img, path, 66)


def render_logo(path, text, pal, rng, size=256):
    s = 4  # supersample for smooth edges
    big = size * s
    tile = gradient(big, big, pal["bright"], pal["deep"], angle=rng.choice([60, 90, 120]))
    tile = blobs(tile, pal, rng, n=2, strength=0.5)
    mask = Image.new("L", (big, big), 0)
    ImageDraw.Draw(mask).rounded_rectangle([8 * s, 8 * s, big - 8 * s, big - 8 * s], radius=int(big * 0.21875), fill=255)
    d = ImageDraw.Draw(tile)
    f, lines = fit_title(d, text, big - 70 * s, 2, 120 * s, 28 * s)
    lh = f.size * 1.0
    y = (big - lh * len(lines)) / 2 - 6 * s
    for ln in lines:
        tw = d.textlength(ln, font=f)
        d.text(((big - tw) / 2, y), ln, font=f, fill=(255, 255, 255))
        y += lh
    d.ellipse([big - 52 * s, 30 * s, big - 34 * s, 48 * s], fill=(255, 45, 59))
    out = Image.new("RGBA", (big, big), (0, 0, 0, 0))
    out.paste(tile, (0, 0), mask)
    out = out.resize((size, size), Image.LANCZOS)
    os.makedirs(os.path.dirname(path), exist_ok=True)
    out.save(path, "WEBP", quality=80, method=4)


def render_avatar(path, initial, pal, rng):
    w = 256
    img = gradient(w, w, pal["bright"], pal["deep"], angle=120)
    img = blobs(img, pal, rng, n=3, strength=0.6)
    d = ImageDraw.Draw(img)
    # simple face glyph
    d.ellipse([78, 70, 178, 170], fill=(250, 250, 250))
    d.ellipse([104, 104, 116, 118], fill=(15, 15, 18))
    d.ellipse([140, 104, 152, 118], fill=(15, 15, 18))
    d.arc([104, 116, 152, 150], 20, 160, fill=(15, 15, 18), width=6)
    f = font("Black", 40)
    tw = d.textlength(initial, font=f)
    d.text(((w - tw) / 2, 186), initial, font=f, fill=(255, 255, 255))
    save(img, path)


def render_album(path, title, artist, pal, rng):
    w = 400
    img = scene(w, w, pal, rng, kind=rng.choice(["rings", "orb", "rays"]))
    img = grain(img, 6)
    d = ImageDraw.Draw(img)
    f, lines = fit_title(d, title.upper(), w - 60, 3, 52, 18)
    y = 34
    for ln in lines:
        d.text((30, y), ln, font=f, fill=(255, 255, 255))
        y += f.size * 1.05
    d.rectangle([30, w - 52, 62, w - 49], fill=pal["red"])
    spaced(d, (30, w - 40), artist.upper(), font("SemiBold", 14), (225, 225, 225), 3)
    save(img, path)


def render_sports(path, w, h, home, away, league, rng):
    c1, c2 = home["color"], away["color"]
    img = Image.new("RGB", (w, h), (0, 0, 0))
    left = gradient(w, h, tuple(int(c * 0.9) for c in c1), tuple(int(c * 0.15) for c in c1), angle=90)
    right = gradient(w, h, tuple(int(c * 0.9) for c in c2), tuple(int(c * 0.15) for c in c2), angle=90)
    mask = Image.new("L", (w, h), 0)
    ImageDraw.Draw(mask).polygon([(0, 0), (w * 0.58, 0), (w * 0.42, h), (0, h)], fill=255)
    img = Image.composite(left, right, mask)
    pal = make_palette(rng, hue=0.0)
    img = blobs(img, pal, rng, n=3, strength=0.4)
    img = vignette(img, 0.7)
    img = grain(img, 6)
    d = ImageDraw.Draw(img)
    big = font("Black", int(min(w, h) * 0.2))
    d.text((w * 0.25, h * 0.45), home["short"], font=big, fill=(255, 255, 255), anchor="mm")
    d.text((w * 0.75, h * 0.45), away["short"], font=big, fill=(255, 255, 255), anchor="mm")
    d.ellipse([w / 2 - h * 0.08, h * 0.45 - h * 0.08, w / 2 + h * 0.08, h * 0.45 + h * 0.08], fill=(229, 9, 20))
    d.text((w / 2, h * 0.45), "VS", font=font("Black", int(h * 0.07)), fill=(255, 255, 255), anchor="mm")
    spaced(d, (w / 2, h * 0.8), league.upper(), font("Bold", max(9, int(h * 0.035))), (235, 235, 235), 3, True)
    save(img, path)


def render_adult(path, w, h, rng):
    hue = rng.choice([0.98, 0.0, 0.93, 0.88, 0.02])
    pal = make_palette(rng, hue=hue, sat=0.8)
    pal["bright"] = hsv(hue, 0.7, 0.45)
    pal["alt"] = hsv(hue + 0.05, 0.6, 0.35)
    pal["red"] = (120, 10, 20)
    img = gradient(w, h, pal["deep"], (2, 2, 3), angle=rng.choice([60, 120]))
    img = blobs(img, pal, rng, n=4, strength=0.7)
    img = vignette(img, 0.85)
    img = grain(img, 6)
    d = ImageDraw.Draw(img)
    r = int(min(w, h) * 0.11)
    cx, cy = w - r - int(min(w, h) * 0.06), r + int(min(w, h) * 0.06)
    d.ellipse([cx - r, cy - r, cx + r, cy + r], fill=(229, 9, 20), outline=(255, 255, 255), width=max(2, r // 12))
    d.text((cx, cy), "18+", font=font("Black", int(r * 0.8)), fill=(255, 255, 255), anchor="mm")
    save(img, path)


# ---- comics
BUBBLE_LINES = ["WAIT!", "...", "NO WAY?!", "RUN!", "IT'S HIM.", "TOO LATE", "HMPH.", "WHAT?!", "LET'S GO", "I KNEW IT",
                "BEHIND YOU!", "HAH!", "NOT YET.", "TRUST ME", "WHO ARE YOU?"]
MANGA_LAYOUTS = [
    [(0, 0, 1, 0.33), (0, 0.33, 0.5, 0.66), (0.5, 0.33, 1, 0.66), (0, 0.66, 1, 1)],
    [(0, 0, 0.6, 0.45), (0.6, 0, 1, 0.45), (0, 0.45, 1, 1)],
    [(0, 0, 1, 0.25), (0, 0.25, 0.4, 0.75), (0.4, 0.25, 1, 0.5), (0.4, 0.5, 1, 0.75), (0, 0.75, 1, 1)],
    [(0, 0, 0.5, 0.5), (0.5, 0, 1, 0.5), (0, 0.5, 0.5, 1), (0.5, 0.5, 1, 1)],
]


def bubble(d, x, y, text, fnt, rng, ink=(0, 0, 0)):
    tw = d.textlength(text, font=fnt)
    pw, ph = tw / 2 + 22, fnt.size * 0.9 + 14
    tail = [(x - 8, y + ph - 6), (x + 10, y + ph - 4), (x + rng.uniform(-30, 30), y + ph + 26)]
    d.polygon(tail, fill=(255, 255, 255), outline=ink)
    d.ellipse([x - pw, y - ph, x + pw, y + ph], fill=(255, 255, 255), outline=ink, width=3)
    d.text((x, y), text, font=fnt, fill=ink, anchor="mm")


def speed_lines(d, box, rng, color=(0, 0, 0)):
    x0, y0, x1, y1 = box
    cx, cy = (x0 + x1) / 2, (y0 + y1) / 2
    for _ in range(40):
        a = rng.uniform(0, math.tau)
        r0 = rng.uniform(0.25, 0.4) * min(x1 - x0, y1 - y0)
        r1 = max(x1 - x0, y1 - y0)
        d.line([(cx + math.cos(a) * r0, cy + math.sin(a) * r0), (cx + math.cos(a) * r1, cy + math.sin(a) * r1)],
               fill=color, width=rng.choice([1, 2]))


def _panel_art(pw, ph, rng, color):
    pal = make_palette(rng)
    img = scene(pw, ph, pal, rng)
    if not color:
        img = img.convert("L").point(lambda v: 255 if v > 150 else (170 if v > 80 else (90 if v > 35 else 20))).convert("RGB")
    return img


def render_manga_page(path, idx, rng, w=720, h=1080):
    img = Image.new("RGB", (w, h), (244, 241, 234))
    d = ImageDraw.Draw(img)
    m, gap = 36, 14
    for (a, b, c, e) in rng.choice(MANGA_LAYOUTS):
        box = [m + a * (w - 2 * m) + gap / 2, m + b * (h - 2 * m) + gap / 2,
               m + c * (w - 2 * m) - gap / 2, m + e * (h - 2 * m) - gap / 2]
        box = [int(v) for v in box]
        pw, ph = box[2] - box[0], box[3] - box[1]
        if rng.random() < 0.25:
            sp = Image.new("RGB", (pw, ph), (244, 241, 234))
            speed_lines(ImageDraw.Draw(sp), (0, 0, pw, ph), rng)
            img.paste(sp, (box[0], box[1]))
        else:
            img.paste(_panel_art(pw, ph, rng, False), (box[0], box[1]))
        d = ImageDraw.Draw(img)
        d.rectangle(box, outline=(10, 10, 10), width=5)
        if rng.random() < 0.6 and pw > 180:
            bubble(d, box[0] + rng.uniform(0.3, 0.7) * pw, box[1] + 50, rng.choice(BUBBLE_LINES), font("ExtraBold", 22), rng)
    d.text((w / 2, h - 16), str(idx), font=font("SemiBold", 14), fill=(60, 60, 60), anchor="mm")
    save(img, path, 60)


def render_strip_page(path, idx, rng, w=720, h=2000):
    img = Image.new("RGB", (w, h), (250, 250, 250))
    y = 30
    while y < h - 200:
        ph = int(rng.uniform(380, 640))
        ph = min(ph, h - 30 - y)
        img.paste(_panel_art(w - 60, ph, rng, True), (30, y))
        d = ImageDraw.Draw(img)
        d.rectangle([30, y, w - 30, y + ph], outline=(20, 20, 20), width=3)
        if rng.random() < 0.8:
            bubble(d, rng.uniform(0.3, 0.7) * w, y + rng.uniform(70, ph - 90), rng.choice(BUBBLE_LINES), font("ExtraBold", 26), rng)
        y += ph + int(rng.uniform(60, 140))
    d = ImageDraw.Draw(img)
    d.text((w / 2, h - 20), str(idx), font=font("SemiBold", 16), fill=(90, 90, 90), anchor="mm")
    save(img, path, 58)


if __name__ == "__main__":
    import sys
    out = sys.argv[2] if len(sys.argv) > 2 else os.path.join(os.environ.get("TMPDIR", "/tmp"), "cinetor_demo")
    r = random.Random(1)
    for i in range(4):
        p = make_palette(r)
        render_poster(f"{out}/p{i}.webp", "The Crimson Hour of Varrow", "A night that never ends", p, r, "A Cinetor Original")
        render_backdrop(f"{out}/b{i}.webp", p, r)
        print(colors_of(f"{out}/p{i}.webp"))
    render_logo(f"{out}/logo.webp", "PULSE", make_palette(r), r)
    render_manga_page(f"{out}/manga.webp", 1, r)
    render_strip_page(f"{out}/strip.webp", 1, r)
    render_album(f"{out}/album.webp", "Velvet Static", "Nova Kairo", make_palette(r), r)
    render_sports(f"{out}/sport.webp", 960, 540, {"short": "VRF", "color": (200, 20, 30)}, {"short": "KSR", "color": (20, 60, 200)}, "Continental League", r)
    render_adult(f"{out}/adult.webp", 300, 450, r)
    render_avatar(f"{out}/av.webp", "A", make_palette(r), r)
    print("ok", out)
