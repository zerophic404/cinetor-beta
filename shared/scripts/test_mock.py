#!/usr/bin/env python3
"""Mock catalog + token tests. Run: python3 -m unittest shared/scripts/test_mock.py -v
(or from shared/scripts: python3 -m unittest test_mock)."""
import json
import os
import re
import unittest

SHARED = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))
MOCK = os.path.join(SHARED, "mock")
GEN = os.path.join(SHARED, "generated")
FILES = ["home", "anime", "drama", "tv", "music", "comics", "adult"]
SPACE_OF = {"home": "HOME", "anime": "ANIME", "drama": "DRAMA", "tv": "TV", "music": "MUSIC", "comics": "COMICS", "adult": "ADULT"}
VIDEO_TYPES = {"MOVIE", "SERIES", "CHANNEL", "EVENT", "VIDEO"}

try:
    import jsonschema
except ImportError:  # pragma: no cover
    jsonschema = None


def load(name):
    with open(os.path.join(MOCK, f"{name}.json"), encoding="utf-8") as f:
        return json.load(f)


# --- tiny draft-07 subset validator (fallback when jsonschema is missing)
def _resolve(root, ref):
    node = root
    for part in ref.lstrip("#/").split("/"):
        node = node[part]
    return node


def mini_validate(inst, schema, root, path="$"):
    errs = []
    if "$ref" in schema:
        return mini_validate(inst, _resolve(root, schema["$ref"]), root, path)
    t = schema.get("type")
    tmap = {"object": dict, "array": list, "string": str, "integer": int, "number": (int, float), "boolean": bool, "null": type(None)}
    if t:
        ts = t if isinstance(t, list) else [t]
        ok = any(isinstance(inst, tmap[x]) and not (x in ("integer", "number") and isinstance(inst, bool)) for x in ts)
        if not ok:
            return [f"{path}: expected {t}"]
    if "enum" in schema and inst not in schema["enum"]:
        errs.append(f"{path}: {inst!r} not in enum")
    if "const" in schema and inst != schema["const"]:
        errs.append(f"{path}: != const")
    if isinstance(inst, str):
        if "pattern" in schema and not re.search(schema["pattern"], inst):
            errs.append(f"{path}: {inst!r} !~ {schema['pattern']}")
        if len(inst) < schema.get("minLength", 0):
            errs.append(f"{path}: too short")
    if isinstance(inst, (int, float)) and not isinstance(inst, bool):
        if "minimum" in schema and inst < schema["minimum"]:
            errs.append(f"{path}: < minimum")
        if "maximum" in schema and inst > schema["maximum"]:
            errs.append(f"{path}: > maximum")
    if isinstance(inst, list):
        if len(inst) < schema.get("minItems", 0) or len(inst) > schema.get("maxItems", 10 ** 9):
            errs.append(f"{path}: bad item count {len(inst)}")
        if "items" in schema:
            for i, v in enumerate(inst):
                errs += mini_validate(v, schema["items"], root, f"{path}[{i}]")
    if isinstance(inst, dict):
        for r in schema.get("required", []):
            if r not in inst:
                errs.append(f"{path}: missing {r}")
        props = schema.get("properties", {})
        for k, v in inst.items():
            if k in props:
                errs += mini_validate(v, props[k], root, f"{path}.{k}")
            elif schema.get("additionalProperties") is False:
                errs.append(f"{path}: unexpected {k}")
            elif isinstance(schema.get("additionalProperties"), dict):
                errs += mini_validate(v, schema["additionalProperties"], root, f"{path}.{k}")
    for sub in schema.get("allOf", []):
        if "if" in sub:
            if not mini_validate(inst, sub["if"], root, path):
                errs += mini_validate(inst, sub.get("then", {}), root, path)
        else:
            errs += mini_validate(inst, sub, root, path)
    return errs


class MockCatalogTest(unittest.TestCase):
    @classmethod
    def setUpClass(cls):
        with open(os.path.join(MOCK, "catalog.schema.json"), encoding="utf-8") as f:
            cls.schema = json.load(f)
        cls.data = {n: load(n) for n in FILES}

    def test_schema_valid(self):
        for n, d in self.data.items():
            with self.subTest(file=n):
                if jsonschema:
                    jsonschema.Draft7Validator.check_schema(self.schema)
                    errs = [f"{'/'.join(map(str, e.absolute_path))}: {e.message}"
                            for e in jsonschema.Draft7Validator(self.schema).iter_errors(d)]
                else:
                    errs = mini_validate(d, self.schema, self.schema)
                self.assertEqual(errs[:10], [])
                self.assertEqual(d["space"], SPACE_OF[n])
                self.assertTrue(all(it["space"] == SPACE_OF[n] for it in d["items"]))
                self.assertTrue(all(c["space"] == SPACE_OF[n] for c in d["categories"]))

    def test_refs_resolve_within_file(self):
        for n, d in self.data.items():
            ids = {it["id"] for it in d["items"]}
            cat_ids = {c["id"] for c in d["categories"]}
            for c in d["categories"]:
                for h in c["heroIds"]:
                    self.assertIn(h, ids, f"{n}:{c['id']} hero {h}")
                self.assertTrue(3 <= len(c["heroIds"]) <= 5)
                self.assertTrue(4 <= len(c["rows"]) <= 8)
                for r in c["rows"]:
                    self.assertEqual(len(r["itemIds"]), len(set(r["itemIds"])), r["id"])
                    for i in r["itemIds"]:
                        self.assertIn(i, ids, f"{n}:{r['id']} item {i}")
            for it in d["items"]:
                self.assertIn(it["categoryId"], cat_ids, it["id"])
                for t in it.get("trackIds", []):
                    self.assertIn(t, ids)
                if "albumId" in it:
                    self.assertIn(it["albumId"], ids)

    def test_ids_globally_unique(self):
        seen = {}
        for n, d in self.data.items():
            ids = [c["id"] for c in d["categories"]] + [r["id"] for c in d["categories"] for r in c["rows"]]
            for it in d["items"]:
                ids.append(it["id"])
                ids += [e["id"] for s in it.get("seasons", []) for e in s["episodes"]]
                ids += [ch["id"] for ch in it.get("pages", [])]
                ids += [p["id"] for p in it.get("programs", [])]
            for i in ids:
                self.assertNotIn(i, seen, f"duplicate id {i} in {n} and {seen.get(i)}")
                seen[i] = n

    def test_titles_unique(self):
        titles = [it["title"] for n in ("home", "anime", "drama", "comics") for it in self.data[n]["items"]]
        self.assertEqual(len(titles), len(set(titles)))

    def test_movie_series_only_in_video_spaces(self):
        for n, d in self.data.items():
            for it in d["items"]:
                if it["type"] in ("MOVIE", "SERIES"):
                    self.assertIn(it["space"], ("HOME", "ANIME", "DRAMA"), it["id"])

    def test_video_items_have_sources(self):
        for n, d in self.data.items():
            for it in d["items"]:
                if it["type"] in VIDEO_TYPES | {"TRACK"}:
                    self.assertGreaterEqual(len(it.get("sources", [])), 1, it["id"])
                for s in it.get("seasons", []):
                    for e in s["episodes"]:
                        self.assertGreaterEqual(len(e["sources"]), 1, e["id"])

    def test_paths_exist(self):
        def check(rel):
            self.assertTrue(os.path.isfile(os.path.join(MOCK, rel)), rel)

        for n, d in self.data.items():
            for p in d.get("assets", {}).get("avatars", []):
                check(p)
            for it in d["items"]:
                for k in ("poster", "backdrop", "logo"):
                    if k in it:
                        check(it[k])
                for sub in it.get("subtitles", []):
                    check(sub["url"])
                for s in it.get("seasons", []):
                    for e in s["episodes"]:
                        check(e["still"])
                        for sub in e["subtitles"]:
                            check(sub["url"])
                for ch in it.get("pages", []):
                    for p in ch["pages"]:
                        check(p)

    def test_adult_placeholder_images_only(self):
        for it in self.data["adult"]["items"]:
            for k in ("poster", "backdrop", "logo"):
                if k in it:
                    self.assertTrue(it[k].startswith("images/adult/"), it[k])
        # and no non-adult item uses adult art
        for n, d in self.data.items():
            if n != "adult":
                for it in d["items"]:
                    self.assertFalse(it["poster"].startswith("images/adult/"))

    def test_sizes(self):
        d = self.data
        cnt = lambda n, **kw: sum(all(it.get(k) == v for k, v in kw.items()) for it in d[n]["items"])
        self.assertEqual(cnt("home", type="MOVIE"), 40)
        self.assertEqual(cnt("home", type="SERIES"), 20)
        self.assertEqual(len(d["anime"]["items"]), 30)
        self.assertEqual(cnt("drama", categoryId="dr-kdrama"), 15)
        self.assertEqual(cnt("drama", categoryId="dr-cdrama"), 10)
        self.assertEqual(cnt("drama", categoryId="dr-jdrama"), 10)
        self.assertEqual(cnt("tv", type="CHANNEL"), 30)
        self.assertEqual(cnt("tv", type="EVENT"), 10)
        self.assertEqual(cnt("music", type="ALBUM"), 10)
        self.assertEqual(cnt("music", type="TRACK"), 50)
        for c in ("co-manga", "co-manhwa", "co-manhua"):
            self.assertEqual(cnt("comics", categoryId=c), 10)
        for it in d["comics"]["items"]:
            self.assertEqual(len(it["pages"]), 3)
            self.assertTrue(all(len(ch["pages"]) == 12 for ch in it["pages"]))
        self.assertEqual(len(d["adult"]["items"]), 12)
        for it in d["home"]["items"]:
            if it["type"] == "SERIES":
                self.assertTrue(1 <= len(it["seasons"]) <= 3)
                self.assertTrue(all(4 <= len(s["episodes"]) <= 10 for s in it["seasons"]))

    def test_epg_covers_12h(self):
        tv = self.data["tv"]
        self.assertIsInstance(tv["epgAnchor"], int)
        for it in tv["items"]:
            if it["type"] != "CHANNEL":
                continue
            progs = it["programs"]
            self.assertLessEqual(progs[0]["startOffsetSec"], 0)
            for a, b in zip(progs, progs[1:]):
                self.assertEqual(a["startOffsetSec"] + a["durationSec"], b["startOffsetSec"])
                self.assertTrue(30 * 60 <= a["durationSec"] <= 90 * 60)
            self.assertGreaterEqual(progs[-1]["startOffsetSec"] + progs[-1]["durationSec"], 12 * 3600)

    def test_images_budget(self):
        total = 0
        for root, _, files in os.walk(os.path.join(MOCK, "images")):
            total += sum(os.path.getsize(os.path.join(root, f)) for f in files)
        self.assertLess(total, 25 * 1024 * 1024)


class TokensTest(unittest.TestCase):
    @classmethod
    def setUpClass(cls):
        with open(os.path.join(SHARED, "tokens", "themes.json"), encoding="utf-8") as f:
            cls.themes = json.load(f)

    def test_exact_theme_set(self):
        self.assertEqual(set(self.themes["themes"]), {"noir", "obsidian", "ember"})
        self.assertEqual(self.themes["default"], "noir")
        t = self.themes["themes"]
        self.assertEqual(t["noir"]["colors"]["accent"], "#E50914")
        self.assertEqual(t["obsidian"]["colors"]["accent"], "#C8102E")
        self.assertEqual(t["ember"]["colors"]["accentEnd"], "#FF7A1A")
        self.assertEqual((t["noir"]["blurDp"], t["obsidian"]["blurDp"], t["ember"]["blurDp"]), (14, 24, 22))

    def test_kotlin_has_all_themes(self):
        with open(os.path.join(GEN, "Tokens.kt"), encoding="utf-8") as f:
            kt = f.read()
        self.assertIn("package com.mythbrix.cinetor.design", kt)
        self.assertIn("enum class AppTheme { NOIR, OBSIDIAN, EMBER }", kt)
        for name in ("Noir", "Obsidian", "Ember"):
            self.assertIn(f"object {name} : CinetorThemeTokens", kt)
        # every Color literal is a well-formed 8-digit ARGB long
        for lit in re.findall(r"Color\(([^)]*)\)", kt):
            self.assertRegex(lit, r"^0x[0-9A-F]{8}L$")
        self.assertIn("Color(0x0FFFFFFFL)", kt)  # noir glassFill white 6%
        self.assertEqual(kt.count("{"), kt.count("}"))

    def test_css_matches_json(self):
        with open(os.path.join(GEN, "tokens.css"), encoding="utf-8") as f:
            css = f.read()
        for name, th in self.themes["themes"].items():
            m = re.search(r'\[data-theme="%s"\]\s*\{([^}]*)\}' % name, css)
            self.assertIsNotNone(m, name)
            block = dict(re.findall(r"--([a-z0-9-]+):\s*([^;]+);", m.group(1)))
            for k, v in th["colors"].items():
                css_key = re.sub(r"([A-Z])", lambda x: "-" + x.group(1).lower(), k)
                self.assertEqual(block[css_key].lower(), v.lower(), f"{name}.{k}")
            self.assertEqual(block["blur"], f"{th['blurDp']}px")
            self.assertEqual(float(block["tint-strength"]), th["tintStrength"])

    def test_ts_exists(self):
        with open(os.path.join(GEN, "tokens.ts"), encoding="utf-8") as f:
            ts = f.read()
        for name in ("noir", "obsidian", "ember"):
            self.assertIn(f'"{name}"', ts)


if __name__ == "__main__":
    unittest.main()
