"""commonMain sources of Kotlin Multiplatform modules must not import JVM-only packages."""
import pathlib
import re

ROOT = pathlib.Path(__file__).resolve().parents[2]
BAD = re.compile(r"^\s*import\s+(java|javax)\.", re.M)


def test_no_java_imports_in_common_main():
    offenders = []
    for f in ROOT.glob("**/src/commonMain/**/*.kt"):
        if "build" in f.relative_to(ROOT).parts:
            continue
        if BAD.search(f.read_text(encoding="utf-8")):
            offenders.append(str(f.relative_to(ROOT)))
    assert not offenders, f"java.*/javax.* imports in commonMain: {offenders}"
