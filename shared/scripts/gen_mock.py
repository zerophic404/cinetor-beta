#!/usr/bin/env python3
"""Generate the Cinetor mock catalog (7 JSON files + WebP images + VTT subs).

Deterministic (seeded). Images are rendered by gen_images.py one at a time; the
dominantColor/accentColor written into the JSON are extracted from the saved
image files, so they always match the pixels.

Usage: python3 shared/scripts/gen_mock.py [--force]   (--force re-renders images)
"""
import argparse
import calendar
import json
import os
import random
import sys

sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))
import gen_images as gi  # noqa: E402

SHARED = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))
MOCK = os.path.join(SHARED, "mock")
FORCE = False
EPG_ANCHOR = calendar.timegm((2026, 11, 14, 0, 0, 0))

# ------------------------------------------------------------------ streams
MUX = "https://test-streams.mux.dev/x36xhzz/x36xhzz.m3u8"
USP = "https://demo.unified-streaming.com/k8s/features/stable/video/tears-of-steel/tears-of-steel.ism/.m3u8"
DASH = "https://storage.googleapis.com/shaka-demo-assets/angel-one/dash.mpd"
GTV = "https://commondatastorage.googleapis.com/gtv-videos-bucket/sample/"
# NOTE: the gtv-videos-bucket MP4s (GTV) return HTTP 403 as of 2026-11 (bucket no
# longer public) — replaced with reachable public MP4s below.
MP4_BUNNY = "https://media.w3.org/2010/05/bunny/trailer.mp4"
MP4_SINTEL = "https://media.w3.org/2010/05/sintel/trailer.mp4"
MP4_OCEANS = "https://vjs.zencdn.net/v/oceans.mp4"
MP4_BBB720 = "https://test-videos.co.uk/vids/bigbuckbunny/mp4/h264/720/Big_Buck_Bunny_720_10s_1MB.mp4"
LIVE = "https://cph-p2p-msl.akamaized.net/hls/live/2000341/test/master.m3u8"
VIDEO_SOURCES = [
    ("HLS", MUX, "Auto · HLS"),
    ("MP4", MP4_BUNNY, "MP4"),
    ("HLS", USP, "Multi-audio · HLS"),
    ("MP4", MP4_OCEANS, "MP4"),
    ("DASH", DASH, "DASH"),
    ("MP4", MP4_SINTEL, "MP4"),
    ("MP4", MP4_BBB720, "720p · MP4"),
]
SUBS = [
    {"lang": "en", "label": "English", "url": "subs/sample-en.vtt", "format": "VTT"},
    {"lang": "bn", "label": "বাংলা (Bangla)", "url": "subs/sample-bn.vtt", "format": "VTT"},
]


def video_sources(i):
    k, u, l = VIDEO_SOURCES[i % len(VIDEO_SOURCES)]
    k2, u2, _ = VIDEO_SOURCES[(i + 3) % len(VIDEO_SOURCES)]
    srcs = [{"url": u, "kind": k, "label": l}, {"url": u2, "kind": k2, "label": "Server 2"}]
    subs = [dict(s) for s in SUBS] if k == "MP4" else []
    return srcs, subs


# ------------------------------------------------------------------ images
def _img(rel, render):
    path = os.path.join(MOCK, rel)
    if FORCE or not os.path.exists(path):
        render(path)
    return rel


def colors(rel):
    return gi.colors_of(os.path.join(MOCK, rel))


def poster_and_backdrop(item_id, title, tagline, label=None, hue=None):
    rng = random.Random(item_id + ":pal")
    pal = gi.make_palette(rng, hue=hue)
    p = _img(f"images/posters/{item_id}.webp",
             lambda path: gi.render_poster(path, title, tagline, pal, random.Random(item_id + ":p"), label))
    b = _img(f"images/backdrops/{item_id}.webp",
             lambda path: gi.render_backdrop(path, pal, random.Random(item_id + ":b")))
    dom, acc = colors(p)
    return {"poster": p, "backdrop": b, "dominantColor": dom, "accentColor": acc}


STILL_POOL = 72


def still(idx):
    idx = idx % STILL_POOL
    rel = f"images/stills/still_{idx + 1:03d}.webp"
    return _img(rel, lambda path: gi.render_still(path, gi.make_palette(random.Random(f"still{idx}")),
                                                  random.Random(f"still{idx}:r")))


# ------------------------------------------------------------------ names
USED_TITLES = set()


def unique(rng, fn):
    for _ in range(500):
        t = fn()
        if t not in USED_TITLES:
            USED_TITLES.add(t)
            return t
    raise RuntimeError("title space exhausted")


A = ["Crimson", "Hollow", "Velvet", "Ashen", "Scarlet", "Midnight", "Glass", "Iron", "Silent", "Burning", "Pale",
     "Gilded", "Restless", "Northern", "Feral", "Quiet", "Electric", "Sunken", "Copper", "Lantern-lit"]
N = ["Meridian", "Lantern", "Cipher", "Orchard", "Verdict", "Ledger", "Compass", "Requiem", "Covenant", "Harbor",
     "Protocol", "Tide", "Archive", "Signal", "Parallax", "Vesper", "Monolith", "Cartographer", "Sanctum", "Undertow"]
P = ["Varrow", "Kelstane", "Orlune", "Dunmere", "Halvik", "Ostravel", "Cindral", "Maelport", "Thornvale",
     "Esk Hollow", "Brannoch", "Quillon"]
PEOPLE = ["Mara Voss", "Idris Kael", "Juno Hartwell", "Teo Lindqvist", "Saanvi Rao", "Elio Marchetti", "Nadia Quill",
          "Rafe Okonkwo", "Lena Sorensen", "Arjun Mehta", "Yuki Amano", "Selin Kaya", "Owen Thatcher", "Priya Das",
          "Kian Rahimi", "Ines Duarte", "Hana Sato", "Malik Brandt", "Zara Ellison", "Tomas Revel"]
STUDIOS = ["Mythbrix Pictures", "Redline Studio", "Nightglass Films", "Ember Lane", "Black Tile Media", "Northlight Works"]


def synopsis(rng, title, setting):
    who = rng.choice(PEOPLE).split()[0]
    hooks = ["uncovers a secret that could unravel everything",
             "has one night to make an impossible choice",
             "is pulled into a conspiracy older than the city itself",
             "must team up with a sworn rival to survive",
             "finds a letter that rewrites their past",
             "chases a signal no one else can hear",
             "returns home to settle a debt that never died"]
    tails = ["Nothing is what it seems.", "Every choice leaves a mark.", "The clock is already running.",
             "Trust is the first casualty.", "Some doors should stay closed."]
    return f"In {setting}, {who} {rng.choice(hooks)}. {rng.choice(tails)}"


EP_TITLES = ["Pilot", "The First Door", "Static on the Line", "Old Debts", "Glass Houses", "Undertow", "Red Thread",
             "The Long Night", "Crossroads", "Paper Crowns", "Afterimage", "The Quiet Part", "Embers", "Homecoming",
             "Fault Lines", "The Last Signal"]


def seasons_for(rng, item_id, n_seasons, ep_range, ep_dur, base_idx, intro=True):
    out = []
    k = 0
    for s in range(1, n_seasons + 1):
        eps = []
        for e in range(1, rng.randint(*ep_range) + 1):
            srcs, subs = video_sources(base_idx + k)
            ep = {"id": f"{item_id}-s{s}e{e:02d}", "n": e,
                  "title": EP_TITLES[(e - 1 + s * 3) % len(EP_TITLES)] if e > 1 or s > 1 else EP_TITLES[0],
                  "durationSec": rng.randint(*ep_dur), "still": still(base_idx * 3 + k),
                  "sources": srcs, "subtitles": subs}
            if intro:
                st = rng.randint(20, 120)
                ep["introStartSec"], ep["introEndSec"] = st, st + rng.choice([60, 75, 85, 90])
            eps.append(ep)
            k += 1
        out.append({"n": s, "title": f"Season {s}", "episodes": eps})
    return out


# ------------------------------------------------------------------ rows helpers
def pick(rng, pool, n, prefer=()):
    out = [x for x in prefer if x in pool][:n]
    rest = [x for x in pool if x not in out]
    rng.shuffle(rest)
    return out + rest[: max(0, n - len(out))]


def genre_row(rng, items, genre, n=10):
    have = [it["id"] for it in items if genre in it["genres"]]
    return pick(rng, [it["id"] for it in items], n, prefer=have)


def category(cid, space, title, hero_ids, rows):
    return {"id": cid, "space": space, "title": title, "heroIds": hero_ids,
            "rows": [{"id": f"{cid}-r{i + 1}", "title": t, "style": s, "itemIds": ids} for i, (t, s, ids) in enumerate(rows)]}


def top_ids(items, n):
    return [it["id"] for it in sorted(items, key=lambda x: (-x["rating"], x["id"]))[:n]]


def base_item(iid, space, cat, typ, title, rng, genres, maturities, years=(2008, 2026)):
    return {"id": iid, "space": space, "categoryId": cat, "type": typ, "title": title,
            "year": rng.randint(*years), "rating": round(rng.uniform(6.1, 9.6), 1),
            "maturity": rng.choice(maturities), "genres": rng.sample(genres, rng.randint(2, 3))}


def video_cats(rng, space, cat_defs, items, extra_rows=None):
    """Generic categories for video spaces. cat_defs = [(cid, title, genre_rows[])]."""
    cats = []
    for cid, title, grows in cat_defs:
        mine = [it for it in items if it["categoryId"] == cid]
        mine_ids = [it["id"] for it in mine]
        all_ids = [it["id"] for it in items]
        rows = [
            ("Trending Now", "POSTER", pick(rng, all_ids, 12, prefer=mine_ids)),
            (f"Top 10 {title}", "TOP10", top_ids(mine if len(mine) >= 10 else items, 10)),
            ("New Releases", "LANDSCAPE", pick(rng, all_ids, 10, prefer=sorted(mine_ids, key=lambda i: -next(x["year"] for x in mine if x["id"] == i)))),
            ("Continue Watching", "CONTINUE", pick(rng, mine_ids, min(6, len(mine_ids)))),
        ]
        for gtitle, g in grows:
            rows.append((gtitle, "POSTER", genre_row(rng, mine if len(mine) >= 10 else items, g)))
        cats.append(category(cid, space, title, top_ids(mine, rng.randint(3, 5)), rows[:8]))
    return cats


# ------------------------------------------------------------------ HOME
HOME_GENRES = ["Action", "Thriller", "Sci-Fi", "Drama", "Comedy", "Horror", "Romance", "Adventure", "Mystery", "Crime", "Fantasy"]


def gen_home():
    rng = random.Random("home")
    items = []
    movie_pat = ["The {A} {N}", "{A} {N}", "The {N} of {P}", "{P} {N}", "{N} at {P}", "Last Light over {P}", "{A} Hours of {P}"]
    series_pat = ["{P}", "House of {P}", "The {N} Society", "{A} {N}", "{P} Nights", "The {P} Files"]

    def mk(pats):
        return lambda: rng.choice(pats).format(A=rng.choice(A), N=rng.choice(N), P=rng.choice(P))

    for i in range(40):
        iid = f"hm-mv-{i + 1:03d}"
        title = unique(rng, mk(movie_pat))
        it = base_item(iid, "HOME", "home-movies", "MOVIE", title, rng, HOME_GENRES, ["PG", "PG-13", "R", "PG-13"])
        it["synopsis"] = synopsis(rng, title, rng.choice(P))
        it.update(poster_and_backdrop(iid, title, it["genres"][0] + " · " + str(it["year"]),
                                      "A Cinetor Original" if i % 4 == 0 else None))
        it["durationSec"] = rng.randint(85, 150) * 60
        it["sources"], it["subtitles"] = video_sources(i)
        it["cast"] = rng.sample(PEOPLE, 3)
        it["studio"] = rng.choice(STUDIOS)
        items.append(it)
    for i in range(20):
        iid = f"hm-sr-{i + 1:03d}"
        title = unique(rng, mk(series_pat))
        it = base_item(iid, "HOME", "home-series", "SERIES", title, rng, HOME_GENRES, ["TV-PG", "TV-14", "TV-MA"])
        it["synopsis"] = synopsis(rng, title, rng.choice(P))
        it.update(poster_and_backdrop(iid, title, f"{it['genres'][0]} series", "Series" if i % 3 == 0 else None))
        it["seasons"] = seasons_for(rng, iid, rng.randint(1, 3), (4, 10), (1500, 3600), 100 + i * 31)
        first = it["seasons"][0]["episodes"][0]
        it["sources"], it["subtitles"] = first["sources"], first["subtitles"]
        it["introStartSec"], it["introEndSec"] = first["introStartSec"], first["introEndSec"]
        it["cast"] = rng.sample(PEOPLE, 3)
        it["studio"] = rng.choice(STUDIOS)
        items.append(it)
    cats = video_cats(rng, "HOME", [
        ("home-movies", "Movies", [("Edge-of-Seat Thrillers", "Thriller"), ("Sci-Fi Worlds", "Sci-Fi"), ("Action Packed", "Action")]),
        ("home-series", "Series", [("Crime & Mystery", "Mystery"), ("Binge-worthy Drama", "Drama")]),
    ], items)
    avatars = []
    for k in range(8):
        rel = f"images/avatars/avatar_{k + 1:02d}.webp"
        _img(rel, lambda path, k=k: gi.render_avatar(path, "ABCDEFGH"[k],
                                                     gi.make_palette(random.Random(f"av{k}"), hue=[0.0, 0.6, 0.08, 0.33, 0.78, 0.97, 0.5, 0.14][k]),
                                                     random.Random(f"av{k}r")))
        avatars.append(rel)
    return {"space": "HOME", "version": 1, "genres": HOME_GENRES, "assets": {"avatars": avatars}, "categories": cats, "items": items}


# ------------------------------------------------------------------ ANIME
JP = ["Kaizora", "Hoshiren", "Ryuuhane", "Sorabito", "Yumenagi", "Tenrai", "Akanezumi", "Mikazuchi", "Hinokage",
      "Shirasagi", "Kazemaru", "Tsukinami", "Rensei", "Hakuren", "Aoibara", "Seiryuu", "Amagumo", "Hotaruzaka",
      "Ginrei", "Yozakura-cho", "Benimaru", "Kurohane", "Tokinagi", "Mizuchiri"]
JP_EN = ["Wings of the Ember Tower", "Requiem for a Paper Moon", "The Ninth Blade", "Chronicle of the Last Summoner",
         "Reborn as a Lighthouse Mage", "The Girl Who Folded Stars", "Academy of Broken Crowns", "Hunters of the Hollow Sky",
         "A Thousand Lanterns Rising", "My Rival Is a Dragon", "Echoes of the Iron Shrine", "Summer of the Glass Fox",
         "Beyond the Fifth Gate", "The Cursed Courier", "Spirit Café After Dark", "Rebellion of the Clockwork Saint",
         "When the Sky Cracked Open", "Second Life in Another Kingdom", "Blades of the Crimson Tide", "The Quiet Exorcist"]
ANIME_GENRES = ["Action", "Shonen", "Isekai", "Romance", "Fantasy", "Slice of Life", "Mecha", "Supernatural", "Comedy", "Adventure"]


def gen_anime():
    rng = random.Random("anime")
    cats_def = [("an-trending", "Trending", "Action"), ("an-shonen", "Shonen", "Shonen"), ("an-isekai", "Isekai", "Isekai"),
                ("an-romance", "Romance", "Romance"), ("an-movies", "Movies", "Fantasy")]
    items = []
    for i in range(30):
        cid, ctitle, g = cats_def[i // 6]
        iid = f"an-{i + 1:03d}"
        movie = cid == "an-movies"
        title = unique(rng, lambda: (f"{rng.choice(JP)} the Movie: {rng.choice(JP_EN)}" if movie else f"{rng.choice(JP)}: {rng.choice(JP_EN)}"))
        it = base_item(iid, "ANIME", cid, "MOVIE" if movie else "SERIES", title, rng, ANIME_GENRES, ["TV-PG", "TV-14", "PG-13"])
        if g not in it["genres"]:
            it["genres"][0] = g
        it["altTitle"] = title.split(":")[0]
        it["synopsis"] = synopsis(rng, title, rng.choice(["a floating city", "the old capital", "a cursed academy", "another world"]))
        it.update(poster_and_backdrop(iid, title.split(":")[0], title.split(":")[-1].strip(), "Anime"))
        it["studio"] = rng.choice(["Studio Kumo", "Hikari Animation", "Redline Studio", "Tsubasa Works"])
        if movie:
            it["durationSec"] = rng.randint(90, 130) * 60
            it["sources"], it["subtitles"] = video_sources(i + 2)
        else:
            it["seasons"] = seasons_for(rng, iid, rng.randint(1, 3), (4, 10), (1380, 1500), 400 + i * 31)
            first = it["seasons"][0]["episodes"][0]
            it["sources"], it["subtitles"] = first["sources"], first["subtitles"]
            it["introStartSec"], it["introEndSec"] = first["introStartSec"], first["introEndSec"]
        items.append(it)
    gr = {"an-trending": [("Action Highlights", "Action")], "an-shonen": [("Big Battles", "Action")],
          "an-isekai": [("Fantasy Realms", "Fantasy")], "an-romance": [("Feel-good Slice of Life", "Slice of Life")],
          "an-movies": [("Supernatural Picks", "Supernatural")]}
    cats = video_cats(rng, "ANIME", [(c, t, gr[c]) for c, t, _ in cats_def], items)
    return {"space": "ANIME", "version": 1, "genres": ANIME_GENRES, "categories": cats, "items": items}


# ------------------------------------------------------------------ DRAMA
DRAMA_GENRES = ["Romance", "Melodrama", "Historical", "Thriller", "Comedy", "Medical", "Legal", "Fantasy", "Family", "Youth"]
K_PL = ["Hanbit", "Saebyeok", "Gaon", "Haneul", "Daon", "Yeoul", "Mirae", "Byeolbit", "Seorin", "Haeon"]
K_ROLE = ["CEO", "Chef", "Prosecutor", "Idol", "Doctor", "Heir", "Detective", "Pilot", "Florist", "Architect"]
K_PAT = ["Moonlight over {K} Street", "My Secret {R}", "The {R} Next Door", "Spring Rain in {K}", "Midnight Bakery of {K}",
         "When the Stars Fell on {K}", "{K} Love Station", "Dear {R}, Remember Me", "The {R}'s Second Chance"]
C_ADJ = ["Jade", "Vermilion", "Silver", "Azure", "Golden", "Ivory", "Ninth", "Exiled", "Hidden"]
C_OBJ = ["Lantern", "Lotus", "Phoenix", "Sword", "Pavilion", "Crane", "Moon", "Willow", "Hairpin"]
C_PL = ["Linyue", "Qingshan", "Baihe", "Yunluo", "Feixue", "Jinhai"]
C_PAT = ["Legend of the {A} {O}", "Song of the {A} {O}", "{A} Palace Chronicles", "The {A} Prince of {P}",
         "Love Beneath the {A} {O}", "The {O} of {P}"]
J_PL = ["Aozaki", "Kamisora", "Minatogawa", "Hoshimura", "Yukinoe", "Sakuradai", "Tsugane"]
J_JOB = ["Night Pharmacist", "Last Postman", "Quiet Chef", "Lighthouse Keeper", "Detective Clerk", "Piano Tuner"]
J_PAT = ["Last Train to {P}", "{P} Café Nocturne", "The {J} of {P}", "Summer Letters from {P}", "Tomorrow at {P} Station", "The {J}"]


def gen_drama():
    rng = random.Random("drama")
    items = []
    plan = [("dr-kdrama", "K-Drama", 15, "k"), ("dr-cdrama", "C-Drama", 10, "c"), ("dr-jdrama", "J-Drama", 10, "j")]
    idx = 0
    for cid, ctitle, n, kind in plan:
        for j in range(n):
            iid = f"dr-{kind}-{j + 1:03d}"
            if kind == "k":
                f = lambda: rng.choice(K_PAT).format(K=rng.choice(K_PL), R=rng.choice(K_ROLE))
                setting = "Seoul" if rng.random() < 0.5 else rng.choice(K_PL) + "-dong"
            elif kind == "c":
                f = lambda: rng.choice(C_PAT).format(A=rng.choice(C_ADJ), O=rng.choice(C_OBJ), P=rng.choice(C_PL))
                setting = "the imperial city of " + rng.choice(C_PL)
            else:
                f = lambda: rng.choice(J_PAT).format(P=rng.choice(J_PL), J=rng.choice(J_JOB))
                setting = "a quiet town called " + rng.choice(J_PL)
            title = unique(rng, f)
            movie = j % 5 == 4
            it = base_item(iid, "DRAMA", cid, "MOVIE" if movie else "SERIES", title, rng, DRAMA_GENRES, ["TV-PG", "TV-14", "TV-MA"])
            it["synopsis"] = synopsis(rng, title, setting)
            it.update(poster_and_backdrop(iid, title, ctitle, ctitle))
            if movie:
                it["durationSec"] = rng.randint(95, 135) * 60
                it["sources"], it["subtitles"] = video_sources(idx + 1)
            else:
                it["seasons"] = seasons_for(rng, iid, rng.randint(1, 2), (6, 10), (2700, 4200), 900 + idx * 23)
                first = it["seasons"][0]["episodes"][0]
                it["sources"], it["subtitles"] = first["sources"], first["subtitles"]
                it["introStartSec"], it["introEndSec"] = first["introStartSec"], first["introEndSec"]
            it["cast"] = rng.sample(PEOPLE, 3)
            items.append(it)
            idx += 1
    cats = video_cats(rng, "DRAMA", [
        ("dr-kdrama", "K-Drama", [("Swoon-worthy Romance", "Romance"), ("Twisty Thrillers", "Thriller")]),
        ("dr-cdrama", "C-Drama", [("Historical Epics", "Historical"), ("Fantasy Worlds", "Fantasy")]),
        ("dr-jdrama", "J-Drama", [("Heartfelt Family", "Family"), ("Youth Stories", "Youth")]),
    ], items)
    return {"space": "DRAMA", "version": 1, "genres": DRAMA_GENRES, "categories": cats, "items": items}


# ------------------------------------------------------------------ TV
CHANNELS = {
    "News": [("Cinetor News 24", "CN24"), ("Meridian World", "MWN"), ("Pulse Report", "PULSE"), ("Nordline News", "NLN"), ("Capital Wire", "CW")],
    "Entertainment": [("Velvet TV", "VTV"), ("Ember One", "E1"), ("Starlit", "STAR"), ("Halo Plus", "HALO+"), ("Orbit Prime", "ORBIT")],
    "Kids": [("Nimbus Kids", "NIMBUS"), ("Pip & Pals", "PIP"), ("Toon Harbor", "TOON"), ("Little Comet", "COMET"), ("Bramble", "BRAM")],
    "Movies": [("Reel Noir", "NOIR"), ("Crimson Cinema", "CRIM"), ("Matinee Max", "MAX"), ("Silver Screen 2", "SS2"), ("Midnight Reel", "MID")],
    "Music": [("Bassline", "BASS"), ("Loop TV", "LOOP"), ("Encore", "ENC"), ("Neon Beat", "BEAT"), ("Velour Live", "VLR")],
    "Sports": [("Arena 1", "A1"), ("Arena 2", "A2"), ("Pitchside", "PITCH"), ("Fastlane Motors", "FAST"), ("Court Vision", "COURT")],
}
GROUP_HUE = {"News": 0.6, "Entertainment": 0.0, "Kids": 0.12, "Movies": 0.98, "Music": 0.8, "Sports": 0.33}
PROGRAMS = {
    "News": ["Morning Brief", "World Desk", "Market Watch", "Headlines Hour", "Late Edition", "Weather Now", "Tech Roundup", "The Interview"],
    "Entertainment": ["Talk Tonight", "Kitchen Clash", "Home Rescue", "Comedy Hour", "Mystery Theater", "Quiz Royale", "Star Stories"],
    "Kids": ["Robo Pals", "Captain Pebble", "Forest Friends", "Space Kittens", "Doodle Lab", "Bedtime Tales"],
    "Movies": ["Feature Presentation", "Midnight Double Bill", "Classic Matinee", "Director's Cut", "Premiere Night"],
    "Music": ["Top 40 Countdown", "Live Sessions", "Throwback Mix", "Studio Unplugged", "Night Grooves"],
    "Sports": ["Match Day Live", "Highlights Reel", "Pre-Game Show", "Locker Room", "Motorsport Weekly", "Court Side"],
}
TEAMS = [("Varrow Falcons", "VRF"), ("Kelstane Rovers", "KSR"), ("Orlune Tide", "ORT"), ("Dunmere Wolves", "DMW"),
         ("Halvik Comets", "HVC"), ("Maelport Mariners", "MPM"), ("Thornvale Stags", "TVS"), ("Cindral Blaze", "CDB"),
         ("Brannoch Kings", "BRK"), ("Quillon Hawks", "QLH"), ("Ostravel United", "OSU"), ("Esk Hollow Owls", "EHO"),
         ("Maelport Sharks", "MPS"), ("Varrow Titans", "VRT"), ("Kelstane Lions", "KSL"), ("Halvik Storm", "HVS"),
         ("Orlune Royals", "ORR"), ("Dunmere Bears", "DMB"), ("Cindral Vipers", "CDV"), ("Thornvale Rangers", "TVR")]
LEAGUES = [("Continental Football League", "Football"), ("Pro Hoops Circuit", "Basketball"),
           ("Super Cricket Series", "Cricket"), ("Pacific Rugby Cup", "Rugby")]


def gen_tv():
    rng = random.Random("tv")
    items, movie_titles = [], [t for t in sorted(USED_TITLES)][:40]
    num = 101
    group_ids = {}
    for group, chans in CHANNELS.items():
        for name, call in chans:
            iid = f"tv-ch-{num}"
            pal = gi.make_palette(random.Random(iid), hue=GROUP_HUE[group] + rng.uniform(-0.04, 0.04))
            logo = _img(f"images/logos/{iid}.webp", lambda p: gi.render_logo(p, call, pal, random.Random(iid + ":l")))
            bd = _img(f"images/backdrops/{iid}.webp", lambda p: gi.render_backdrop(p, pal, random.Random(iid + ":b")))
            dom, acc = colors(bd)
            progs, t, k = [], -rng.choice([0, 15, 30]) * 60, 0
            while t < 12 * 3600:
                dur = rng.choice([30, 45, 60, 60, 90]) * 60
                ptitle = ("Movie: " + rng.choice(movie_titles)) if group == "Movies" and rng.random() < 0.7 else rng.choice(PROGRAMS[group])
                progs.append({"id": f"{iid}-p{k + 1:02d}", "title": ptitle, "genre": group, "startOffsetSec": t, "durationSec": dur,
                              "synopsis": f"{ptitle} on {name}."})
                t += dur
                k += 1
            it = {"id": iid, "space": "TV", "categoryId": "tv-live", "type": "CHANNEL", "title": name, "altTitle": call,
                  "year": 2026, "rating": round(rng.uniform(6.5, 9.2), 1), "maturity": "TV-Y" if group == "Kids" else rng.choice(["TV-PG", "TV-14"]),
                  "genres": [group], "synopsis": f"{name} — 24/7 {group.lower()} channel.", "poster": logo, "backdrop": bd, "logo": logo,
                  "dominantColor": dom, "accentColor": acc, "live": True, "channelNumber": num, "channelGroup": group,
                  "sources": [{"url": LIVE, "kind": "HLS", "label": "Live"}, {"url": MUX, "kind": "HLS", "label": "Backup"}],
                  "subtitles": [], "programs": progs}
            items.append(it)
            group_ids.setdefault(group, []).append(iid)
            num += 1
    statuses = ["LIVE"] * 3 + ["UPCOMING"] * 4 + ["FINISHED"] * 3
    trng = random.Random("teams")
    team_pool = TEAMS[:]
    trng.shuffle(team_pool)
    events = []
    for e in range(10):
        iid = f"tv-ev-{e + 1:03d}"
        league, sport = LEAGUES[e % len(LEAGUES)]
        (hn, hs), (an, as_) = team_pool[2 * e], team_pool[2 * e + 1]
        hc = gi.hsv(trng.random(), 0.85, 0.85)
        ac = gi.hsv((trng.random() + 0.5) % 1, 0.85, 0.85)
        status = statuses[e]
        home = {"name": hn, "short": hs, "color": gi.hex_of(hc)}
        away = {"name": an, "short": as_, "color": gi.hex_of(ac)}
        if status == "UPCOMING":
            home["score"] = away["score"] = None
            start = rng.choice([2, 4, 6, 9, 20]) * 3600
            srcs = [{"url": MUX, "kind": "HLS", "label": "Preview"}]
        else:
            hi = 120 if sport in ("Basketball", "Cricket") else 5
            home["score"], away["score"] = rng.randint(0, hi), rng.randint(0, hi)
            if sport in ("Basketball", "Cricket"):
                home["score"] += 60
                away["score"] += 60
            start = -rng.choice([1, 2]) * 1800 if status == "LIVE" else -rng.choice([5, 8, 26]) * 3600
            srcs = ([{"url": LIVE, "kind": "HLS", "label": "Live"}] if status == "LIVE"
                    else [{"url": MP4_SINTEL, "kind": "MP4", "label": "Full Replay"}])
        p = _img(f"images/posters/{iid}.webp", lambda path: gi.render_sports(path, 300, 450, {"short": hs, "color": hc}, {"short": as_, "color": ac}, league, random.Random(iid)))
        b = _img(f"images/backdrops/{iid}.webp", lambda path: gi.render_sports(path, 960, 540, {"short": hs, "color": hc}, {"short": as_, "color": ac}, league, random.Random(iid + "b")))
        dom, acc = colors(b)
        ev = {"id": iid, "space": "TV", "categoryId": "tv-sports", "type": "EVENT", "title": f"{hn} vs {an}", "year": 2026,
              "rating": round(rng.uniform(7.0, 9.5), 1), "maturity": "TV-G", "genres": [sport, league],
              "synopsis": f"{league}: {hn} take on {an}.", "poster": p, "backdrop": b, "dominantColor": dom, "accentColor": acc,
              "live": status == "LIVE", "league": league, "status": status, "startOffsetSec": start,
              "homeTeam": home, "awayTeam": away, "durationSec": 7200, "sources": srcs, "subtitles": []}
        events.append(ev)
    items += events
    ch = [it for it in items if it["type"] == "CHANNEL"]
    live_rows = [(f"{g}", "CHANNEL", ids) for g, ids in group_ids.items()]
    cats = [
        category("tv-live", "TV", "Live TV", [group_ids["Entertainment"][0], group_ids["Movies"][0], group_ids["Sports"][0], group_ids["News"][0]],
                 [("Popular Now", "CHANNEL", top_ids(ch, 10))] + live_rows[:7]),
        category("tv-sports", "TV", "Sports", [e["id"] for e in events if e["status"] == "LIVE"] + [events[3]["id"]],
                 [("Live Now", "LANDSCAPE", [e["id"] for e in events if e["status"] == "LIVE"]),
                  ("Upcoming", "LANDSCAPE", [e["id"] for e in events if e["status"] == "UPCOMING"]),
                  ("Results", "LANDSCAPE", [e["id"] for e in events if e["status"] == "FINISHED"]),
                  ("All Events", "POSTER", [e["id"] for e in events]),
                  ("Sports Channels", "CHANNEL", group_ids["Sports"])]),
    ]
    genres = list(CHANNELS.keys()) + [s for _, s in LEAGUES]
    return {"space": "TV", "version": 1, "genres": genres, "epgAnchor": EPG_ANCHOR, "categories": cats, "items": items}


# ------------------------------------------------------------------ MUSIC
ALBUMS = [("Velvet Static", "Nova Kairo"), ("Neon Monsoon", "The Low Hours"), ("Paper Satellites", "Mira Vale"),
          ("Afterglow Avenue", "Sundial Youth"), ("Red Shift Lullabies", "Kestrel & Wren"), ("Midnight Cartography", "Lumen Drive"),
          ("Glass Garden", "Arden Sol"), ("Low Orbit Love", "Velour Kids"), ("Ember Season", "Hollow Pines"), ("Static Bloom", "Rhea Montclair")]
MUSIC_GENRES = ["Synthwave", "Lo-fi", "Electronic", "Ambient", "Indie Rock", "Pop", "Jazz", "Cinematic"]
TRACK_A = ["Neon", "Slow", "Golden", "Paper", "Crimson", "Late", "Hollow", "Silver", "Electric", "Quiet", "Distant", "Velvet"]
TRACK_N = ["Rain", "Hearts", "Satellites", "Highway", "Lights", "Echoes", "Summer", "Signals", "Rooftops", "Waves", "Static", "Ghosts"]


def gen_music():
    rng = random.Random("music")
    items, albums, tracks = [], [], []
    tn = 0
    used = set()
    for a, (title, artist) in enumerate(ALBUMS):
        aid = f"mu-al-{a + 1:03d}"
        pal = gi.make_palette(random.Random(aid))
        cover = _img(f"images/albums/{aid}.webp", lambda p: gi.render_album(p, title, artist, pal, random.Random(aid + ":c")))
        bd = _img(f"images/backdrops/{aid}.webp", lambda p: gi.render_backdrop(p, pal, random.Random(aid + ":b")))
        dom, acc = colors(cover)
        genres = rng.sample(MUSIC_GENRES, 2)
        year = rng.randint(2015, 2026)
        tids = []
        for t in range(5):
            tid = f"mu-tr-{tn + 1:03d}"
            while True:
                tt = f"{rng.choice(TRACK_A)} {rng.choice(TRACK_N)}"
                if tt not in used:
                    used.add(tt)
                    break
            tracks.append({"id": tid, "space": "MUSIC", "categoryId": "mu-moods", "type": "TRACK", "title": tt, "year": year,
                           "rating": round(rng.uniform(6.5, 9.6), 1), "maturity": "All", "genres": genres,
                           "synopsis": f"Track {t + 1} from {title} by {artist}.", "poster": cover, "backdrop": bd,
                           "dominantColor": dom, "accentColor": acc, "durationSec": rng.randint(180, 420),
                           "artist": artist, "album": title, "albumId": aid, "trackNumber": t + 1,
                           "sources": [{"url": f"https://www.soundhelix.com/examples/mp3/SoundHelix-Song-{tn % 16 + 1}.mp3", "kind": "MP3"}]})
            tids.append(tid)
            tn += 1
        albums.append({"id": aid, "space": "MUSIC", "categoryId": "mu-discover", "type": "ALBUM", "title": title, "year": year,
                       "rating": round(rng.uniform(7.0, 9.6), 1), "maturity": "All", "genres": genres,
                       "synopsis": f"{title} — the {rng.choice(['debut', 'second', 'third', 'latest'])} album from {artist}.",
                       "poster": cover, "backdrop": bd, "dominantColor": dom, "accentColor": acc,
                       "artist": artist, "trackIds": tids, "durationSec": 0})
        albums[-1]["durationSec"] = sum(t["durationSec"] for t in tracks if t["albumId"] == aid)
    items = albums + tracks
    al_ids, tr_ids = [a["id"] for a in albums], [t["id"] for t in tracks]
    cats = [
        category("mu-discover", "MUSIC", "Discover", top_ids(albums, 4),
                 [("Featured Albums", "POSTER", al_ids), ("Top 10 Tracks", "TOP10", top_ids(tracks, 10)),
                  ("Artists", "CIRCLE", al_ids), ("Fresh Tracks", "LANDSCAPE", pick(rng, tr_ids, 12)),
                  ("Chill Electronic", "POSTER", genre_row(rng, tracks, "Lo-fi", 12))]),
        category("mu-moods", "MUSIC", "Moods", pick(rng, al_ids, 3),
                 [("Deep Focus", "POSTER", genre_row(rng, tracks, "Ambient", 10)), ("Workout Drive", "POSTER", genre_row(rng, tracks, "Synthwave", 10)),
                  ("Late Night", "LANDSCAPE", genre_row(rng, tracks, "Jazz", 10)), ("Feel Good", "POSTER", genre_row(rng, tracks, "Pop", 10)),
                  ("Cinematic Moments", "POSTER", genre_row(rng, tracks, "Cinematic", 10))]),
    ]
    return {"space": "MUSIC", "version": 1, "genres": MUSIC_GENRES, "categories": cats, "items": items}


# ------------------------------------------------------------------ COMICS
COMIC_GENRES = ["Action", "Fantasy", "Romance", "Martial Arts", "Comedy", "Drama", "Mystery", "Horror", "Slice of Life", "Sci-Fi"]
MANGA_EN = ["The Blade Beneath the Snow", "Ghost Lantern Detective", "Class Rep of the Underworld", "Ten Days in Kagerou Alley",
            "Iron Petal Brigade", "The Cat Who Ran a Bathhouse", "Last Bus to Yomi", "Rival Hearts Academy",
            "Hunter of Paper Demons", "Moon Rabbit Express", "Shrine Keeper's Apprentice", "Crimson Kite Squad"]
MANHWA_PAT = ["Return of the {R}-Rank {C}", "The {R}-Class {C}'s Second Life", "Solo Climber of {P} Tower",
              "I Became the {C} of {P}", "The {C} Who Refused to Die", "Regressor of {P} Academy"]
MH_R = ["S", "F", "SSS", "Ninth", "Zero"]
MH_C = ["Alchemist", "Courier", "Necromancer", "Swordsmith", "Healer", "Archer", "Butler", "Strategist"]
MANHUA_PAT = ["Supreme {T} of {P}", "Ten Thousand Years of {X}", "The {T} Who Swallowed the Heavens",
              "Peerless {T} of Mount {P}", "Rebirth of the {X} Sovereign"]
MU_T = ["Daoist", "Alchemist", "Swordsman", "Beast Tamer", "Sect Master", "Physician"]
MU_X = ["Cultivation", "Thunder", "Jade", "Flame", "Frost", "Lotus"]


def gen_comics():
    rng = random.Random("comics")
    for k in range(12):
        _img(f"images/comics/manga/page_{k + 1:02d}.webp", lambda p, k=k: gi.render_manga_page(p, k + 1, random.Random(f"mpage{k}")))
        _img(f"images/comics/strip/page_{k + 1:02d}.webp", lambda p, k=k: gi.render_strip_page(p, k + 1, random.Random(f"spage{k}")))
    items = []
    plan = [("co-manga", "Manga", "ma", "PAGED", "RTL", "manga"),
            ("co-manhwa", "Manhwa", "mw", "LONGSTRIP", "TTB", "strip"),
            ("co-manhua", "Manhua", "mh", "LONGSTRIP", "TTB", "strip")]
    for ci, (cid, ctitle, pre, fmt, rd, pool) in enumerate(plan):
        for j in range(10):
            iid = f"co-{pre}-{j + 1:03d}"
            if pre == "ma":
                f = lambda: f"{rng.choice(JP)}: {rng.choice(MANGA_EN)}"
            elif pre == "mw":
                f = lambda: rng.choice(MANHWA_PAT).format(R=rng.choice(MH_R), C=rng.choice(MH_C), P=rng.choice(K_PL))
            else:
                f = lambda: rng.choice(MANHUA_PAT).format(T=rng.choice(MU_T), X=rng.choice(MU_X), P=rng.choice(C_PL))
            title = unique(rng, f)
            it = base_item(iid, "COMICS", cid, "COMIC", title, rng, COMIC_GENRES, ["13+", "16+", "All"])
            it["synopsis"] = synopsis(rng, title, rng.choice(["a crumbling empire", "a tower of a hundred floors", "a sleepy seaside town", "the spirit realm"]))
            it.update(poster_and_backdrop(iid, title.split(":")[-1].strip() if pre == "ma" else title, ctitle, ctitle))
            it["format"], it["readingDirection"] = fmt, rd
            it["pages"] = [{"id": f"{iid}-ch{c + 1}", "n": c + 1, "title": f"Chapter {c + 1}",
                            "pages": [f"images/comics/{pool}/page_{(j * 5 + c * 4 + p) % 12 + 1:02d}.webp" for p in range(12)]}
                           for c in range(3)]
            it["artist"] = rng.choice(PEOPLE)
            items.append(it)
    cats = []
    for cid, ctitle, *_ in plan:
        mine = [it for it in items if it["categoryId"] == cid]
        ids = [it["id"] for it in mine]
        cats.append(category(cid, "COMICS", ctitle, top_ids(mine, rng.randint(3, 5)), [
            (f"Popular {ctitle}", "POSTER", pick(rng, ids, 10)),
            (f"Top 10 {ctitle}", "TOP10", top_ids(mine, 10)),
            ("Recently Updated", "LANDSCAPE", pick(rng, ids, 8)),
            ("Action & Adventure", "POSTER", genre_row(rng, mine, "Action", 8)),
            ("Romance Corner", "POSTER", genre_row(rng, mine, "Romance", 8)),
        ]))
    return {"space": "COMICS", "version": 1, "genres": COMIC_GENRES, "categories": cats, "items": items}


# ------------------------------------------------------------------ ADULT
ADULT_GENRES = ["Late Night", "Romance", "Mature Drama", "Art House"]


def gen_adult():
    rng = random.Random("adult")
    items = []
    for i in range(12):
        iid = f"ad-{i + 1:03d}"
        p = _img(f"images/adult/{iid}-poster.webp", lambda path: gi.render_adult(path, 300, 450, random.Random(iid)))
        b = _img(f"images/adult/{iid}-backdrop.webp", lambda path: gi.render_adult(path, 960, 540, random.Random(iid + "b")))
        dom, acc = colors(p)
        srcs, subs = video_sources(i)
        items.append({"id": iid, "space": "ADULT", "categoryId": "ad-featured", "type": "VIDEO",
                      "title": f"Late Night Feature {i + 1:02d}", "year": rng.randint(2018, 2026),
                      "rating": round(rng.uniform(5.5, 8.5), 1), "maturity": "18+", "genres": rng.sample(ADULT_GENRES, 2),
                      "synopsis": "Placeholder title for the gated 18+ space. Mock data only — no real adult content.",
                      "poster": p, "backdrop": b, "dominantColor": dom, "accentColor": acc,
                      "durationSec": rng.randint(20, 90) * 60, "sources": srcs, "subtitles": subs})
    ids = [it["id"] for it in items]
    cats = [category("ad-featured", "ADULT", "Featured", ids[:4], [
        ("Featured", "POSTER", pick(rng, ids, 10)), ("Top 10", "TOP10", top_ids(items, 10)),
        ("New Arrivals", "LANDSCAPE", pick(rng, ids, 8)), ("Late Night Picks", "POSTER", genre_row(rng, items, "Late Night", 8))])]
    return {"space": "ADULT", "version": 1, "genres": ADULT_GENRES, "categories": cats, "items": items}


# ------------------------------------------------------------------ subs
EN_LINES = ["Hello, how are you?", "We have to leave now.", "Is that really true?", "I knew it.", "Watch out!", "Let's go.",
            "Did you hear that?", "Stay close to me.", "It's almost midnight.", "This changes everything."]
BN_LINES = ["হ্যালো, তুমি কেমন আছো?", "আমাদের এখনই যেতে হবে।", "এটা কি সত্যি?", "আমি জানতাম।", "সাবধান!", "চলো যাই।",
            "তুমি কি ওটা শুনেছো?", "আমার কাছাকাছি থাকো।", "প্রায় মধ্যরাত।", "এটা সবকিছু বদলে দেয়।"]


def ts(s):
    return f"{s // 3600:02d}:{s % 3600 // 60:02d}:{s % 60:02d}.000"


def gen_subs():
    os.makedirs(os.path.join(MOCK, "subs"), exist_ok=True)
    for lang, lines in (("en", EN_LINES), ("bn", BN_LINES)):
        out = ["WEBVTT", ""]
        for k, t in enumerate(range(2, 900, 6)):
            out += [str(k + 1), f"{ts(t)} --> {ts(t + 4)}", lines[k % len(lines)], ""]
        with open(os.path.join(MOCK, "subs", f"sample-{lang}.vtt"), "w", encoding="utf-8") as f:
            f.write("\n".join(out))


# ------------------------------------------------------------------ main
def main():
    global FORCE
    ap = argparse.ArgumentParser()
    ap.add_argument("--force", action="store_true", help="re-render images even if they exist")
    FORCE = ap.parse_args().force
    gen_subs()
    files = [("home", gen_home), ("anime", gen_anime), ("drama", gen_drama), ("tv", gen_tv),
             ("music", gen_music), ("comics", gen_comics), ("adult", gen_adult)]
    for name, fn in files:
        data = fn()
        with open(os.path.join(MOCK, f"{name}.json"), "w", encoding="utf-8") as f:
            json.dump(data, f, ensure_ascii=False, indent=1)
        types = {}
        for it in data["items"]:
            types[it["type"]] = types.get(it["type"], 0) + 1
        print(f"{name}.json: {len(data['items'])} items {types}, {len(data['categories'])} categories", flush=True)


if __name__ == "__main__":
    main()
