"""Layering rule of docs/decisions/0010-multi-game-reuse.md: shared `gbafe` code never depends on the
FE8 game layer. Every Kotlin file whose package is `gbafe.*` (except the per-game extractor packages
`gbafe.extract.<game>`) must not import or reference `fe8.` packages. Game-neutral GBA code (`gba.*`, M4-06) imports neither
`gbafe.*` nor `fe8.*`."""
import pathlib
import re
import unittest

ROOT = pathlib.Path(__file__).resolve().parents[2]
GAME_PACKAGES = re.compile(r"^gbafe\.extract\.(fe6|fe7|fe8)(\.|$)")
FE8_REF = re.compile(r"(^|[^\w.])fe8\.(?!extract\b)[a-z]")


class LayeringTest(unittest.TestCase):
    def test_shared_code_does_not_use_the_fe8_layer(self):
        offenders = []
        checked = 0
        for f in ROOT.glob("**/src/main/kotlin/**/*.kt"):
            if "build" in f.parts:
                continue
            text = f.read_text(encoding="utf-8")
            m = re.search(r"^package\s+([\w.]+)", text, re.M)
            if not m or not m.group(1).startswith("gbafe") or GAME_PACKAGES.match(m.group(1)):
                continue
            checked += 1
            for n, line in enumerate(text.splitlines(), 1):
                code = line.split("//")[0]
                if code.lstrip().startswith("*"):
                    continue
                if FE8_REF.search(code):
                    offenders.append(f"{f.relative_to(ROOT)}:{n}: {line.strip()}")
        self.assertGreater(checked, 10)
        self.assertEqual([], offenders)

    def test_gba_code_uses_neither_gbafe_nor_fe8(self):
        offenders = []
        checked = 0
        for f in ROOT.glob("**/src/*/kotlin/**/*.kt"):
            if "build" in f.parts:
                continue
            text = f.read_text(encoding="utf-8")
            m = re.search(r"^package\s+([\w.]+)", text, re.M)
            if not m or not re.match(r"gba(\.|$)", m.group(1)):
                continue
            checked += 1
            for n, line in enumerate(text.splitlines(), 1):
                if re.match(r"\s*import\s+(gbafe|fe8)\.", line):
                    offenders.append(f"{f.relative_to(ROOT)}:{n}: {line.strip()}")
        self.assertGreater(checked, 0)
        self.assertEqual([], offenders)


if __name__ == "__main__":
    unittest.main()
