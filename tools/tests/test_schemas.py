"""Validates schemas/*.schema.json (JSON Schema 2020-12) and extracted data against them.

Extracted data is ROM-derived, so it only exists in build dirs: testing/build/extracted/ is written by
ItemsRomTest/ClassesRomTest/CharactersRomTest/TerrainsRomTest when the ROM is available. Missing files are skipped, never counted as passing.
"""
import json
import pathlib
import unittest

try:
    import jsonschema
except ImportError:  # pragma: no cover - CI installs it
    jsonschema = None

ROOT = pathlib.Path(__file__).resolve().parents[2]
SCHEMAS = ROOT / "schemas"
EXTRACTED = ROOT / "testing" / "build" / "extracted"


def load(path):
    with open(path, encoding="utf-8") as f:
        return json.load(f)


def minimal_item(**over):
    item = {
        "id": "SWORD_IRON", "nameText": 852, "descText": 1028, "useDescText": 0, "weaponType": "SWORD",
        "attributes": ["weapon"], "statBonuses": None, "effectiveAgainst": None, "uses": 46, "might": 5,
        "hit": 90, "weight": 5, "crit": 0, "minRange": 1, "maxRange": 1, "costPerUse": 10, "rankExp": 1,
        "icon": 0, "useEffect": 0, "weaponEffect": "NONE", "weaponExp": 1,
    }
    item.update(over)
    return {"format": "gbafe.items", "game": "fe8", "version": 1, "entries": [item]}


@unittest.skipIf(jsonschema is None, "jsonschema not installed (pip install jsonschema)")
class SchemaTest(unittest.TestCase):
    def test_all_schemas_are_valid_2020_12(self):
        files = sorted(SCHEMAS.glob("*.schema.json"))
        self.assertTrue(files)
        for f in files:
            with self.subTest(f.name):
                jsonschema.Draft202012Validator.check_schema(load(f))

    def test_items_schema_accepts_and_rejects(self):
        v = jsonschema.Draft202012Validator(load(SCHEMAS / "items.schema.json"))
        v.validate(minimal_item())
        for bad in (minimal_item(might=300), minimal_item(weaponType="GUN"), minimal_item(extra=1),
                    minimal_item(attributes=["flying"]), minimal_item(id="lower")):
            with self.subTest(bad["entries"][0]):
                self.assertFalse(v.is_valid(bad))

    def test_extracted_items_are_schema_valid(self):
        path = EXTRACTED / "data" / "items.json"
        if not path.is_file():
            self.skipTest("no extracted items.json (needs the ROM; run ./gradlew :testing:test)")
        doc = load(path)
        jsonschema.Draft202012Validator(load(SCHEMAS / "items.schema.json")).validate(doc)
        self.assertEqual(206, len(doc["entries"]))

    def test_extracted_classes_are_schema_valid(self):
        path = EXTRACTED / "data" / "classes.json"
        if not path.is_file():
            self.skipTest("no extracted classes.json (needs the ROM; run ./gradlew :testing:test)")
        doc = load(path)
        jsonschema.Draft202012Validator(load(SCHEMAS / "classes.schema.json")).validate(doc)
        self.assertEqual(127, len(doc["entries"]))
        ids = [e["id"] for e in doc["entries"]]
        self.assertEqual(len(ids), len(set(ids)))

    def test_extracted_characters_are_schema_valid(self):
        path = EXTRACTED / "data" / "characters.json"
        if not path.is_file():
            self.skipTest("no extracted characters.json (needs the ROM; run ./gradlew :testing:test)")
        doc = load(path)
        jsonschema.Draft202012Validator(load(SCHEMAS / "characters.schema.json")).validate(doc)
        self.assertEqual(256, len(doc["entries"]))

    def test_extracted_terrains_are_schema_valid(self):
        path = EXTRACTED / "data" / "terrains.json"
        if not path.is_file():
            self.skipTest("no extracted terrains.json (needs the ROM; run ./gradlew :testing:test)")
        jsonschema.Draft202012Validator(load(SCHEMAS / "terrains.schema.json")).validate(load(path))


if __name__ == "__main__":
    unittest.main()
