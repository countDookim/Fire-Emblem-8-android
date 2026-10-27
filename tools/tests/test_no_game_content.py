"""M4-05: the game-content guard passes a clean APK-like archive and fails on planted game content."""
import contextlib
import io
import pathlib
import sys
import tempfile
import unittest
import zipfile

sys.path.insert(0, str(pathlib.Path(__file__).resolve().parents[1]))
import check_no_game_content as guard  # noqa: E402


def fake_rom():
    rom = bytearray(0x100000)
    rom[0xA0:0xAC] = b"FIREEMBLEM2E"
    rom[0xB2] = 0x96
    rom[0xBD] = (-sum(rom[0xA0:0xBD]) - 0x19) & 0xFF
    return bytes(rom)


class NoGameContentTest(unittest.TestCase):
    def setUp(self):
        self.tmp = tempfile.TemporaryDirectory()
        self.dir = pathlib.Path(self.tmp.name)

    def tearDown(self):
        self.tmp.cleanup()

    def apk(self, extra):
        p = self.dir / "app.apk"
        with zipfile.ZipFile(p, "w") as z:
            z.writestr("classes.dex", b"dex\n035" + bytes(200))
            z.writestr("res/mipmap-hdpi/ic_launcher.png", b"\x89PNG launcher icon" + bytes(100))
            z.writestr("AndroidManifest.xml", b"<manifest/>")
            for name, data in extra.items():
                z.writestr(name, data)
        return p

    def run_guard(self, *args):
        with contextlib.redirect_stdout(io.StringIO()) as out:
            code = guard.main([str(a) for a in args])
        return code, out.getvalue()

    def test_clean_apk_passes(self):
        self.assertEqual(0, self.run_guard(self.apk({}))[0])

    def test_planted_asset_fails(self):
        code, out = self.run_guard(self.apk({"assets/ui/intro_nintendo.png": b"\x89PNG" + bytes(100)}))
        self.assertEqual(1, code)
        self.assertIn("intro_nintendo.png", out)

    def test_planted_rom_fails(self):
        self.assertEqual(1, self.run_guard(self.apk({"assets/game.dat": fake_rom()}))[0])
        loose = self.dir / "rom.bin"
        loose.write_bytes(fake_rom())
        self.assertEqual(1, self.run_guard(loose)[0])

    def test_copy_of_extracted_asset_fails(self):
        extracted = self.dir / "extracted" / "music"
        extracted.mkdir(parents=True)
        blob = bytes(range(256)) * 2
        (extracted / "song01.mid").write_bytes(blob)
        apk = self.apk({"lib/arm64-v8a/data.so": blob})
        self.assertEqual(0, self.run_guard(apk)[0])
        code, out = self.run_guard("--extracted", self.dir / "extracted", apk)
        self.assertEqual(1, code)
        self.assertIn("song01.mid", out)


if __name__ == "__main__":
    unittest.main()
