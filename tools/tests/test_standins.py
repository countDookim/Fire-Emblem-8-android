"""Self-test for tools/standins.py on a tiny fake repo (run: python3 -m unittest discover tools/tests)."""
from __future__ import annotations

import contextlib
import io
import sys
import tempfile
import textwrap
import unittest
from pathlib import Path

sys.path.insert(0, str(Path(__file__).resolve().parent.parent))
import standins  # noqa: E402

GOOD_KT = '''\
package fx

class Hooks {
    /** `StartMidFadeToBlack`. Stand-in until its port (M6-A). */
    public var startMidFadeToBlack: ProcFunc = ProcFunc { }

    /**
     * Stand-in save menu that ends at once. Wired by D11-wm9; other text follows.
     */
    fun saveMenu() {}

    fun run() {
        // The standin ends at once
        // and goes on over a second line.
        val x = 1
        val s = "a stand-in in a string"
        val standingOrder = 2 // not a match: standing
        val c = '"' // stand-in after a char quote
    }
}

/** Stand-in. */
/** Stand-in. */
fun dup() {}
'''


class StandinsTest(unittest.TestCase):
    def setUp(self):
        self._tmp = tempfile.TemporaryDirectory()
        self.root = Path(self._tmp.name)
        self.write_kt("game/src/commonMain/kotlin/fx/Good.kt", GOOD_KT)
        for ignored in ("game/src/test/kotlin/fx/T.kt", "game/build/src/main/kotlin/fx/B.kt",
                        ".claude/worktrees/w/game/src/main/kotlin/fx/W.kt"):
            self.write_kt(ignored, "/** Stand-in. */\nfun ignored() {}\n")

    def tearDown(self):
        self._tmp.cleanup()

    def write_kt(self, rel, text):
        p = self.root / rel
        p.parent.mkdir(parents=True, exist_ok=True)
        p.write_text(textwrap.dedent(text))

    def run_tool(self, *args):
        out, err = io.StringIO(), io.StringIO()
        with contextlib.redirect_stdout(out), contextlib.redirect_stderr(err):
            code = standins.main([*args, "--root", str(self.root)])
        return code, out.getvalue(), err.getvalue()

    def entries(self):
        return standins.scan(self.root)

    def by_decl(self, decl):
        return [e for e in self.entries() if e["decl"] == decl]

    def test_kdoc_on_hook_default(self):
        (e,) = self.by_decl("startMidFadeToBlack")
        self.assertEqual(e["text"], "Stand-in until its port (M6-A).")
        self.assertEqual(e["ref"], "M6-A")

    def test_sentence_and_plan_reference_from_same_block(self):
        (e,) = self.by_decl("saveMenu")
        self.assertEqual(e["text"], "Stand-in save menu that ends at once.")
        self.assertEqual(e["ref"], "D11-wm9")

    def test_line_comment_block_uses_following_declaration(self):
        (e,) = self.by_decl("x")
        self.assertEqual(e["text"], "The standin ends at once and goes on over a second line.")
        self.assertEqual(e["ref"], standins.UNASSIGNED)

    def test_strings_and_identifiers_ignored_char_quote_handled(self):
        texts = [e["text"] for e in self.entries()]
        self.assertFalse(any("string" in t for t in texts))
        self.assertFalse(any("standing" in t for t in texts))
        self.assertIn("stand-in after a char quote", texts)

    def test_trailing_comment_uses_enclosing_declaration(self):
        self.write_kt("game/src/commonMain/kotlin/fx/T.kt",
                      "object O {\n    fun f() {\n        g() // stand-in call\n    }\n}\n")
        (e,) = self.by_decl("f")
        self.assertEqual(e["file"], "game/src/commonMain/kotlin/fx/T.kt")

    def test_no_declaration_gives_dash(self):
        self.write_kt("game/src/commonMain/kotlin/fx/N.kt", "// stand-in at top\n")
        self.assertEqual(len(self.by_decl("-")), 1)

    def test_duplicates_merged_and_ignored_sets_skipped(self):
        self.assertEqual(len(self.by_decl("dup")), 1)
        self.assertFalse(self.by_decl("ignored"))

    def test_summary_and_order(self):
        n = len(self.entries())
        code, out, _ = self.run_tool("--write")
        self.assertEqual(code, 0)
        self.assertIn(f"Stand-in entries: {n}", out)
        self.assertIn("M6-A: 1", out)
        doc = (self.root / "docs/plans/STANDINS.md").read_text()
        self.assertLess(doc.index("## M6-A"), doc.index("## D11-wm9"))
        self.assertLess(doc.index("## D11-wm9"), doc.index("## unassigned"))

    def test_check_passes_then_detects_drift(self):
        self.assertEqual(self.run_tool("--write")[0], 0)
        self.assertEqual(self.run_tool("--check")[0], 0)  # unassigned entries are allowed
        self.write_kt("game/src/commonMain/kotlin/fx/More.kt", "/** Stand-in for M6-B. */\nfun more() {}\n")
        code, _, err = self.run_tool("--check")
        self.assertEqual(code, 1)
        self.assertIn("Out-of-date", err)


if __name__ == "__main__":
    unittest.main()
