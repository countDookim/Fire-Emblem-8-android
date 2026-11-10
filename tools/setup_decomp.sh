#!/usr/bin/env bash
# Provision the pinned public source files needed by runtime reference tests.
set -euo pipefail
ROOT="$(cd "$(dirname "$0")/.." && pwd)"
DEST="${FE8U_DECOMP:-${FE8_ASSETS:-$ROOT/.assets}/decomp}"
mapfile -t VERSION < "$ROOT/reference/DECOMP_VERSION"
URL="${VERSION[0]:-}"
PIN="${VERSION[1]:-}"
[[ ${#VERSION[@]} -eq 2 && "$URL" =~ ^https://github\.com/[A-Za-z0-9_.-]+/[A-Za-z0-9_.-]+$ && "$PIN" =~ ^[0-9a-f]{40}$ ]] || {
  echo "Invalid decomp URL or full commit in reference/DECOMP_VERSION" >&2; exit 1;
}
verify() {
  [[ "$(git -C "$1" rev-parse HEAD 2>/dev/null)" == "$PIN" ]] &&
    git -C "$1" diff --quiet HEAD -- src/worldmap_node_data.c include/constants/chapters.h include/worldmap.h &&
    [[ -f "$1/src/worldmap_node_data.c" && -f "$1/include/constants/chapters.h" && -f "$1/include/worldmap.h" ]]
}
if [[ -e "$DEST" ]]; then
  verify "$DEST" || { echo "Decomp at $DEST is missing required files or is not pinned at $PIN" >&2; exit 1; }
  exit 0
fi
mkdir -p "$(dirname "$DEST")"
WORK="$(mktemp -d "$(dirname "$DEST")/.decomp-XXXXXX")"
trap 'rm -rf "$WORK"' EXIT
git -C "$WORK" init -q
git -C "$WORK" remote add origin "$URL"
git -C "$WORK" config core.sparseCheckout true
printf '%s\n' '/src/worldmap_node_data.c' '/include/constants/chapters.h' '/include/worldmap.h' > "$WORK/.git/info/sparse-checkout"
git -C "$WORK" -c protocol.version=2 fetch -q --depth=1 --filter=blob:none origin "$PIN"
git -C "$WORK" checkout -q --detach FETCH_HEAD
verify "$WORK" || { echo "Fetched decomp does not match $PIN or lacks required files" >&2; exit 1; }
mv "$WORK" "$DEST"
