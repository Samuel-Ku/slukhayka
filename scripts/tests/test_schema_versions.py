"""Keep exported Room schema labels consistent with their metadata."""
import json
from pathlib import Path
import unittest


class SchemaVersionMetadataTest(unittest.TestCase):
    def test_exported_schema_versions_match_their_filenames(self):
        root = Path(__file__).resolve().parents[2]
        schemas = root / "app/schemas/com.slukhayka.audiobooks.data.db.AudiobookDatabase"
        paths = sorted(path for path in schemas.glob("*.json") if path.stem.isdecimal())
        self.assertTrue(paths, "No exported Room schemas found")
        mismatches = []
        for path in paths:
            version = json.loads(path.read_text(encoding="utf-8"))["database"]["version"]
            if version != int(path.stem):
                mismatches.append((path.name, version))
        self.assertEqual([], mismatches, "Room schema filename and database.version differ")
