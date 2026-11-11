"""Self-test for tools/unverified.py on a tiny fake repo (run: python3 -m unittest discover tools/tests)."""
from __future__ import annotations

import contextlib
import io
import sys
import tempfile
import textwrap
import unittest
from pathlib import Path

sys.path.insert(0, str(Path(__file__).resolve().parent.parent))
import unverified  # noqa: E402

REFERENCE = """\
name\tfile\tline\tkind\tsystem\taddress
FuncA\tsrc/a.c\t1\tc\talpha\t0x08000000
FuncB\tsrc/a.c\t2\tc\talpha\t0x08000010
FuncC\tsrc/b.c\t1\tc\tbeta\t0x08000020
"""

GOOD_KT = """\
package fx

/** Transcribed from src/a.c: FuncA (not verified: no capture yet). */
fun a() {}

/**
 * Transcribed from src/a.c: FuncB (not verified: the reason wraps over
 * two lines, with a (nested) note | and a pipe).
 */
fun b() {}

/**
 * Transcribed from src/b.c: FuncC, Unlisted (not verified: shared reason)
 */
fun c() {}
"""

BAD_KT = """\
package fx

/** Transcribed from src/b.c: FuncC (no reference run yet). */
fun d() {}
"""


class UnverifiedTest(unittest.TestCase):
    def setUp(self):
        self._tmp = tempfile.TemporaryDirectory()
        self.root = Path(self._tmp.name)
        (self.root / "reference").mkdir()
        (self.root / "reference" / "decomp_functions.tsv").write_text(REFERENCE)
        self.write_kt("game/src/commonMain/kotlin/fx/Good.kt", GOOD_KT)
        # Must be ignored: test sources, build output, agent worktrees.
        for ignored in ("game/src/test/kotlin/fx/T.kt", "game/build/src/main/kotlin/fx/B.kt",
                        ".claude/worktrees/w/game/src/main/kotlin/fx/W.kt"):
            self.write_kt(ignored, BAD_KT)

    def tearDown(self):
        self._tmp.cleanup()

    def write_kt(self, rel, text):
        p = self.root / rel
        p.parent.mkdir(parents=True, exist_ok=True)
        p.write_text(textwrap.dedent(text))

    def run_tool(self, *args):
        out, err = io.StringIO(), io.StringIO()
        with contextlib.redirect_stdout(out), contextlib.redirect_stderr(err):
            code = unverified.main([*args, "--root", str(self.root)])
        return code, out.getvalue(), err.getvalue()

    def rows(self):
        return {(e["file"], e["name"]): e for e in unverified.scan(self.root)}

    def test_single_line_entry(self):
        e = self.rows()[("src/a.c", "FuncA")]
        self.assertEqual(e["reason"], "no capture yet")
        self.assertEqual(e["kotlin"], "game/src/commonMain/kotlin/fx/Good.kt")

    def test_wrapped_reason_with_nested_parens(self):
        e = self.rows()[("src/a.c", "FuncB")]
        self.assertEqual(e["reason"], "the reason wraps over two lines, with a (nested) note | and a pipe")

    def test_multi_name_entry_gives_one_row_per_name(self):
        rows = self.rows()
        self.assertEqual(rows[("src/b.c", "FuncC")]["reason"], "shared reason")
        self.assertEqual(rows[("src/b.c", "Unlisted")]["reason"], "shared reason")
        self.assertEqual(len(rows), 4)  # ignored source sets add nothing

    def test_missing_reason_detected(self):
        self.write_kt("game/src/commonMain/kotlin/fx/Bad.kt", BAD_KT)
        code, out, _ = self.run_tool("--write")
        self.assertEqual(code, 0)
        self.assertIn("missing-reason: 1", out)
        code, out, err = self.run_tool("--check")
        self.assertEqual(code, 1)
        self.assertIn("without", err)

    def test_summary_systems_and_unknown_names(self):
        _, out, _ = self.run_tool("--write")
        self.assertIn("Unverified (Transcribed) entries: 4", out)
        self.assertIn("alpha: 2", out)
        self.assertIn("not-in-decomp-list: 1", out)
        self.assertIn("src/b.c: Unlisted", out)
        self.assertIn("missing-reason: 0", out)

    def test_check_passes_then_detects_drift(self):
        self.assertEqual(self.run_tool("--write")[0], 0)
        doc = (self.root / "docs/plans/UNVERIFIED.md").read_text()
        self.assertIn("## src/a.c (2)", doc)
        self.assertIn("with a (nested) note \\| and a pipe", doc)
        self.assertEqual(self.run_tool("--check")[0], 0)
        self.write_kt("game/src/commonMain/kotlin/fx/More.kt",
                      "/** Transcribed from src/a.c: FuncA (not verified: another reason). */\n")
        code, _, err = self.run_tool("--check")
        self.assertEqual(code, 1)
        self.assertIn("Out-of-date", err)


if __name__ == "__main__":
    unittest.main()
