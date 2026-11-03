#!/usr/bin/env bash
# M2-21: runs the extractor (gbafe.extract CLI) on the ROM and checks that its output is byte-identical to the
# ROM repo's committed extracted/ (released as extracted.tar.xz, unpacked to $FE8_ASSETS/extracted).
# A difference means the extractor changed without re-extracting: run the CLI and commit the output to the ROM
# repo (see HANDOFF). Skips (exit 0, warning) when the ROM assets, the committed extraction or the decomp
# symbols are missing. Output goes outside the code repo (the CLI refuses otherwise).
set -euo pipefail
ROOT="$(cd "$(dirname "$0")/.." && pwd)"
: "${FE8_ASSETS:?FE8_ASSETS not set}"
skip() { echo "::warning title=Extraction check skipped::$1"; exit 0; }
[ -f "$FE8_ASSETS/baserom.gba" ] || skip "no ROM"
[ -d "$FE8_ASSETS/extracted" ] || skip "no committed extraction (extracted.tar.xz) in the ROM-repo release"
[ -f "$FE8_ASSETS/fireemblem8.map" ] && [ -f "$FE8_ASSETS/fireemblem8.nm.txt" ] || skip "no decomp symbols in the release"
WORK="${RUNNER_TEMP:-$(mktemp -d)}/fe8-extract"
rm -rf "$WORK" && mkdir -p "$WORK"
# Decomp source (public) at the pinned commit: names, texts, songs need it.
PIN="$(grep -oE '^[0-9a-f]{40}' "$ROOT/reference/DECOMP_VERSION" | head -1)"
git clone -q --filter=blob:none --sparse https://github.com/FireEmblemUniverse/fireemblem8u "$WORK/decomp"
(cd "$WORK/decomp" && git checkout -q "$PIN" && git sparse-checkout set --no-cone include src texts asm data sound)
"$ROOT/gradlew" -q :tools:extract:run --console=plain --args="--rom $FE8_ASSETS/baserom.gba --out $WORK/out \
  --decomp $WORK/decomp --map $FE8_ASSETS/fireemblem8.map --symbols $FE8_ASSETS/fireemblem8.nm.txt \
  --write-manifest $WORK/manifest.json"
# M4-03: the committed extraction manifest must be what the decomp gives, and extraction from the ROM and the
# manifest alone (as on device) must give the same output.
MANIFEST="$ROOT/tools/extract/manifest/fe8.json"
if ! cmp -s "$WORK/manifest.json" "$MANIFEST"; then
  echo "::error title=Manifest drift::tools/extract/manifest/fe8.json is stale (regenerate with --write-manifest)"
  diff "$WORK/manifest.json" "$MANIFEST" | head -20
  exit 1
fi
"$ROOT/gradlew" -q :tools:extract:run --console=plain \
  --args="--rom $FE8_ASSETS/baserom.gba --out $WORK/out-manifest --manifest $MANIFEST"
if ! diff -r -q "$WORK/out" "$WORK/out-manifest"; then
  echo "::error title=Manifest extraction drift::extraction from the manifest differs from the decomp extraction"
  exit 1
fi
# PNGs compare by content (chunks + inflated pixels): Deflater output differs between zlib builds.
if python3 "$ROOT/tools/compare_extraction.py" "$FE8_ASSETS/extracted" "$WORK/out" > "$WORK/diff.txt"; then
  echo "extraction reproduces the committed extracted/ ($(find "$WORK/out" -type f | wc -l) files)"
else
  echo "::error title=Extraction drift::extractor output differs from the ROM repo's extracted/ (re-extract and commit to the ROM repo)"
  head -50 "$WORK/diff.txt"
  exit 1
fi
