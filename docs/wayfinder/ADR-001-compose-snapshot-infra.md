---
name: adr-001-compose-snapshot-infra
label: wayfinder:adr
created: 2026-07-30
status: accepted
tracker: github-issues
parent: https://github.com/Samuel-Ku/4read-audiobooks-player/issues/2
ticket: https://github.com/Samuel-Ku/4read-audiobooks-player/issues/5
---

# ADR-001 — Compose snapshot infra (Roborazzi)

> **Context.** Ticket #5 in the `coverage-to-80-via-hybrid-stack` map.
> Pick a snapshot framework, wire it up without touching the lib catalog
> entries the parallel Kover agent edited, snapshot at least one screen as
> a working exemplar, document the choice.

## Decision

**Adopt Roborazzi (takahirom.roborazzi 1.59.0) for Compose snapshot tests.**

## Why

Both Paparazzi (app.cash.paparazzi) and Roborazzi are first-class Compose
snapshot tools. Paparazzi uses LayoutLib + a JVM rasteriser; Roborazzi
records the same SurfaceView a connected device would, but routes it
through the existing Robolectric harness (no emulator, no extra plugin
configuration beyond `apply false` in the root build). The deciding
factors in the prototype phase were:

1. **Existing test code is JUnit 4 + Robolectric.** Roborazzi drops into
   the same `@RunWith(RobolectricTestRunner::class)` + `@Config(sdk=…)`
   pattern as the unit tests (#6). Paparazzi instead requires its own
   `Paparazzi` rule and a different JUnit category, doubling the matrix.
2. **Zero infra beyond `libs.versions.toml` is already wired.**
   `roborazzi`, `roborazzi-compose`, `roborazzi-junit-rule`, and the
   `io.github.takahirom.roborazzi` Gradle plugin were already added by
   the Kover-aware sibling. We did not need to touch any version.
3. **Compatible with the `TestDataFactory`.** Compose-side tests can
   pull fixtures from `dataBooks()` / `seedListeningStats()`
   directly — no Room, no ExoPlayer, no 4read fetch — which matches
   the fakes-not-mocks rule from `kotlin/testing.md`.

Paparazzi would have worked too: it is faster (no Robolectric boot per
test) and does not require native graphics. But it would have required
adding a separate Gradle plugin and rewriting every existing
Robolectric-routed test pattern. We pick the path that does not regress
work already in flight.

## Scope of this prototype

- Library: roborazzi (already in `libs.versions.toml`).
- Screen coverage: the three top-level composables that make up the
  `LibraryScreen` body — `ListeningStatsCard`, `OfflineBookItem`,
  `EmptyStateMessage` — four snapshot variants in total:
  - `library_stats_card_empty.png`
  - `library_stats_card_populated.png`
  - `library_empty_state_no_offline_books.png`
  - `library_offline_book_item.png`
- Goldens live under `app/src/test/snapshots/` and are committed so
  CI can compare against them without an emulator.

The full `LibraryScreen` is intentionally NOT snapshotted here. Its
constructor takes a concrete `MainViewModel`, and `MainViewModel`'s
`init` block calls `refreshCacheSize()` on `viewModelScope` and holds a
real `AudiobookRepository` (which auto-seeds production catalogue rows
via `autoSyncOnInit = true` and runs the 4read HTTP fetch). Plumbing
that into a Robolectric test would drag network + Room + cache I/O
into a pixel-comparison gate. A future ticket should add a state-holder
seam (e.g. `LibraryState` data class + stateless `LibraryScreen(state,
onAction)`) so the whole screen can be snapshot without the ViewModel.

## Kover interaction

`./gradlew koverXmlReport` counts JVM code under `app/src/main` as
production. Our `@Test` methods under `app/src/test` are not production,
but the composables they invoke (`ListeningStatsCard`, `OfflineBookItem`,
`EmptyStateMessage`, `StatItemCard`, `BookCoverImage`) are. Every
robosnap therefore marks those production lines as covered **for
koverXmlReport purposes**. There is no separate instrumentation
counter for "rendered pixels", so any visual branch a screen exercises
counts, including icon tint variants and corner-shape clipping logic.

Gotcha worth flagging for sibling ticket #3 (Kover gate): the Roborazzi
runtime classes in the test classpath carry their own .class metadata
that may show up under Kover's default scope. If the gate starts
complaining about roborazzi coverage paths, exclude them with
`kover { exclude { group = "io.github.takahirom" } }` (or scope `reports
{ total { ... } }` to `com.example.**` only). The Kover block in
`app/build.gradle.kts` is intentionally untouched here per the ticket
boundary.

## Regenerating goldens locally

```bash
JAVA_HOME=/usr/lib/jvm/java-21-openjdk-amd64 \
  PATH=/usr/lib/jvm/java-21-openjdk-amd64/bin:$PATH \
  ./gradlew :app:testDebugUnitTest \
    --tests "com.example.ui.snapshots.LibraryComponentsSnapshotTest" \
    -Proborazzi.test.record=true
```

Without `-Proborazzi.test.record=true`, the test compares against the
committed golden and fails on pixel diffs above Roborazzi's threshold.

## Future work

- Add a `LibraryState` data class + stateless overload so a true
  `LibraryScreen` snapshot can be captured without a ViewModel.
- Extend the snapshot matrix to the other five screens called out in
  the map (Explore, BookDetail, Player, FourReadWeb, MiniPlayer).
- Wire `./gradlew :app:verifyRoborazziDebug` into a CI matrix leg so
  visual diffs show up on PRs.
