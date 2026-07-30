# Tests Module

<!-- Generated: 2026-07-30 | Files scanned: 6 | Token estimate: ~500 -->

## Purpose

Automated test suite: unit tests in `src/test/`, instrumented tests in `src/androidTest/`. Currently 5 unit test files + 1 instrumented test. No screenshot-test config wired despite Roborazzi dependency.

## Key Files

```
app/src/test/java/com/example/AudioParsingTest.kt           (~70 lines)  — minimal
app/src/test/java/com/example/ButtonTesting.kt             (~50 lines)  — minimal
app/src/test/java/com/example/ExampleRobolectricTest.kt    (~40 lines)  — template
app/src/test/java/com/example/ExampleUnitTest.kt           (~30 lines)  — template
app/src/test/java/com/example/GreetingScreenshotTest.kt    (~50 lines)  — Roborazzi, may be broken
app/src/androidTest/java/com/example/ExampleInstrumentedTest.kt (~30 lines) — template
```

## Test Configuration

```kotlin
// app/build.gradle.kts
testOptions { unitTests { isIncludeAndroidResources = true } }

dependencies {
  testImplementation(libs.junit)
  testImplementation(libs.androidx.junit)
  testImplementation(libs.kotlinx.coroutines.test)
  testImplementation(libs.robolectric)
  testImplementation(libs.roborazzi)
  testImplementation(libs.roborazzi.compose)
  testImplementation(libs.roborazzi.junit.rule)
  androidTestImplementation(libs.androidx.espresso.core)
}
```

## What Each Test Does (best guess from filename)

| File | Likely purpose | Status |
|---|---|---|
| `AudioParsingTest` | Tests for HTML/audio URL regex extraction | Phase 3 will tell |
| `ButtonTesting` | UI interaction tests (Compose) | Phase 3 will tell |
| `ExampleRobolectricTest` | Template — basic Robolectric test | May be placeholder |
| `ExampleUnitTest` | Template — basic JUnit test | Likely trivial |
| `GreetingScreenshotTest` | Roborazzi screenshot test | May fail if no `@Preview` |
| `ExampleInstrumentedTest` | Espresso template | Not run in unit phase |

## Known Issues (Phase 2 candidates)

- `app/src/test/screenshots/greeting.png` exists but no `@Preview` was found in screens → Roborazzi test likely fails
- Most test files appear to be templates / placeholders — real coverage is essentially zero
- No tests for `AudiobookRepository` (the largest, most complex file) — should test:
  - `extractAudioFromHtml` regex variations
  - `importAudiobookFromHtml` URL parsing
  - `seedInitialDataIfEmpty` idempotency
  - `downloadAudiobookOffline` fallback behavior
- No tests for `AudioPlayerManager` (state machine, position updates)
- No tests for `MainViewModel` (coroutine flows, search)
- No tests for screens (Compose UI tests)

## How to Run

```bash
./gradlew test                          # unit tests only
./gradlew connectedDebugAndroidTest     # instrumented tests (requires device)
./gradlew testDebugUnitTest             # explicit debug variant unit tests
./gradlew :app:verifyRoborazziDebug     # screenshot comparison
```

## Common Tasks

| Task | Touch |
|---|---|
| Add unit test for Repository | `app/src/test/java/com/example/data/repository/` (new) |
| Add UI test | `app/src/androidTest/java/com/example/ui/` (new) |
| Add screenshot test | new file in `app/src/test/`, annotate with `@RoborazziRule` + `captureRoboImage()` |
| Remove template tests | Delete `Example*.kt` if they're truly placeholders |
