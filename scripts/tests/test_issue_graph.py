"""Тести графа залежностей тікетів (#1369).

Головний тест — URL-форма: `## Blocked by` із посиланням
`https://github.com/Samuel-Ku/slukhayka/issues/1240` не бачиться grep-ом
`#[0-9]`, і саме через це #1285 і #1241 були названі вільними, маючи блокер.
"""

from __future__ import annotations

import sys
import unittest
from pathlib import Path

sys.path.insert(0, str(Path(__file__).resolve().parent.parent))

import issue_graph as graph  # noqa: E402


class ExtractBlockedByTest(unittest.TestCase):
    """Секція читається, а не шукається по символу `#`."""

    def test_short_form(self) -> None:
        body = "## What to build\n\nщось\n\n## Blocked by\n\n- #1240 — причина\n"
        self.assertEqual(graph.extract_blocked_by(body), [1240])

    def test_url_form_is_the_regression(self) -> None:
        """URL-форма: grep по `#NNNN` її не бачить — тому механізм читає секцію."""
        body = (
            "## Blocked by\n\n"
            "- https://github.com/Samuel-Ku/slukhayka/issues/1240 — причина\n"
        )
        self.assertEqual(graph.extract_blocked_by(body), [1240])

    def test_both_forms_together(self) -> None:
        body = "## Blocked by\n\n- #1185\n- https://github.com/o/r/issues/1188\n"
        self.assertEqual(graph.extract_blocked_by(body), [1185, 1188])

    def test_references_outside_the_section_do_not_count(self) -> None:
        """`#` у прозі `What to build` — контекст, а не блокада.

        Це рівно та помилка, яку я зробив руками в #1240: узяв #1185 і #1230
        з усього тіла, а секція казала «Немає».
        """
        body = "## What to build\n\nбачити #1185 і #1230\n\n## Blocked by\n\nНемає.\n"
        self.assertEqual(graph.extract_blocked_by(body), [])

    def test_section_ends_at_next_heading(self) -> None:
        body = "## Blocked by\n\n- #1240\n\n## Acceptance criteria\n\n- #9999\n"
        self.assertEqual(graph.extract_blocked_by(body), [1240])

    def test_no_blockers_verdict_with_prose_refs_is_empty(self) -> None:
        """«Немає — можна починати одразу» + номери суміжної роботи ≠ блокери.

        Регресія з огляду #1369: #1242 і #1283 кажуть «Немає …», а далі в
        прозі стоять #1185/#1188 і #1240/#1241/#620 як «суміжна робота для
        узгодження». Парсер витягував їх як блокери — тобто 2 з 8 вердиктів
        були хибно «заблокований», інверсія того класу, проти якого механізм
        існує.
        """
        body = (
            "## Blocked by\n\n"
            "Немає — можна починати одразу. #1185 і #1188 — суміжна робота "
            "для узгодження, а не технічні блокери цього зрізу.\n"
        )
        self.assertEqual(graph.extract_blocked_by(body), [])

    def test_a_real_list_after_heading_is_still_a_list(self) -> None:
        """«Немає» всередині пункту списку не робить усю секцію порожньою."""
        body = "## Blocked by\n\n- #1240 — можна починати, Немає перешкод\n"
        self.assertEqual(graph.extract_blocked_by(body), [1240])

    def test_no_section_is_empty(self) -> None:
        self.assertEqual(graph.extract_blocked_by("## What to build\n\nбез блокерів\n"), [])

    def test_duplicates_are_collapsed(self) -> None:
        body = "## Blocked by\n\n- #1240\n- https://github.com/o/r/issues/1240\n"
        self.assertEqual(graph.extract_blocked_by(body), [1240])


class DepthTest(unittest.TestCase):
    """Глибина: 0 — вільно, N — N кроків до вільного, лише крізь ВІДКРИТІ."""

    def _node(self, number: int, state: str, blockers: list[int]) -> graph.Node:
        return graph.Node(number=number, state=state, blockers=list(blockers))

    def test_node_without_blockers_is_free_not_depth_one(self) -> None:
        """Регресія: база повертала 1, і вільний тікет показувався заблокованим."""
        nodes = {1284: self._node(1284, "OPEN", [])}
        graph.mark(nodes)
        self.assertEqual(nodes[1284].depth, 0)

    def test_one_blocker_gives_depth_one(self) -> None:
        nodes = {
            1284: self._node(1284, "OPEN", []),
            1285: self._node(1285, "OPEN", [1284]),
        }
        graph.mark(nodes)
        self.assertEqual(nodes[1285].depth, 1)

    def test_chain_gives_depth_two(self) -> None:
        nodes = {
            1: self._node(1, "OPEN", []),
            2: self._node(2, "OPEN", [1]),
            3: self._node(3, "OPEN", [2]),
        }
        graph.mark(nodes)
        self.assertEqual(nodes[3].depth, 2)

    def test_closed_blocker_does_not_block(self) -> None:
        """Закритий блокер не блокує: інакше закритий #605 «блокував» би все."""
        nodes = {
            605: self._node(605, "CLOSED", []),
            831: self._node(831, "OPEN", [605]),
        }
        graph.mark(nodes)
        self.assertEqual(nodes[831].depth, 0)

    def test_one_open_among_closed_still_blocks(self) -> None:
        nodes = {
            1: self._node(1, "CLOSED", []),
            2: self._node(2, "OPEN", []),
            3: self._node(3, "OPEN", [1, 2]),
        }
        graph.mark(nodes)
        self.assertEqual(nodes[3].depth, 1)

    def test_cycle_terminates_and_is_not_reported_as_free(self) -> None:
        """Цикл — дефект ДАНИХ, а не «вільно»: такий тікет не стартує ніколи.

        Механізм не висить (множина `seen` уриває рекурсію) і дає скінченне
        число, а НЕ 0. Нуль означав би «можна починати», і це було б хибно
        зелене — рівно той клас, проти якого механізм існує. Розпізнати цикл
        окремим станом — свідомо поза обсягом #1369; названо тут, а не
        замовчано.
        """
        nodes = {
            1: self._node(1, "OPEN", [2]),
            2: self._node(2, "OPEN", [1]),
        }
        graph.mark(nodes)
        self.assertGreater(nodes[1].depth, 0)
        self.assertGreater(nodes[2].depth, 0)


if __name__ == "__main__":
    unittest.main()
