"""commonMain sources of Kotlin Multiplatform modules must use only the Kotlin stdlib (ADR 0013).

Flags JVM-only API in code: java.*/javax.* imports and fully-qualified uses, System.*, String.format and
.format(...), ::class.java and Charsets.*. Comments, KDoc and string literals are ignored.
"""
import pathlib
import re

ROOT = pathlib.Path(__file__).resolve().parents[2]

JVM_ONLY = re.compile(
    r"(?<![\w.])javax?\.[A-Za-z]"  # import java.io.X and java.io.X() alike
    r"|(?<![\w.])System\."
    r"|\bString\.format\b"
    r"|\.format\("
    r"|::class\.java\b"
    r"|(?<![\w.])Charsets\."
)
STRING_LITERAL = re.compile(r'"(?:\\.|[^"\\])*"')
LINE_COMMENT = re.compile(r"//.*")


def code_of(line: str) -> str:
    """Returns [line] without string literals and comments ('' for KDoc/block-comment lines)."""
    if line.lstrip().startswith(("*", "/*")):
        return ""
    return LINE_COMMENT.sub("", STRING_LITERAL.sub('""', line))


def jvm_only_uses(text: str) -> list[tuple[int, str]]:
    return [(n, line.strip()) for n, line in enumerate(text.splitlines(), 1) if JVM_ONLY.search(code_of(line))]


def test_guard_detects_known_violations():
    for bad in (
        "import java.io.ByteArrayOutputStream",
        "val out = java.io.ByteArrayOutputStream()",
        "val x = javax.imageio.ImageIO.read(f)",
        "System.currentTimeMillis()",
        'String.format("%02X", v)',
        '"%02X".format(v)',
        "val c = Foo::class.java",
        "s.toByteArray(Charsets.UTF_8)",
    ):
        assert jvm_only_uses(bad), bad


def test_guard_ignores_comments_and_strings():
    for ok in (
        " * `java.util.zip.CRC32` replacement",
        "/** avoids `javax.imageio` */",
        "// System.exit(0)",
        'val s = "java.io.File"',
        "val t = x // String.format(y)",
        "val m = kotlin.math.max(a, b)",
        "fun hex(v: Int) = v.toString(16)",
    ):
        assert not jvm_only_uses(ok), ok


def test_no_jvm_only_api_in_common_main():
    offenders = []
    for f in ROOT.glob("**/src/commonMain/**/*.kt"):
        rel = f.relative_to(ROOT)
        if "build" in rel.parts:
            continue
        for n, line in jvm_only_uses(f.read_text(encoding="utf-8")):
            offenders.append(f"{rel.as_posix()}:{n}: {line}")
    assert not offenders, "JVM-only API in commonMain (ADR 0013):\n" + "\n".join(offenders)
