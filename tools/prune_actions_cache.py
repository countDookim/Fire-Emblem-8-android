#!/usr/bin/env python3
"""Deletes all but the newest GitHub Actions cache entry of each kind (key prefix) in a repo.

gradle/actions/setup-gradle saves new entries under new keys on every master run, so stale
snapshots pile up (8+ GB) until GitHub evicts them. Only the newest of each kind is restored.

Usage: python3 tools/prune_actions_cache.py OWNER/REPO [--dry-run]   (needs `gh`, actions: write)
"""
import json
import re
import subprocess
import sys


def kind(key: str) -> str:
    """Key prefix without the trailing content hash / run id (`gradle-home-v1|...|build[..]-<hash>`)."""
    return re.sub(r"(-[0-9a-f]{16,}|-\d+)+$", "", key)


def main() -> int:
    repo = sys.argv[1]
    dry = "--dry-run" in sys.argv
    out = subprocess.run(
        ["gh", "api", "--paginate", f"repos/{repo}/actions/caches?per_page=100",
         "-q", ".actions_caches[] | {id, key, ref, created_at, size_in_bytes}"],
        check=True, capture_output=True, text=True).stdout
    caches = [json.loads(line) for line in out.splitlines() if line.strip()]
    newest = {}
    for c in sorted(caches, key=lambda c: c["created_at"], reverse=True):
        newest.setdefault((kind(c["key"]), c["ref"]), c["id"])
    keep = set(newest.values())
    freed = 0
    failed = 0
    for c in caches:
        if c["id"] in keep:
            continue
        if not dry:
            r = subprocess.run(["gh", "api", "-X", "DELETE", f"repos/{repo}/actions/caches/{c['id']}"],
                               check=False, capture_output=True, text=True)
            if r.returncode != 0:
                failed += 1
                continue
        freed += c["size_in_bytes"]
    print(f"caches: {len(caches)}, kept {len(keep)}, {'would free' if dry else 'freed'} "
          f"{freed / 1e6:.0f} MB, failed deletes: {failed}")
    return 0


if __name__ == "__main__":
    sys.exit(main())
