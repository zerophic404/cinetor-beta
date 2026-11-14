#!/usr/bin/env python3
"""HEAD every unique source URL in the mock catalog (5 s timeout) and print a
status table. Informational only — always exits 0.
Usage: python3 shared/scripts/check_streams.py"""
import json
import os
import urllib.error
import urllib.request
from concurrent.futures import ThreadPoolExecutor

MOCK = os.path.join(os.path.dirname(os.path.dirname(os.path.abspath(__file__))), "mock")
FILES = ["home", "anime", "drama", "tv", "music", "comics", "adult"]


def urls():
    out = {}
    for n in FILES:
        with open(os.path.join(MOCK, f"{n}.json"), encoding="utf-8") as f:
            d = json.load(f)
        for it in d["items"]:
            srcs = list(it.get("sources", []))
            for s in it.get("seasons", []):
                for e in s["episodes"]:
                    srcs += e["sources"]
            for s in srcs:
                out.setdefault(s["url"], s["kind"])
    return out


def head(url):
    req = urllib.request.Request(url, method="HEAD", headers={"User-Agent": "Cinetor-check/1.0"})
    try:
        with urllib.request.urlopen(req, timeout=5) as r:
            return r.status, "OK"
    except urllib.error.HTTPError as e:
        if e.code in (403, 405):  # some CDNs reject HEAD; retry tiny GET
            try:
                g = urllib.request.Request(url, headers={"User-Agent": "Cinetor-check/1.0", "Range": "bytes=0-0"})
                with urllib.request.urlopen(g, timeout=5) as r:
                    return r.status, "OK (GET)"
            except Exception as e2:  # noqa: BLE001
                return getattr(e2, "code", None) or "ERR", f"DEAD ({e2})"[:60]
        return e.code, "DEAD"
    except Exception as e:  # noqa: BLE001
        return "ERR", f"DEAD ({type(e).__name__}: {e})"[:60]


def main():
    u = urls()
    with ThreadPoolExecutor(8) as ex:
        res = dict(zip(u, ex.map(head, u)))
    print(f"{'STATUS':<7} {'KIND':<5} {'RESULT':<12} URL")
    dead = 0
    for url, kind in sorted(u.items(), key=lambda x: (x[1], x[0])):
        code, msg = res[url]
        dead += msg.startswith("DEAD")
        print(f"{str(code):<7} {kind:<5} {msg[:12]:<12} {url}")
        if msg.startswith("DEAD"):
            print(f"        -> {msg}")
    print(f"\n{len(u)} unique URLs, {dead} dead")


if __name__ == "__main__":
    main()
