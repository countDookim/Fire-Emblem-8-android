"""Self-test for tools/progress.py on a tiny fake repo (run: python3 -m unittest discover tools/tests).

Guards the brief's "never inflate progress" rule: only a function that is BOTH marked
`Ported from <file>: <Func>` in main sources AND covered by a PASSED `@VerifiesPort` test counts.
Skipped, failed and errored tests, unmarked functions, `Partially ported` mentions and names outside
the reference list must never count.
"""
from __future__ import annotations

import contextlib
import io
import json
import sys
import tempfile
import textwrap
import unittest
from unittest import mock
from pathlib import Path

sys.path.insert(0, str(Path(__file__).resolve().parent.parent))
import progress  # noqa: E402

REFERENCE = """\
name\tfile\tline\tkind\tsystem\taddress
FuncPass\tsrc/a.c\t1\tc\talpha\t0x08000000
FuncSkip\tsrc/a.c\t2\tc\talpha\t0x08000010
FuncFail\tsrc/a.c\t3\tc\talpha\t0x08000020
FuncError\tsrc/b.c\t1\tc\tbeta\t0x08000030
FuncNoTest\tsrc/b.c\t2\tc\tbeta\t0x08000040
FuncPartial\tsrc/b.c\t3\tc\tbeta\t0x08000050
FuncTestOnly\tsrc/b.c\t4\tc\tbeta\t0x08000060
FuncPass2\tsrc/a.c\t4\tc\talpha\t0x08000070
LinkFn\tsrc/sio_x.c\t1\tc\tlink\t0x08000080
LinkKept\tsrc/sio_x.c\t2\tc\tlink\t0x08000090
DbgFn\tsrc/uidebug.c\t1\tc\tmenus\t0x080000a0
"""

MAIN_KT = """\
package fx

/** Ported from src/a.c: FuncPass, FuncPass2 */
fun a() {}
/** Ported from src/a.c: FuncSkip */
fun b() {}
/** Ported from src/a.c: FuncFail */
fun c() {}
/** Ported from src/b.c: FuncError */
fun d() {}
/** Ported from src/b.c: FuncNoTest */
fun e() {}
/** Partially ported from src/b.c: FuncPartial */
fun f() {}
/** Ported from src/sio_x.c: LinkFn */
fun h() {}
/** Ported from src/sio_x.c: LinkKept */
fun i() {}
/** Ported from src/zz.c: NotInReference */
fun g() {}
"""

TEST_KT = """\
package fx

class FxTest {
    @VerifiesPort("FuncPass", "FuncPass2")
    @Test fun passes() {}
    @VerifiesPort("FuncSkip")
    @Test fun skipped() {}
    @VerifiesPort("FuncFail")
    @Test fun fails() {}
    @VerifiesPort("FuncError")
    @Test fun errors() {}
    @VerifiesPort("FuncPartial")
    @Test fun partialPasses() {}
    @VerifiesPort("FuncTestOnly")
    @Test fun testOnlyPasses() {}
    @VerifiesPort("LinkFn")
    @Test fun linkPasses() {}
    @VerifiesPort("LinkKept")
    @Test fun linkKeptPasses() {}
    @VerifiesPort("src/x.c: Bogus", "NoSuchFunc")
    @Test fun bogusNames() {}
}
"""

JUNIT_XML = """\
<?xml version="1.0" encoding="UTF-8"?>
<testsuite name="fx.FxTest" tests="9" skipped="1" failures="1" errors="1">
  <testcase name="passes()" classname="fx.FxTest" time="0.0"/>
  <testcase name="skipped()" classname="fx.FxTest" time="0.0"><skipped/></testcase>
  <testcase name="fails()" classname="fx.FxTest" time="0.0"><failure message="x"/></testcase>
  <testcase name="errors()" classname="fx.FxTest" time="0.0"><error message="x"/></testcase>
  <testcase name="partialPasses()" classname="fx.FxTest" time="0.0"/>
  <testcase name="testOnlyPasses()" classname="fx.FxTest" time="0.0"/>
  <testcase name="linkPasses()" classname="fx.FxTest" time="0.0"/>
  <testcase name="linkKeptPasses()" classname="fx.FxTest" time="0.0"/>
  <testcase name="bogusNames()" classname="fx.FxTest" time="0.0"/>
</testsuite>
"""


def write(root: Path, rel: str, text: str) -> None:
    p = root / rel
    p.parent.mkdir(parents=True, exist_ok=True)
    p.write_text(text)


class ProgressFixtureTest(unittest.TestCase):
    def setUp(self) -> None:
        self._tmp = tempfile.TemporaryDirectory()
        self.root = Path(self._tmp.name)
        write(self.root, "reference/decomp_functions.tsv", REFERENCE)
        write(self.root, "reference/DECOMP_VERSION", "fake-decomp 0000000\n")
        write(self.root, "README.md", "# Fake\n\n<!-- progress:start -->\nold\n<!-- progress:end -->\n\ntail\n")
        write(self.root, "mod/src/main/kotlin/fx/Main.kt", MAIN_KT)
        write(self.root, "mod/src/test/kotlin/fx/FxTest.kt", TEST_KT)
        write(self.root, "mod/build/test-results/test/TEST-fx.FxTest.xml", JUNIT_XML)

    def tearDown(self) -> None:
        self._tmp.cleanup()

    def run_progress(self, *args: str) -> str:
        out, err = io.StringIO(), io.StringIO()
        with contextlib.redirect_stdout(out), contextlib.redirect_stderr(err):
            self.assertEqual(progress.main(["--root", str(self.root), *args]), 0)
        return out.getvalue() + err.getvalue()

    def port_map(self) -> dict:
        self.run_progress("--write")
        return json.loads((self.root / "docs" / "port_map.json").read_text())

    def test_only_passed_and_marked_functions_count(self) -> None:
        pm = self.port_map()
        verified = sorted(n for n, f in pm["functions"].items() if f["status"] == "verified" and f["in_scope"])
        self.assertEqual(verified, ["FuncPass", "FuncPass2"])
        self.assertEqual(pm["summary"], {"total": 11, "in_scope_total": 8, "out_of_scope": 3, "verified": 2,
                                      "implemented_unverified": 4})

    def test_skipped_failed_errored_are_implemented_not_verified(self) -> None:
        funcs = self.port_map()["functions"]
        for n in ("FuncSkip", "FuncFail", "FuncError", "FuncNoTest"):
            self.assertEqual(funcs[n]["status"], "implemented", n)
            self.assertEqual(funcs[n]["tests"], [], n)

    def test_partial_and_test_only_never_appear(self) -> None:
        funcs = self.port_map()["functions"]
        self.assertNotIn("FuncPartial", funcs)  # "Partially ported" is ignored even with a passing test
        self.assertNotIn("FuncTestOnly", funcs)  # a passing test without a Ported-from KDoc is not a port

    def test_unknown_names_warn_and_do_not_count(self) -> None:
        out = self.run_progress()
        self.assertIn("NotInReference", out)
        self.assertIn("(2 / 8 functions in scope)", out)

    def test_no_results_means_nothing_verified(self) -> None:
        (self.root / "mod/build/test-results/test/TEST-fx.FxTest.xml").unlink()
        self.assertEqual(self.port_map()["summary"]["verified"], 0)

    def test_readme_block_replaced_and_bar_floors(self) -> None:
        self.run_progress("--write")
        text = (self.root / "README.md").read_text()
        self.assertNotIn("old", text)
        self.assertTrue(text.rstrip().endswith("tail"))
        self.assertIn("`█████░░░░░░░░░░░░░░░ 25% (2 / 8 functions in scope)`", text)
        self.assertIn("| alpha | 2 | 4 | 4 | 50.0 |", text)
        self.assertIn("| beta | 0 | 2 | 4 | 0.0 |", text)

    def test_out_of_scope_not_in_denominator_or_percentage(self) -> None:
        out = self.run_progress()
        self.assertIn("(2 / 8 functions in scope)", out)  # LinkFn verified+ported but not counted
        self.assertIn("out-of-scope verified (not counted): LinkFn", out)
        pm = self.port_map()
        self.assertEqual(pm["functions"]["LinkFn"]["in_scope"], False)
        self.assertEqual(pm["functions"]["FuncPass"]["in_scope"], True)

    def test_out_of_scope_row_and_systems(self) -> None:
        self.run_progress("--write")
        text = (self.root / "README.md").read_text()
        self.assertIn("3 functions (link-cable play, debug menus) are out of scope and not counted", text)
        self.assertIn("| out of scope (link, debug menus) | 2 | 2 | 3 | |", text)
        self.assertNotIn("| link |", text)  # whole system out of scope: no per-system row

    def test_keep_in_scope_overrides(self) -> None:
        with mock.patch.object(progress, "KEEP_IN_SCOPE", frozenset({"LinkKept"})):
            pm = self.port_map()
            out = self.run_progress()
        self.assertEqual(pm["summary"]["in_scope_total"], 9)
        self.assertEqual(pm["summary"]["out_of_scope"], 2)
        self.assertEqual(pm["summary"]["verified"], 3)  # LinkKept counts again
        self.assertIn("(3 / 9 functions in scope)", out)

    def test_unknown_verifies_names_listed(self) -> None:
        out = self.run_progress()
        self.assertIn("unknown-verifies-names: 2", out)
        self.assertIn("fx.FxTest#bogusNames: src/x.c: Bogus", out)
        self.assertIn("fx.FxTest#bogusNames: NoSuchFunc", out)

    def test_percentage_text_is_floored(self) -> None:
        self.assertEqual(progress.pct_text(750, 7732), "9.6")  # 9.6999…, never "9.7" or "10"
        self.assertEqual(progress.pct_text(2, 8), "25")
        self.assertEqual(progress.pct_text(1, 8352), "0.01")
        self.assertEqual(progress.pct_text(0, 8352), "0")
        self.assertEqual(progress.pct_text(0, 0), "0")

    def test_bar_never_rounds_up(self) -> None:
        self.assertEqual(progress.bar(0, 10), "░" * 20)
        self.assertEqual(progress.bar(99, 100).count("█"), 19)
        self.assertEqual(progress.bar(1, 8352).count("█"), 0)


if __name__ == "__main__":
    unittest.main()
