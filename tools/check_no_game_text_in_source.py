#!/usr/bin/env python3
"""CI guard (CLAUDE.md rule 3): fail if tracked sources quote the ROM's message text.

Usage: check_no_game_text_in_source.py [--extracted DIR]

DIR defaults to $FE8_ASSETS/extracted (the runtime `text/en/messages.json`). Without it the check is skipped
(exit 0). Each message is split into fragments (control codes in {...} or [...] dropped, newlines and {NL}
split); fragments of 7+ characters containing a space and a lowercase letter are searched, case-sensitively,
in tracked *.kt, *.kts, *.py and *.md files. Prints `file:line` per hit (never the text) and exits 1 on any
hit. Name game things by decomp constant or message id instead of quoting them.
"""
import argparse
import json
import os
import re
import subprocess
import sys

# Fragments that may appear in source, one comment per entry. A hit needs a reason to be listed here.
ALLOWED = {
    'Fire Emblem',  # the game's title, a product name (MainActivity, TitleScreen, Fe8Import)
    'Sacred Stone',  # the game's title, a product name (MainActivity, TitleScreen, Fe8Import)
    'Sacred Stones',  # the game's title, a product name (MainActivity, TitleScreen, Fe8Import)
    'The Sacred Stone',  # the game's title, a product name (MainActivity, TitleScreen, Fe8Import)
    'A Button',  # GBA button name (GameAction, key handling)
    'B Button',  # GBA button name (GameAction, key handling)
    'R Button',  # GBA button name (GameAction, key handling)
    'Chapter 1',  # chapter number reference ("Chapter N"), not a chapter title
    'Chapter 2',  # chapter number reference ("Chapter N"), not a chapter title
    'Chapter 6',  # chapter number reference ("Chapter N"), not a chapter title
    'map menu',  # generic mechanics term of the port (the same word in any description of the game)
    'red unit',  # generic mechanics term of the port (the same word in any description of the game)
    'red flag',  # generic mechanics term of the port (the same word in any description of the game)
    'weapon triangle',  # generic mechanics term of the port (the same word in any description of the game)
    'enemy units',  # generic mechanics term of the port (the same word in any description of the game)
    'supply convoy',  # generic mechanics term of the port (the same word in any description of the game)
    'preparations screen',  # generic mechanics term of the port (the same word in any description of the game)
    'terrain bonuses',  # generic mechanics term of the port (the same word in any description of the game)
    'critical hit',  # generic mechanics term of the port (the same word in any description of the game)
    'attack range.',  # generic mechanics term of the port (the same word in any description of the game)
    'in the fog',  # generic mechanics term of the port (the same word in any description of the game)
    'with a sword',  # generic mechanics term of the port (the same word in any description of the game)
    'the title',  # generic mechanics term of the port (the same word in any description of the game)
    'the map.',  # generic mechanics term of the port (the same word in any description of the game)
    'with the cursor',  # generic mechanics term of the port (the same word in any description of the game)
    'The world',  # generic mechanics term of the port (the same word in any description of the game)
    'The light',  # generic mechanics term of the port (the same word in any description of the game)
    'The enemy',  # generic mechanics term of the port (the same word in any description of the game)
    'The rest',  # generic mechanics term of the port (the same word in any description of the game)
    'Units on',  # generic mechanics term of the port (the same word in any description of the game)
    'to Eirika',  # first name of a playable character in a comment (CLAUDE.md-accepted reference)
    'is gone',  # generic English phrase
    'not found',  # generic English phrase
    'of the',  # generic English phrase
    'is not',  # generic English phrase
    'does not',  # generic English phrase
    'with the',  # generic English phrase
    'the same',  # generic English phrase
    'that is',  # generic English phrase
    'use the',  # generic English phrase
    'and for',  # generic English phrase
    'added to',  # generic English phrase
    'did not',  # generic English phrase
    'they are',  # generic English phrase
    'me with',  # generic English phrase
    'in both',  # generic English phrase
    'a battle',  # generic English phrase
    'for each',  # generic English phrase
    'within the',  # generic English phrase
    'This is',  # generic English phrase
    'next to',  # generic English phrase
    ', while',  # generic English phrase
    'and the other',  # generic English phrase
    'is that',  # generic English phrase
    'to finish',  # generic English phrase
    'This is the',  # generic English phrase
    'can use',  # generic English phrase
    'press the',  # generic English phrase
    'the cost',  # generic English phrase
    ', while the',  # generic English phrase
    'share a',  # generic English phrase
    'battle and',  # generic English phrase
    'is gone.',  # generic English phrase
    'and any',  # generic English phrase
    'and press',  # generic English phrase
    'and select',  # generic English phrase
    'as this',  # generic English phrase
    'They are',  # generic English phrase
    'as well.',  # generic English phrase
    'with them',  # generic English phrase
    'Not yet',  # generic English phrase
    'as always',  # generic English phrase
    'These are',  # generic English phrase
    'in battle',  # generic English phrase
    'I never',  # generic English phrase
    'this task.',  # generic English phrase
    'with you',  # generic English phrase
    'you must',  # generic English phrase
    'something else',  # generic English phrase
    'find it',  # generic English phrase
    'and become',  # generic English phrase
    '3 times',  # generic English phrase
    'there. A',  # generic English phrase
    'to use the',  # generic English phrase
    'this way.',  # generic English phrase
    'and now',  # generic English phrase
    'and then he',  # generic English phrase
    'the whole of',  # generic English phrase
    'for a while',  # generic English phrase
    'After that',  # generic English phrase
}
EXTS = (".kt", ".kts", ".py", ".md")
CONTROL = re.compile(r"\{[^}]*\}|\[[^\]]*\]")


def fragments(entries):
    out = set()
    for m in entries:
        text = m.get("text") if isinstance(m, dict) else None
        if not isinstance(text, str):
            continue
        for part in CONTROL.sub("\n", text).split("\n"):
            part = part.strip()
            if len(part) >= 7 and " " in part and re.search("[a-z]", part) and part not in ALLOWED:
                out.add(part)
    return out


def contains(line, index):
    for i in range(len(line) - 6):
        for x in index.get(line[i:i + 7], ()):
            if line.startswith(x, i):
                return True
    return False


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("--extracted")
    args = ap.parse_args()
    root = args.extracted or (os.path.join(os.environ["FE8_ASSETS"], "extracted") if os.environ.get("FE8_ASSETS") else None)
    path = os.path.join(root, "text", "en", "messages.json") if root else None
    if not path or not os.path.isfile(path):
        print("check_no_game_text_in_source: skipped (no extracted messages.json)")
        return 0
    with open(path, encoding="utf-8") as f:
        frags = fragments(json.load(f)["entries"])
    index = {}  # first 7 characters -> fragments starting with them (a regex alternation is far too slow)
    for x in frags:
        index.setdefault(x[:7], []).append(x)
    repo = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))
    files = subprocess.run(["git", "ls-files"], cwd=repo, capture_output=True, text=True, check=True).stdout.split("\n")
    hits = 0
    for name in files:
        full = os.path.join(repo, name)
        if not name.endswith(EXTS) or not os.path.isfile(full):
            continue
        with open(full, encoding="utf-8", errors="replace") as f:
            for n, line in enumerate(f, 1):
                if contains(line, index):
                    print(f"{name}:{n}")
                    hits += 1
    if hits:
        print(f"{hits} line(s) quote game text; name things by decomp constant or message id", file=sys.stderr)
        return 1
    print(f"check_no_game_text_in_source: ok ({len(frags)} fragments)")
    return 0


if __name__ == "__main__":
    sys.exit(main())
