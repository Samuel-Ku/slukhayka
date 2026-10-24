"""`values-pl` мусить лишатися УКРАЇНСЬКИМ, а не перетворюватись на переклад.

ІСТОРІЯ, ЯКУ ЦЕЙ ФАЙЛ ОХОРОНЯЄ.

`values-pl/strings.xml` існує не як польський переклад. Він існує, щоб на
польськомовному пристрої TalkBack не читав польською ВЛАСНІ рядки Material3
(«Zamknij arkusz», «Uchwyt do przeciągania»): кваліфікатор `pl` з українським
текстом перемагає бібліотечний польський. Це задокументовано в шапці файлу й
у коментарі `scripts/lint_baseline.py`.

ДЕФЕКТ, ЯКИЙ ЦЕ ВЖЕ ТРАПЛЯЛОСЯ (#1385). Файл був СУМІШШЮ: п'ять таких
українських оверрайдів плюс 31 СПРАВЖНІЙ польський переклад, доданий для
функцій застосунку. На польському пристрої це давало пів-український,
пів-польський екран: «Książki» поруч із «Полиці», «Mój rok w książkach»
поруч із «Збережене».

**Частково перекладена мова гірша за неперекладену:** неперекладена падає в
одну цілісну мову, а часткова показує дві одночасно.

Тому інваріант цього файлу простий і перевіряється тут: **кожне значення в
`values-pl` мусить дорівнювати українському значенню того самого ключа.** Якщо
хтось колись додасть справжній польський переклад — цей тест почервоніє й
змусить прочитати шапку файлу.

СПРАВЖНЯ ПОЛЬСЬКА — ОКРЕМИЙ ШЛЯХ (#1387/#1388/#1389), і вона мусить бути
повною, з власною стратегією кваліфікаторів, а не через цей файл-оверрайд.
"""

import re
import unittest
from pathlib import Path

RES = Path(__file__).resolve().parents[2] / "app/src/main/res"
PL = RES / "values-pl/strings.xml"
# `values/` розкиданий по кількох файлах; беремо всі, де взагалі є <string>.
BASE_GLOBS = ("values/strings.xml", "values/strings_*.xml")

_ENTRY = re.compile(r'<string name="([^"]+)"[^>]*>(.*?)</string>', re.S)


def _entries(path: Path) -> dict:
    return dict(_ENTRY.findall(path.read_text(encoding="utf-8")))


class LocaleOverridesTest(unittest.TestCase):
    def setUp(self):
        self.assertTrue(PL.is_file(), "values-pl/strings.xml зник — шапка файлу пояснює, навіщо він")

    def test_this_file_is_not_a_polish_translation(self):
        """Кожне значення мусить дорівнювати українському — інакше це переклад."""
        ukrainian = {}
        for glob in BASE_GLOBS:
            for path in sorted(RES.glob(glob)):
                ukrainian.update(_entries(path))

        offenders = []
        for name, value in _entries(PL).items():
            expected = ukrainian.get(name)
            if expected is None:
                # Ключ без українського відповідника — це вже інша проблема:
                # він не міг би нічого оверрайдити, бо нема що перекривати.
                offenders.append((name, "<немає в values/>"))
            elif value != expected:
                offenders.append((name, value))

        self.assertEqual(
            [], offenders,
            "values-pl перестав бути українським оверрайдом і став частковим "
            "перекладом — саме це дало мішанину мов у #1385. Або поверни "
            "українське значення, або роби ПОВНУ польську окремим шляхом "
            "(#1387/#1388/#1389).",
        )

    def test_the_file_is_not_empty(self):
        """Порожній файл теж пройшов би перевірку вище — тому рахуємо явно."""
        self.assertGreater(len(_entries(PL)), 0, "values-pl існує, але не містить жодного рядка")


if __name__ == "__main__":
    unittest.main()
