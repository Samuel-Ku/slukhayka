---
name: coverage-to-80-via-hybrid-stack
label: wayfinder:map
created: 2026-07-30
status: charting
tracker: github-issues
map_issue: https://github.com/Samuel-Ku/slukhayka/issues/2
---

# Wayfinder Mirror — `coverage-to-80-via-hybrid-stack`

> **Canonical artifact lives on the GitHub issue tracker.**
> This file is a local pointer so a checkout without network access
> still shows the destination and current frontier.
>
> Map issue: [#2](https://github.com/Samuel-Ku/slukhayka/issues/2)

## Destination

Код покритий тестами так, що `gradlew koverXmlReport` показує ≥ 80% instructions
і ≥ 70% branches для всього `app/src/main`, а UI smoke-шлях
(BookDetail → chapter row → натиснув → Player → isPlaying) проходить на
емуляторі з golden-record скріншотом, який CI фіксирує.

Карта вважається закінченою, коли:

1. Kover-verified gate в GitHub Actions падає на PR без тестів.
2. Compose snapshot tests покривають усі 6 екранів (Library, Explore,
   BookDetail, Player, FourReadWeb, MiniPlayer).
3. Espresso-сценарій playable audio playback проходить на
   `Pixel_API_34` емуляторі.
4. Threshold 80/70 закріплений у `app/build.gradle.kts`, override
   тільки через property.

## Notes

Domain: Android (Kotlin 2.2.10, Compose Material 3, Media3 1.3.1,
Room 5-entity). Стек вже існує, тести мають підключитись без refactor.

Skills кожна сесія має консультувати:

- Kotlin rule `kotlin-testing` (fakes over mocks, runTest, Turbine)
- `web/testing.md` (snapshot testing для Compose)
- `kotlin/patterns.md` (state machines, sealed UiState)

Standing preferences:

- TDD: RED → GREEN → IMPROVE. Без тестів немає PR.
- Kotlin rule: fakes > Mockito. Виняток — коли Media3/ExoPlayer
  губиться в марнославній реалізації.
- Кожен тест — `runTest { }` або `testTag` для Compose.
- Покриття рахуємо instructions + branches, не тільки lines.

## Decisions so far

- **Test runner stack** (resolved in chart session Q2) — hybrid:
  JVM JUnit5 + Robolectric для AudioPlayerManager та репозиторіїв;
  Paparazzi/Roborazzi для Compose snapshot; Espresso на емуляторі
  виключно для audio playback smoke.
- **Audio test scope** (resolved in chart session Q3) — один
  емуляторний сценарій: chapter row → натиснув → Player відкрився →
  через 3 с `playerState.isPlaying == true`. Golden-record
  скріншот закомічений, assertion через compose-test API. Не load
  мережі в тестах — тільки локальний MP3 з `src/test/resources/`.
- **Coverage tooling + threshold** (resolved in chart session Q4) —
  Kover (JetBrains), 80% instructions / 70% branches, fail
  GitHub Actions job якщо нижче. Override — `kover.threshold` Gradle
  property.

## Tickets (children of map #2)

- #3 — [Ticket #1 — kover-and-coverage-gate](https://github.com/Samuel-Ku/slukhayka/issues/3) `wayfinder:task` — frontier.
- #4 — [Ticket #2 — fake-player-engine](https://github.com/Samuel-Ku/slukhayka/issues/4) `wayfinder:prototype` — frontier.
- #5 — [Ticket #3 — compose-snapshot-infra](https://github.com/Samuel-Ku/slukhayka/issues/5) `wayfinder:research` — frontier.
- #6 — [Ticket #4 — jvm-test-fixtures](https://github.com/Samuel-Ku/slukhayka/issues/6) `wayfinder:research` — frontier.
- #7 — [Ticket #5 — emulator-audio-scenario](https://github.com/Samuel-Ku/slukhayka/issues/7) `wayfinder:task` — **blocked by #3, #5, #6**.

Fog is in the GitHub map body. Local ticket files
(`docs/wayfinder/tickets/*.md`) were removed once the GitHub
issues became the canonical tracker — keep only this mirror.

## Out of scope

- **Split AudiobookRepository (CR-001)** з аудиту
  `2026-07-30-static-and-agents.md` — окремий wayfinder effort.
- **Accessibility WCAG 2.2 (A11Y-001)** — окремий effort.
- **Push main до origin** — операційна, не effort.
- **Repository fixture from production seed** — обрали factory.
