#!/usr/bin/env python3
"""Smoke-test the Japscan extension inside Komikku.

Samples mangas from the japscan-neo server catalogue, opens a random chapter of
each one in Komikku through adb/UiAutomator (selectors only, never raw
coordinates), captures the reader logs and classifies the outcome:

    OK       reader captured at least one page
    DEAD     the chapter redirects to its manga page (removed upstream)
    EMPTY    the reader finished without capturing any page
    BLOCKED  the reader timed out (Cloudflare / captcha)
    ERROR    any other exception raised by the source

When the app asks for a manual Cloudflare / captcha validation, the run pauses
and waits for Enter so the user can solve it in the WebView.

Usage:
    python3 tools/komikku_smoke.py --count 5
    python3 tools/komikku_smoke.py --count 3 --seed 42 --json /tmp/report.json
"""

from __future__ import annotations

import argparse
import json
import os
import random
import re
import subprocess
import sys
import time
import xml.etree.ElementTree as ET
from dataclasses import dataclass, field

SERVER = os.environ.get("JAPSCAN_SERVER", "https://server-japscan-neo.tailb8bddd.ts.net")
TOKEN = os.environ.get(
    "JAPSCAN_TOKEN",
    "5393edfb9926ac7194d8c81835b4aa964f3d1adffde91050e8d62a93ea91164c",
)

READER_LOG_TAGS = "JapscanReader:V JapscanDebug:V ReaderActivity:E *:S"
CAPTCHA_HINTS = (
    "Résolvez le captcha",
    "Ouvrez dans la WebView",
    "Failed to bypass Cloudflare",
    "Clé 2Captcha",
    "Un instant",
    "Just a moment",
    "Attention Required",
)


# --------------------------------------------------------------------------- adb


def adb(*args: str, timeout: int = 30, capture: bool = True) -> str:
    """Runs an adb command and returns its stdout."""
    result = subprocess.run(
        ["adb", *args],
        capture_output=capture,
        text=True,
        timeout=timeout,
    )
    return (result.stdout or "").strip()


def current_activity() -> str:
    """Returns the resumed activity as `package/activity`."""
    out = adb("shell", "dumpsys", "activity", "activities")
    match = re.search(r"topResumedActivity=.*?u0 ([^ }]+)", out)
    return match.group(1) if match else ""


def logcat_clear() -> None:
    adb("logcat", "-c", timeout=20)


def logcat_dump() -> str:
    return adb("logcat", "-d", timeout=60) or ""


# ---------------------------------------------------------------------- ui tree


@dataclass
class Node:
    text: str = ""
    desc: str = ""
    rid: str = ""
    cls: str = ""
    bounds: tuple[int, int, int, int] = (0, 0, 0, 0)
    clickable: bool = False

    @property
    def label(self) -> str:
        return self.text or self.desc

    @property
    def center(self) -> tuple[int, int]:
        left, top, right, bottom = self.bounds
        return (left + right) // 2, (top + bottom) // 2


def parse_bounds(raw: str) -> tuple[int, int, int, int]:
    numbers = [int(n) for n in re.findall(r"\d+", raw or "")]
    return tuple(numbers[:4]) if len(numbers) >= 4 else (0, 0, 0, 0)  # type: ignore[return-value]


def ui_dump() -> list[Node]:
    """Dumps the current window and returns every node that carries text."""
    adb("shell", "uiautomator", "dump", "/sdcard/ui_dump.xml", timeout=40)
    adb("pull", "/sdcard/ui_dump.xml", "/tmp/komikku_ui.xml", timeout=40)
    try:
        tree = ET.parse("/tmp/komikku_ui.xml")
    except (ET.ParseError, FileNotFoundError):
        return []

    nodes: list[Node] = []
    for element in tree.iter("node"):
        node = Node(
            text=element.get("text") or "",
            desc=element.get("content-desc") or "",
            rid=element.get("resource-id") or "",
            cls=element.get("class") or "",
            bounds=parse_bounds(element.get("bounds") or ""),
            clickable=element.get("clickable") == "true",
        )
        if node.label.strip():
            nodes.append(node)
    return nodes


def wait_for(
    predicate,
    timeout: float = 15.0,
    interval: float = 0.7,
) -> list[Node] | None:
    """Polls the UI until `predicate(nodes)` is truthy."""
    deadline = time.time() + timeout
    while time.time() < deadline:
        nodes = ui_dump()
        if predicate(nodes):
            return nodes
        time.sleep(interval)
    return None


def find(
    nodes: list[Node],
    text: str | None = None,
    contains: str | None = None,
    pattern: str | None = None,
    desc: str | None = None,
) -> Node | None:
    """Finds the first node matching one of the criteria (exact, substring, regex or description)."""
    for node in nodes:
        label = node.label
        if text is not None and label == text:
            return node
        if desc is not None and node.desc == desc:
            return node
        if contains is not None and contains.lower() in label.lower():
            return node
        if pattern is not None and re.search(pattern, label):
            return node
    return None


def tap(node: Node, settle: float = 1.2) -> None:
    x, y = node.center
    adb("shell", "input", "tap", str(x), str(y))
    time.sleep(settle)


def tap_text(text: str, timeout: float = 10.0, settle: float = 1.2) -> bool:
    nodes = wait_for(lambda ns: find(ns, text=text) is not None, timeout)
    if not nodes:
        return False
    tap(find(nodes, text=text))  # type: ignore[arg-type]
    time.sleep(settle)
    return True


def type_text(value: str) -> None:
    escaped = value.replace(" ", "%s")
    adb("shell", "input", "text", escaped)


def press_back(times: int = 1, settle: float = 1.0) -> None:
    for _ in range(times):
        adb("shell", "input", "keyevent", "4")
        time.sleep(settle)


def swipe_up() -> None:
    adb("shell", "input", "swipe", "600", "2300", "600", "600", "120")
    time.sleep(0.6)


# ------------------------------------------------------------------- app control


def bottom_nav(nodes: list[Node], label: str) -> Node | None:
    """Returns a bottom-navigation item (the bar sits at the very bottom of the screen)."""
    return next(
        (n for n in nodes if n.text == label and n.bounds[1] > 2400),
        None,
    )


def ensure_app(reset: bool = True) -> bool:
    """Starts Komikku from a known state: library with the bottom navigation visible."""
    if reset:
        adb("shell", "am", "force-stop", "app.komikku", timeout=30)
        time.sleep(1)
        adb("shell", "am", "start", "-n", "app.komikku/eu.kanade.tachiyomi.ui.main.MainActivity", timeout=40)
        time.sleep(5)

    for attempt in range(4):
        nodes = ui_dump()
        if bottom_nav(nodes, "Browse") or is_source_screen(nodes):
            print("    démarrage : écran racine prêt")
            return True
        if "reader" in current_activity().lower():
            press_back()
            continue
        if attempt == 0:
            press_back(times=1)
        else:
            adb("shell", "am", "force-stop", "app.komikku", timeout=30)
            time.sleep(1)
            adb("shell", "am", "start", "-n", "app.komikku/eu.kanade.tachiyomi.ui.main.MainActivity", timeout=40)
            time.sleep(5)
    return False


def open_japscan_source() -> bool:
    """Navigates Library → Browse tab → sources list → Japscan."""
    if is_source_screen(ui_dump()):
        return True

    nodes = ui_dump()
    browse = bottom_nav(nodes, "Browse")
    if not browse:
        print("    navigation : onglet Browse introuvable")
        return False
    tap(browse)
    time.sleep(2.5)

    for _ in range(4):
        nodes = ui_dump()
        japscan = next(
            (n for n in nodes if n.text == "Japscan" and n.bounds[1] > 300),
            None,
        )
        if japscan:
            tap(japscan)
            time.sleep(2.5)
            if is_source_screen(ui_dump()):
                print("    navigation : écran Japscan ouvert")
                return True
        swipe_up()
    print("    navigation : entrée Japscan introuvable dans la liste des sources")
    return False


def is_source_screen(nodes: list[Node]) -> bool:
    """True when the Japscan browse screen with its Popular/Latest/Search tab row is displayed."""
    tabs = [n for n in nodes if n.text in ("Popular", "Latest", "Search") and 350 <= n.bounds[1] <= 480]
    title = next((n for n in nodes if n.text == "Japscan" and n.bounds[1] < 320), None)
    return bool(tabs) and title is not None


def ensure_source_screen() -> bool:
    """Makes sure the Japscan Browse screen with its tabs is displayed."""
    if is_source_screen(ui_dump()):
        return True
    if open_japscan_source():
        return True
    # Deep navigation (search → detail → source) sometimes leaves the app somewhere unexpected:
    # restart it from scratch, which is the only state the harness fully controls.
    print("    navigation : redémarrage de Komikku pour repartir d'un état connu")
    return ensure_app(reset=True) and open_japscan_source()


def search_in_source(query: str) -> bool:
    """Runs a search inside the source and waits for results."""
    # The source screen exposes a "Search" tab and a toolbar search bar; the bar is the input the
    # text has to reach, so prefer it and fall back to the tab.
    nodes = ui_dump()
    bar = find(nodes, pattern=r"^Search\b") or find(nodes, text="Search")
    if not bar:
        return False
    tap(bar, settle=1.0)
    time.sleep(1.0)

    field = None
    for _ in range(6):
        nodes = ui_dump()
        field = next((n for n in nodes if "EditText" in n.cls), None)
        if field:
            break
        time.sleep(0.7)
    if field:
        tap(field, settle=0.5)
    # The field keeps the previous query between cases, so delete generously before typing.
    adb("shell", "input", "keyevent", "KEYCODE_MOVE_END")
    for _ in range(100):
        adb("shell", "input", "keyevent", "67")
    type_text(query)
    adb("shell", "input", "keyevent", "66")
    time.sleep(4)
    return True


def return_to_source() -> bool:
    """Backs out to the Japscan source screen from a reader or a detail page."""
    for _ in range(6):
        if is_source_screen(ui_dump()):
            return True
        press_back()
    # Landed somewhere else (library, sources list): restart from a known root.
    ensure_app()
    return open_japscan_source()


def wait_chapter_list(timeout: float = 25) -> str:
    """Waits for the chapter list of a detail page.

    @return "ok" when chapters are listed, "cf" on a Cloudflare block, "none" otherwise.
    """
    deadline = time.time() + timeout
    while time.time() < deadline:
        nodes = ui_dump()
        if cloudflare_blocking(nodes):
            return "cf"
        if any(re.match(r"^(chapitre|volume|chapter)\b", n.label, re.IGNORECASE) for n in nodes):
            return "ok"
        time.sleep(1.2)
    return "none"


def open_manga(title: str, timeout: float = 20) -> bool:
    """Opens the manga whose card title matches `title`."""
    deadline = time.time() + timeout
    while time.time() < deadline:
        nodes = ui_dump()
        exact = find(nodes, text=title)
        if exact and "Chapitre" not in exact.label:
            tap(exact)
            time.sleep(3.5)
            return True
        fuzzy = next(
            (n for n in nodes if n.label.lower().startswith(title.lower()[:18]) and "Chapitre" not in n.label),
            None,
        )
        if fuzzy:
            tap(fuzzy)
            time.sleep(3.5)
            return True
        swipe_up()
    return False


def find_chapter_nodes(nodes: list[Node], number: str) -> Node | None:
    """Returns the chapter row whose label names `number`."""
    pattern = rf"^chapitre\s+0*{re.escape(number)}\b"
    for node in nodes:
        if re.search(pattern, node.label, re.IGNORECASE):
            return node
    return None


def open_chapter(number: str, max_scrolls: int = 15) -> bool:
    """Scrolls the chapter list until `Chapitre <number>` shows and opens it."""
    for _ in range(max_scrolls):
        node = find_chapter_nodes(ui_dump(), number)
        if node:
            tap(node)
            return True
        swipe_up()
    return False


# ------------------------------------------------------------------- diagnostics


def reader_pages(timeout: float = 90) -> int | None:
    """Waits for the reader and returns its page count, or None on failure."""
    deadline = time.time() + timeout
    while time.time() < deadline:
        nodes = ui_dump()
        counter = find(nodes, pattern=r"^\d+\s*/\s*\d+$")
        if counter:
            total = int(re.findall(r"\d+", counter.label)[-1])
            return total
        for hint in CAPTCHA_HINTS:
            if find(nodes, contains=hint):
                return -1
        time.sleep(1.0)
    return None


def classify(log: str) -> tuple[str, str]:
    """Returns `(verdict, detail)` from the reader logs."""
    captured = re.findall(r"captured=(\d+) done=(\w+)", log)
    redirect = re.search(r"redirect check: requested=(\S+) final=(\S+)", log)
    exception = re.search(r"ReaderActivity.*Exception: (.+)", log)
    timeout = "getPageList timed out" in log

    if redirect:
        requested, final = redirect.group(1), redirect.group(2)
        if len(final.strip("/").split("/")) < len(requested.strip("/").split("/")):
            return "DEAD", f"redirige vers {final}"
    if exception:
        return "ERROR", exception.group(1)[:160]
    if captured:
        pages, done = captured[-1]
        if int(pages) > 0 and done == "true":
            return "OK", f"{pages} pages"
        if int(pages) == 0:
            return "EMPTY", "aucune page capturée"
    if timeout:
        return "BLOCKED", "timeout du lecteur"
    return "UNKNOWN", "aucun log du lecteur"


def cloudflare_blocking(nodes: list[Node]) -> bool:
    """True when Komikku shows the Cloudflare error screen or asks for a manual validation."""
    for node in nodes:
        label = node.label.lower()
        if any(hint.lower() in label for hint in CAPTCHA_HINTS):
            return True
        if "failed to bypass" in label or "captcha" in label:
            return True
    return False


def check_cloudflare(case: dict) -> bool:
    """Pauses for the user when Cloudflare needs a manual solve; True when it happened."""
    if cloudflare_blocking(ui_dump()):
        pause_for_captcha(case)
        return True
    return False


def pause_for_captcha(case: dict) -> None:
    """Hands control back to the user when Cloudflare needs a manual solve."""
    print("\n" + "=" * 78)
    print("  ACTION REQUISE : Cloudflare / captcha à valider manuellement")
    print(f"  Cas bloqué : {case['title']} — chapitre {case['chapter']}")
    print("  Résous le challenge dans la WebView de Komikku (ou navigue jusqu'au site),")
    print("  puis reviens ici et appuie sur Entrée pour rejouer ce cas.")
    print("=" * 78)
    input("  > Entrée pour continuer… ")


# ---------------------------------------------------------------------- sampling


def http_json(path: str):
    """Calls the japscan-neo API."""
    import urllib.request

    request = urllib.request.Request(
        SERVER.rstrip("/") + path,
        headers={"Authorization": "Bearer " + TOKEN},
    )
    with urllib.request.urlopen(request, timeout=90) as response:
        return json.loads(response.read().decode())


def sample_cases(count: int, rng: random.Random) -> list[dict]:
    """Picks `count` mangas at random and a random chapter for each."""
    cases: list[dict] = []
    for _ in range(count):
        for _attempt in range(6):
            page = rng.randint(1, 200)
            listing = http_json(f"/api/manga?page={page}&sort=UPDATED")
            items = listing.get("items") or []
            if not items:
                continue
            manga = rng.choice(items)
            details = http_json(f"/api/manga/{manga['slug']}")
            # Chapter 0 is often a prologue that the site no longer serves, and the newest pages
            # are at the top of the app list: sample those so the harness does not need to scroll
            # through hundreds of rows.
            chapters = [
                c for c in (details.get("chapters") or [])
                if (c.get("chapterNumber") or 0) not in (0, 0.0)
            ]
            if not chapters:
                continue
            chapter = rng.choice(chapters[:10])
            number = chapter.get("chapterNumber")
            label = str(int(number)) if isinstance(number, (int, float)) and float(number).is_integer() else str(number)
            cases.append(
                {
                    "title": details.get("title") or manga["title"],
                    "slug": manga["slug"],
                    "chapter": label or str(chapter.get("chapterSlug")),
                },
            )
            break
    return cases


# ------------------------------------------------------------------------- main


@dataclass
class Result:
    title: str
    chapter: str
    verdict: str
    detail: str
    pages: int | None = None
    logs: str = field(default="", repr=False)


def run_case(case: dict) -> Result:
    """Drives one manga/chapter pair through the app."""
    if not ensure_source_screen():
        return Result(case["title"], case["chapter"], "UI", "écran source Japscan introuvable")
    if check_cloudflare(case):
        return Result(case["title"], case["chapter"], "RETRY", "captcha demandé (source)")
    if not search_in_source(case["title"]):
        return Result(case["title"], case["chapter"], "UI", "champ de recherche introuvable")
    if check_cloudflare(case):
        return Result(case["title"], case["chapter"], "RETRY", "captcha demandé (recherche)")
    if not open_manga(case["title"]):
        return Result(case["title"], case["chapter"], "UI", "fiche introuvable dans les résultats")

    state = wait_chapter_list()
    if state == "cf":
        pause_for_captcha(case)
        return Result(case["title"], case["chapter"], "RETRY", "captcha demandé (fiche)")
    if state == "none":
        return Result(case["title"], case["chapter"], "BLOCKED", "liste des chapitres vide (Cloudflare ?)")

    if not open_chapter(case["chapter"]):
        return Result(case["title"], case["chapter"], "UI", "chapitre introuvable dans la liste")

    pages = reader_pages()
    if pages == -1:
        pause_for_captcha(case)
        return Result(case["title"], case["chapter"], "RETRY", "captcha demandé")

    log = logcat_dump()
    verdict, detail = classify(log)
    if verdict == "UNKNOWN" and pages:
        verdict, detail = "OK", f"{pages} pages (compteur)"
    return_to_source()
    time.sleep(1)
    return Result(case["title"], case["chapter"], verdict, detail, pages, log)


def main() -> int:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--count", type=int, default=5, help="number of mangas to sample")
    parser.add_argument("--seed", type=int, default=None, help="PRNG seed for reproducibility")
    parser.add_argument("--json", default=None, help="write the report to this path")
    args = parser.parse_args()

    if not subprocess.run(["adb", "devices"], capture_output=True, text=True).stdout.count("\tdevice"):
        print("Aucun appareil adb connecté.", file=sys.stderr)
        return 2

    rng = random.Random(args.seed)
    cases = sample_cases(args.count, rng)
    if not cases:
        print("Échantillonnage impossible (API serveur injoignable ?).", file=sys.stderr)
        return 2

    print(f"{len(cases)} cas à tester (seed={args.seed})\n")
    if not ensure_app(reset=True) or not open_japscan_source():
        print("Impossible d'ouvrir l'écran source Japscan dans Komikku.", file=sys.stderr)
        return 2

    results: list[Result] = []
    for index, case in enumerate(cases, 1):
        print(f"[{index}/{len(cases)}] {case['title']} — chapitre {case['chapter']}")
        logcat_clear()
        result = run_case(case)
        if result.verdict == "RETRY":
            logcat_clear()
            result = run_case(case)
        print(f"    → {result.verdict} : {result.detail}")
        results.append(result)
        time.sleep(3)

    print("\n" + "=" * 78)
    print(f"{'Série':38} {'Ch.':>5}  {'Verdict':8} Détail")
    print("-" * 78)
    for result in results:
        print(f"{result.title[:38]:38} {result.chapter:>5}  {result.verdict:8} {result.detail[:34]}")

    summary: dict[str, int] = {}
    for result in results:
        summary[result.verdict] = summary.get(result.verdict, 0) + 1
    print("-" * 78)
    print("  ".join(f"{k}={v}" for k, v in sorted(summary.items())))

    if args.json:
        payload = {
            "seed": args.seed,
            "server": SERVER,
            "summary": summary,
            "cases": [
                {
                    "title": r.title,
                    "chapter": r.chapter,
                    "verdict": r.verdict,
                    "detail": r.detail,
                    "pages": r.pages,
                }
                for r in results
            ],
        }
        with open(args.json, "w", encoding="utf-8") as handle:
            json.dump(payload, handle, ensure_ascii=False, indent=2)
        print(f"\nRapport écrit dans {args.json}")

    return 0


if __name__ == "__main__":
    raise SystemExit(main())
