#!/usr/bin/env python3
"""Writes a short Markdown CI summary (tests, skips, benchmark, progress) to stdout.
Used by CI for $GITHUB_STEP_SUMMARY and the ci-summary artifact; sessions can run it locally too."""
import json
import subprocess
import sys
import xml.etree.ElementTree as ET
from pathlib import Path

ROOT = Path(__file__).resolve().parent.parent
tests = fails = skips = 0
failed, skipped = [], []
for x in sorted(ROOT.glob("**/build/test-results/**/*.xml")):
    if x.relative_to(ROOT).as_posix().startswith(".claude/"):
        continue  # agent worktrees hold other checkouts' results
    for tc in ET.parse(x).iter("testcase"):
        tests += 1
        name = f"{tc.get('classname')}.{tc.get('name')}"
        if any(c.tag in ("failure", "error") for c in tc):
            fails += 1
            failed.append(name)
        elif any(c.tag == "skipped" for c in tc):
            skips += 1
            skipped.append(name)
bench = ROOT / "testing/build/reports/benchmark/frame_time.json"
b = json.loads(bench.read_text()) if bench.exists() else None
progress = subprocess.run([sys.executable, str(ROOT / "tools/progress.py")], capture_output=True, text=True).stdout.splitlines()
assets = sys.argv[1] if len(sys.argv) > 1 else "unknown"

print(f"## CI summary\n")
print(f"- Tests: **{tests - fails - skips} passed**, {fails} failed, {skips} skipped")
print(f"- ROM reference assets available: **{assets}**" + ("" if assets == "true" else " — differential tests skipped!"))
if b:
    print(f"- Frame benchmark: p50 {b['p50_us']:.1f} µs, p99 {b['p99_us']:.1f} µs, allocated {b['allocated_bytes']} B "
          f"(budget p50 {b['budget_p50_us']:.0f} / p99 {b['budget_p99_us']:.0f} µs)")
else:
    print("- Frame benchmark: not run")
if progress:
    print(f"- {progress[0]}")
for title, items in (("Failed", failed), ("Skipped", skipped)):
    if items:
        print(f"\n<details><summary>{title} ({len(items)})</summary>\n")
        for i in items[:50]:
            print(f"- `{i}`")
        print("\n</details>")

# Guard: with reference assets present, nothing may be skipped (a skip then means a broken test setup,
# e.g. stale build-cache results). Fails the CI job so the `latest` release is not published.
if assets == "true" and skips:
    print(f"\n**ERROR:** assets are available but {skips} test(s) were skipped.", file=sys.stderr)
    sys.exit(1)
