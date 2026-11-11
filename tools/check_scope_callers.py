#!/usr/bin/env python3
"""Retail-caller check for the progress out-of-scope set (read-only on the decomp).

For every function that tools/progress.py treats as out of scope (link-cable play, debug menus), search the
decomp for references from files OUTSIDE the out-of-scope set: calls, function-pointer tables, proc scripts,
menu definitions (src/*.c, asm/**/*.s, data/**/*.s, *.inc). A function referenced from an in-scope file should
be listed in progress.KEEP_IN_SCOPE (with the referencing file named in a comment).

    FE8U_DECOMP=~/FE8/fireemblem8u python3 tools/check_scope_callers.py
    python3 tools/check_scope_callers.py /path/to/fireemblem8u

Headers (*.h) are skipped: they only declare. Comments are stripped before matching. Not run in CI.
The decomp checkout may be sparse; the directories actually scanned (and missing ones) are printed.
"""
from __future__ import annotations

import os
import re
import sys
from collections import defaultdict
from pathlib import Path

sys.path.insert(0, str(Path(__file__).resolve().parent))
import progress  # noqa: E402

SCAN = (("src", ".c"), ("asm", ".s"), ("asm", ".inc"), ("data", ".s"), ("data", ".inc"), ("sound", ".s"), ("sound", ".inc"))
EXPECTED_DIRS = ("src", "asm", "data", "include", "sound")
BLOCK_COMMENT = re.compile(r"/\*.*?\*/", re.S)
LINE_COMMENT = re.compile(r"//[^\n]*|@[^\n]*")  # `@` starts an asm comment; harmless in C sources


def main(argv: list[str]) -> int:
    decomp = Path(argv[1] if len(argv) > 1 else os.environ.get("FE8U_DECOMP", "~/FE8/fireemblem8u")).expanduser()
    if not decomp.is_dir():
        print(f"decomp not found: {decomp} (pass a path or set FE8U_DECOMP)", file=sys.stderr)
        return 2
    ref = progress.load_reference()
    # Candidates = out of scope by file/system rules, ignoring KEEP_IN_SCOPE so reruns stay stable.
    kept, progress.KEEP_IN_SCOPE = progress.KEEP_IN_SCOPE, frozenset()
    cand = {n: r for n, r in ref.items() if progress.is_out_of_scope(n, r)}
    progress.KEEP_IN_SCOPE = kept
    oos_files = {r["file"] for r in cand.values()}
    rx = re.compile(r"\b(" + "|".join(sorted(map(re.escape, cand), key=len, reverse=True)) + r")\b")

    print("scanned dirs:", ", ".join(d for d in EXPECTED_DIRS if (decomp / d).is_dir()) or "-")
    missing = [d for d in EXPECTED_DIRS if not (decomp / d).is_dir()]
    if missing:
        print("NOT AVAILABLE (cannot check):", ", ".join(missing))
    hits: dict[str, set[str]] = defaultdict(set)
    nfiles = 0
    for d, ext in SCAN:
        if not (decomp / d).is_dir():
            continue
        for p in sorted((decomp / d).rglob(f"*{ext}")):
            rel = p.relative_to(decomp).as_posix()
            if rel in oos_files:
                continue
            nfiles += 1
            text = LINE_COMMENT.sub("", BLOCK_COMMENT.sub("", p.read_text(errors="replace")))
            for m in rx.finditer(text):
                name = m.group(1)
                line = text[text.rfind("\n", 0, m.start()) + 1: text.find("\n", m.end())]
                if rel.endswith((".s", ".inc")) and re.match(
                        rf"\s*(\.global\s+|\.type\s+|\.thumb_func|{re.escape(name)}\s*:)", line):
                    continue  # label/definition, not a reference
                hits[name].add(rel)
    print(f"candidates: {len(cand)}  files scanned outside the set: {nfiles}")
    print(f"referenced from in-scope files: {len(hits)}")
    for name in sorted(hits):
        print(f"  {name} ({cand[name]['file']}): {', '.join(sorted(hits[name]))}")
    unlisted = sorted(n for n in hits if n not in progress.KEEP_IN_SCOPE)
    if unlisted:
        print(f"NOT in progress.KEEP_IN_SCOPE: {', '.join(unlisted)}")
    return 0


if __name__ == "__main__":
    raise SystemExit(main(sys.argv))
