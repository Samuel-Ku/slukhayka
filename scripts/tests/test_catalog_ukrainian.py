"""Ukrainian catalogue parsers follow the Android adapters' title/author rules."""
import importlib.util
from pathlib import Path
import sys
import unittest

RECOMMEND = Path(__file__).parents[1] / "recommend"
sys.path.insert(0, str(RECOMMEND))
SPEC = importlib.util.spec_from_file_location("catalog_from_ukrainian",
                                              RECOMMEND / "catalog_from_ukrainian.py")
export = importlib.util.module_from_spec(SPEC)
SPEC.loader.exec_module(export)


class UkrainianExportTest(unittest.TestCase):
    def test_soundbooks_reads_the_book_jsonld(self):
        html = ('<script type="application/ld+json">'
                '{"@type":"WebPage","mainEntity":{"@type":"Book","name":"Темна матерія",'
                '"author":"Блейк Крауч","description":"Опис"}}</script>')
        self.assertEqual(("Темна матерія", "Блейк Крауч", "Опис"), export.parse_soundbooks(html))

    def test_audiobookcoua_strips_the_site_wrapper(self):
        html = ('<meta property="og:title" content="Аудиокнига 1984 - Джордж Оруэлл '
                'скачать, слушать онлайн - Аудиокниги на украинском языке">')
        self.assertEqual(("1984", "Джордж Оруэлл"), export.parse_audiobookcoua(html)[:2])

    def test_chitaka_reads_the_guillemet_pair(self):
        html = '<meta property="og:title" content="«Аптекарка» Інґрід Ноль ⭐️ fb2, epub">'
        self.assertEqual(("«Аптекарка»", "Інґрід Ноль"), export.parse_chitaka(html)[:2])

    def test_chytaylo_reads_the_book_jsonld(self):
        html = ('<script type="application/ld+json">'
                '{"@type":"Book","name":"48 законів влади","author":{"@type":"Person",'
                '"name":"Роберт Грін"},"description":"Опис"}</script>')
        self.assertEqual(("48 законів влади", "Роберт Грін", "Опис"), export.parse_chytaylo(html))

    def test_knigionline_and_sluhay_words(self):
        html = '<meta property="og:title" content="Аудіокнига «Природа» Ольга Кобилянська">'
        self.assertEqual(("«Природа»", "Ольга Кобилянська"), export.parse_knigi(html)[:2])
        self.assertEqual(["Ольга Кобилянська"], export.words("['Ольга Кобилянська']"))
        self.assertEqual(["A", "B"], export.words(["A", "B"]))


if __name__ == "__main__":
    unittest.main()
