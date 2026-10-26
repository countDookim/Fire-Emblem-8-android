#!/usr/bin/env python3
"""Compares two extraction output trees (committed vs fresh) for ci_check_extraction.sh.

Non-PNG files must be byte-identical. PNG files are compared by content: every chunk except IDAT must be
identical and the inflated IDAT data (filter bytes + pixels) must be identical, so a different zlib build
(another JDK's Deflater output for the same input) is not reported as drift. Prints differences, exit 1 if any.
"""
import os
import struct
import sys
import zlib


def png_content(path):
    data = open(path, "rb").read()
    if data[:8] != b"\x89PNG\r\n\x1a\n":
        return None
    chunks, idat, o = [], b"", 8
    while o < len(data):
        (n,) = struct.unpack(">I", data[o:o + 4])
        kind, body = data[o + 4:o + 8], data[o + 8:o + 8 + n]
        if kind == b"IDAT":
            idat += body
        else:
            chunks.append((kind, body))
        o += 12 + n
    return chunks, zlib.decompress(idat)


def files(root):
    out = set()
    for d, dirs, names in os.walk(root):
        dirs[:] = [x for x in dirs if x != ".release"]
        for f in (x for x in names if x != ".release"):
            out.add(os.path.relpath(os.path.join(d, f), root))
    return out


def main(a, b):
    fa, fb = files(a), files(b)
    diffs = [f"only in {a}: {f}" for f in sorted(fa - fb)] + [f"only in {b}: {f}" for f in sorted(fb - fa)]
    for f in sorted(fa & fb):
        pa, pb = os.path.join(a, f), os.path.join(b, f)
        ba, bb = open(pa, "rb").read(), open(pb, "rb").read()
        if ba == bb:
            continue
        if f.endswith(".png") and png_content(pa) is not None and png_content(pa) == png_content(pb):
            continue
        diffs.append(f"differs: {f}")
    for line in diffs[:50]:
        print(line)
    return 1 if diffs else 0


if __name__ == "__main__":
    sys.exit(main(sys.argv[1], sys.argv[2]))
