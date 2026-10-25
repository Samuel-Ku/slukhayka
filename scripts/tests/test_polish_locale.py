"""The `pl` resource qualifier is POLISH — and the Material3 override stays in sync.

HISTORY THIS FILE PROTECTS.

`values-pl/strings.xml` began as a UKRAINIAN override (#371): the app UI was
Ukrainian, and an override in `pl` stopped TalkBack from voicing Material3's own
Polish strings on a Polish device. #1385 caught it when it had also become a
partial translation — half Ukrainian, half Polish on one screen, which is worse
than a single language.

#1387–#1389 finished the Polish translation and turned the language switch ON.
The premise of the old invariant is gone: a Ukrainian value in the `pl`
qualifier would now be the #1385 defect itself (Polish UI, Ukrainian
announcements). So the same file keeps only the five Material3 overrides, in
the SAME Polish sentences Compose Material3 ships, and this guard pins:

* every value under `values-pl/` is Polish (Cyrillic = drifted translation),
  except the two endonyms deliberately shown in their own script;
* `values-pl/strings.xml` contains exactly the five Material3 keys and no app
  string — the override file must not re-grow into a partial translation;
* its values equal Material3 1.3.2's Polish wording, so a library upgrade that
  changes them is noticed instead of silently drifting;
* no key is defined twice in the `pl` qualifier (aapt would reject the build,
  and the app files must own their keys alone).
"""

import re
import unittest
from pathlib import Path

RES = Path(__file__).resolve().parents[2] / "app/src/main/res"
PL_DIR = RES / "values-pl"
PL_OVERRIDES = PL_DIR / "strings.xml"

# Compose Material3 1.3.2, `material3-release.aar` → res/values-pl/values-pl.xml.
MATERIAL3_POLISH = {
    "m3c_bottom_sheet_collapse_description": "Zwiń planszę dolną",
    "m3c_bottom_sheet_dismiss_description": "Zamknij planszę dolną",
    "m3c_bottom_sheet_drag_handle_description": "Uchwyt do przeciągania",
    "m3c_bottom_sheet_expand_description": "Rozwiń planszę dolną",
    "m3c_bottom_sheet_pane_title": "Plansza dolna",
}

# Endonyms: the interface-language screen shows each language in its own script.
ENDONYMS = {"app_locale_ukrainian", "content_language_uk"}

_ENTRY = re.compile(r'<(string|plurals)\s+name="([^"]+)"[^>]*>(.*?)</\1>', re.S)
_CYRILLIC = re.compile("[А-Яа-яІіЇїЄєҐґ]")


def _entries(path: Path) -> dict[str, str]:
    return dict((m.group(2), m.group(3)) for m in _ENTRY.finditer(path.read_text(encoding="utf-8")))


class PolishLocaleTest(unittest.TestCase):
    def test_every_value_in_the_pl_qualifier_is_polish(self):
        offenders = []
        for path in sorted(PL_DIR.glob("*.xml")):
            for name, value in _entries(path).items():
                if name in ENDONYMS:
                    continue
                if _CYRILLIC.search(value):
                    offenders.append(f"{path.name}:{name}")
        self.assertEqual(
            [], offenders,
            "these `values-pl` values still contain Cyrillic — a Ukrainian copy in the Polish "
            "qualifier is the #1385 mixed-language defect. Translate them (app strings go to "
            "strings_app.xml/strings_rest.xml; Material3 overrides to strings.xml).",
        )

    def test_the_override_file_is_only_material3(self):
        self.assertTrue(PL_OVERRIDES.is_file(), "values-pl/strings.xml зник")
        names = set(_entries(PL_OVERRIDES))
        self.assertEqual(
            set(MATERIAL3_POLISH), names,
            "values-pl/strings.xml is the Material3 override file — app strings belong in "
            "strings_app.xml/strings_rest.xml, and an extra key here would make one file of the "
            "`pl` qualifier own a translation it should not.",
        )

    def test_material3_overrides_match_the_library_polish(self):
        actual = _entries(PL_OVERRIDES)
        drifted = {k: (MATERIAL3_POLISH[k], actual.get(k)) for k in MATERIAL3_POLISH
                   if actual.get(k) != MATERIAL3_POLISH[k]}
        self.assertEqual(
            {}, drifted,
            "the Material3 Polish override drifted from Compose Material3 1.3.2's own wording — "
            "update the constant in this test together with the library upgrade, or fix the value.",
        )

    def test_no_key_is_defined_twice_in_the_pl_qualifier(self):
        owners: dict[str, list[str]] = {}
        for path in sorted(PL_DIR.glob("*.xml")):
            for name in _entries(path):
                owners.setdefault(name, []).append(path.name)
        clashes = {name: files for name, files in owners.items() if len(files) > 1}
        self.assertEqual(
            {}, clashes,
            "a key defined twice in the `pl` qualifier is an aapt error, and nothing says which "
            "file the app would speak.",
        )


if __name__ == "__main__":
    unittest.main()
