"""The LibriVox export helper: public fields only, bounded text, one key source."""
import importlib.util
from pathlib import Path
import sys
import unittest
from unittest.mock import patch

RECOMMEND = Path(__file__).parents[1] / "recommend"
sys.path.insert(0, str(RECOMMEND))
SPEC = importlib.util.spec_from_file_location("catalog_from_librivox",
                                              RECOMMEND / "catalog_from_librivox.py")
export = importlib.util.module_from_spec(SPEC)
SPEC.loader.exec_module(export)


class LibriVoxExportTest(unittest.TestCase):
    def test_first_author_is_the_apps_first_author(self):
        self.assertEqual("Alexandre Dumas", export.first_author(
            {"authors": [{"first_name": "Alexandre", "last_name": "Dumas"},
                         {"first_name": "Other", "last_name": "Person"}]}))
        self.assertEqual("", export.first_author({"authors": []}))
        self.assertEqual("", export.first_author({}))

    def test_description_is_plain_text_and_bounded(self):
        self.assertEqual("A & B", export.clean("<i>A</i> &amp; B"))
        text = export.work_text("Title", "Author", "English", "<p>" + "x" * 10_000 + "</p>")
        self.assertLessEqual(len(text), export.MAX_TEXT)
        self.assertTrue(text.startswith("Title. Author. English."))

    def test_security_policy_failure_is_not_retried_or_hidden(self):
        with patch.object(export, "fetch_text", side_effect=export.CatalogSecurityError("blocked DNS")) as fetch, \
                patch.object(export.time, "sleep") as sleep:
            with self.assertRaises(export.CatalogSecurityError):
                export.fetch(0, 500)
            fetch.assert_called_once()
            sleep.assert_not_called()

    def test_blank_author_still_yields_no_guessed_key(self):
        self.assertEqual("", export.merge_key("A Title", ""))


if __name__ == "__main__":
    unittest.main()
