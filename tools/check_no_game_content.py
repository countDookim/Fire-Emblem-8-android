#!/usr/bin/env python3
"""CI guard (M4-05, docs/decisions/0011): fail if a build output or release asset contains game content.

Usage: check_no_game_content.py [--extracted DIR] PATH...

PATH is a file (an .apk/.zip/.jar/.aar is scanned entry by entry) or a directory (scanned recursively).
Flags:
  - any file whose SHA1 is the FE8 (U) ROM's, or that looks like a GBA ROM (valid cartridge header);
  - inside an archive, any `assets/` or `res/raw/` entry with an extracted-asset extension
    (.png .wav .mid .midi .gba .bin .lz), i.e. bundled game assets;
  - with --extracted (the ROM repo's `extracted/`): any file byte-identical to an extracted asset.
Exit status 1 with one line per finding, 0 if clean.
"""
import argparse
import hashlib
import os
import sys
import zipfile

ROM_SHA1 = "c25b145e37456171ada4b0d440bf88a19f4d509f"
ARCHIVES = (".apk", ".zip", ".jar", ".aar")
ASSET_EXTS = (".png", ".wav", ".mid", ".midi", ".gba", ".bin", ".lz")
ASSET_DIRS = ("assets/", "res/raw/")


def looks_like_gba_rom(data):
    """Cartridge header: fixed 0x96 at 0xB2 and a valid complement check at 0xBD."""
    if len(data) < 0x100000 or data[0xB2] != 0x96:
        return False
    chk = -sum(data[0xA0:0xBD]) - 0x19
    return (chk & 0xFF) == data[0xBD]


def check_blob(name, data, extracted, findings):
    sha = hashlib.sha1(data).hexdigest()
    if sha == ROM_SHA1:
        findings.append(f"{name}: is the FE8 ROM (SHA1 {sha})")
    elif looks_like_gba_rom(data):
        findings.append(f"{name}: looks like a GBA ROM (cartridge header)")
    if sha in extracted:
        findings.append(f"{name}: identical to extracted asset {extracted[sha]}")


def check_archive(path, extracted, findings):
    with zipfile.ZipFile(path) as z:
        for info in z.infolist():
            if info.is_dir():
                continue
            name = f"{path}!{info.filename}"
            lower = info.filename.lower()
            if lower.startswith(ASSET_DIRS) and lower.endswith(ASSET_EXTS):
                findings.append(f"{name}: game asset type in {lower.split('/')[0]}/")
            check_blob(name, z.read(info), extracted, findings)


def check_path(path, extracted, findings):
    if os.path.isdir(path):
        for root, _, files in os.walk(path):
            for f in sorted(files):
                check_path(os.path.join(root, f), extracted, findings)
    elif path.lower().endswith(ARCHIVES) and zipfile.is_zipfile(path):
        check_archive(path, extracted, findings)
    else:
        with open(path, "rb") as fh:
            check_blob(path, fh.read(), extracted, findings)


def extracted_hashes(directory):
    out = {}
    for root, _, files in os.walk(directory):
        for f in files:
            p = os.path.join(root, f)
            with open(p, "rb") as fh:
                data = fh.read()
            if len(data) >= 64:  # tiny files (e.g. "{}") would match by accident
                out[hashlib.sha1(data).hexdigest()] = os.path.relpath(p, directory)
    return out


def main(argv=None):
    ap = argparse.ArgumentParser(description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter)
    ap.add_argument("--extracted", help="ROM repo extracted/ dir (optional, stronger check)")
    ap.add_argument("paths", nargs="+")
    args = ap.parse_args(argv)
    extracted = extracted_hashes(args.extracted) if args.extracted and os.path.isdir(args.extracted) else {}
    findings = []
    for p in args.paths:
        check_path(p, extracted, findings)
    for f in findings:
        print(f"GAME CONTENT: {f}")
    if not findings:
        print(f"no game content in {len(args.paths)} path(s)"
              + (f" ({len(extracted)} extracted assets compared)" if extracted else ""))
    return 1 if findings else 0


if __name__ == "__main__":
    sys.exit(main())
