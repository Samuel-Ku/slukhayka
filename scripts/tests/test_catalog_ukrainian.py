"""Ukrainian catalogue parsers follow the Android adapters' title/author rules."""
import importlib.util
from pathlib import Path
import sys
import unittest
from unittest.mock import patch

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
        html = ('<meta property="og:title" content="«Аптекарка» Інґрід Ноль ⭐️ fb2, epub">'
                '<audio><source src="https://cdn.example.org/book.mp3?token=test"></audio>')
        self.assertEqual(("«Аптекарка»", "Інґрід Ноль"), export.parse_chitaka(html)[:2])

    def test_chitaka_rejects_text_books_and_commented_or_unrelated_audio(self):
        meta = '<meta property="og:title" content="«Аптекарка» Інґрід Ноль ⭐️ fb2, epub">'
        for extra in ("", '<a href="book.mp3">download</a>',
                      '<!-- <audio src="book.mp3"></audio> -->', '<audio src="book.epub"></audio>'):
            with self.subTest(extra=extra):
                self.assertEqual(("", "", ""), export.parse_chitaka(meta + extra))
        self.assertEqual(("«Аптекарка»", "Інґрід Ноль"),
                         export.parse_chitaka(meta + '<audio src="book.mp3"></audio>')[:2])

    def test_security_policy_failure_is_not_silently_treated_as_an_absent_book(self):
        with patch.object(export, "fetch", side_effect=export.CatalogSecurityError("blocked URL")):
            with self.assertRaises(export.CatalogSecurityError):
                export.sample_page("https://chitaka.com.ua/knigi/book", export.parse_chitaka)

    def test_chytaylo_accepts_only_declared_audiobooks(self):
        audio = ('<script type="application/ld+json">'
                 '{"@type":"Book","name":"48 законів влади","author":{"@type":"Person",'
                 '"name":"Роберт Грін"},"bookFormat":["AudiobookFormat"],"description":"Опис"}</script>')
        text = ('<script type="application/ld+json">'
                '{"@type":"Book","name":"48 законів влади","author":{"@type":"Person",'
                '"name":"Роберт Грін"},"bookFormat":["EBook"]}</script>')
        self.assertEqual(("48 законів влади", "Роберт Грін", "Опис"), export.parse_chytaylo(audio))
        self.assertEqual(("", "", ""), export.parse_chytaylo(text))

    def test_knigionline_requires_the_audio_player_and_reads_words(self):
        html = ('<div data-tracks-url="/playlist.json"></div>'
                '<meta property="og:title" content="Аудіокнига «Природа» Ольга Кобилянська">')
        text = '<meta property="og:title" content="Аудіокнига «Природа» Ольга Кобилянська">'
        self.assertEqual(("«Природа»", "Ольга Кобилянська"), export.parse_knigi(html)[:2])
        self.assertEqual(("", "", ""), export.parse_knigi(text))
        self.assertEqual(["Ольга Кобилянська"], export.words("['Ольга Кобилянська']"))
        self.assertEqual(["A", "B"], export.words(["A", "B"]))


if __name__ == "__main__":
    unittest.main()
