#!/usr/bin/env python3
"""Keep an eligible master CI run's artifacts and remove older Actions artifacts.

Requires gh authentication with actions: write. Use --dry-run to preview deletions.
"""
import argparse
import json
import os
import subprocess
import sys
import time


def api(*args):
    result = subprocess.run(["gh", "api", *args], capture_output=True, text=True)
    if result.returncode:
        raise RuntimeError(f"gh api failed: {result.stderr.strip()}")
    return json.loads(result.stdout)


def prune(repo, keep_run, dry_run=False):
    run = api(f"repos/{repo}/actions/runs/{keep_run}")
    current = (run.get("status") == "in_progress"
               and os.environ.get("GITHUB_RUN_ID") == str(keep_run)
               and os.environ.get("GITHUB_REPOSITORY") == repo
               and os.environ.get("GITHUB_REF") == "refs/heads/master")
    successful = run.get("status") == "completed" and run.get("conclusion") == "success"
    if (run.get("id") != keep_run or run.get("head_branch") != "master"
            or run.get("path", "").split("@", 1)[0] != ".github/workflows/ci.yml"
            or not (successful or current)):
        raise ValueError("keep run must be a successful master ci run or the current master ci run")

    # Fetch every page before deleting anything, including when an API request fails.
    artifacts = []
    page = 1
    while True:
        batch = api(f"repos/{repo}/actions/artifacts?per_page=100&page={page}")["artifacts"]
        artifacts.extend(batch)
        if len(batch) < 100:
            break
        page += 1
    available = {a.get("name") for a in artifacts
                 if a.get("workflow_run", {}).get("id") == keep_run and not a.get("expired", False)}
    if not {"results", "dokka-site"} <= available:
        raise ValueError("keep run lacks non-expired results and dokka-site; preserving previous artifacts")

    if any(not isinstance(a.get("workflow_run", {}).get("id"), int) for a in artifacts):
        raise ValueError("an artifact has no workflow run ID; refusing to delete")

    deleted = 0
    for artifact in artifacts:
        run_id = artifact["workflow_run"]["id"]
        if run_id >= keep_run:
            continue
        print(f"{'Would delete' if dry_run else 'Deleting'} artifact {artifact['id']} (run {run_id})")
        if not dry_run:
            if deleted:
                time.sleep(1)
            result = subprocess.run(
                ["gh", "api", "-X", "DELETE", f"repos/{repo}/actions/artifacts/{artifact['id']}"],
                capture_output=True, text=True,
            )
            if result.returncode and "HTTP 404" not in result.stderr:
                raise RuntimeError(f"delete artifact {artifact['id']} failed: {result.stderr.strip()}")
        deleted += 1
    print(f"{'Would remove' if dry_run else 'Removed or already absent'} {deleted} artifacts")


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("repo", nargs="?", default=os.environ.get("GITHUB_REPOSITORY"), help="OWNER/REPO")
    parser.add_argument("--keep-run", type=int, required=True, help="run ID whose artifacts must be kept")
    parser.add_argument("--dry-run", action="store_true")
    args = parser.parse_args()
    if not args.repo or args.keep_run <= 0:
        parser.error("a repository and positive --keep-run are required")
    try:
        prune(args.repo, args.keep_run, args.dry_run)
    except (RuntimeError, ValueError, KeyError, TypeError, json.JSONDecodeError) as error:
        print(f"artifact cleanup failed: {error}", file=sys.stderr)
        return 1
    return 0


if __name__ == "__main__":
    sys.exit(main())
