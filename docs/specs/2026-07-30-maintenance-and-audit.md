# Maintenance and Audit Cycle for `slukhayka`

**Date:** 2026-07-30
**Type:** Maintenance initiative (no new features)
**Goal:** Establish a clean, documented, audited, buildable, phone-verified baseline for solo long-term maintenance.

---

## Problem Statement

I (the maintainer) have an Android audiobooks player project (`slukhayka`) that I personally maintain. After a recent ExoPlayer migration (commit `ef90563 feat(audio): migrate to ExoPlayer and add HTML import`), the project's state is unclear:

- 24 Kotlin source files, 5 test files, ~50 root migration scripts (mostly leftover one-shot fix tools from the migration)
- No documentation explaining what any of this code does
- No record of what bugs exist, what was fixed, or what was deferred
- No working build verified on a phone
- I'll come back to this in 3 months and won't remember anything

I need a single maintenance cycle that: documents the current state, audits what's broken, builds a working APK, and verifies it works on my phone — so I have a clean baseline to maintain from.

---

## Solution

Run a 5-phase maintenance cycle, with a build sanity check first to avoid wasting effort on broken code:

1. **Phase 0 — Smoke build:** Run `./gradlew assembleDebug`. If it fails, stop and fix the build before proceeding. This is cheap insurance — there's no point documenting broken code.
2. **Phase 1 — Codemaps:** Generate 8 per-feature codemaps covering `audio-player`, `book-library`, `webview-bridge`, `ui-system`, `app-entry`, `build-config`, `tests`, and `migration-artefacts` (the ~50 root scripts get one shared map).
3. **Phase 2 — Audit:** All-inclusive audit (bugs + security + quality + perf + a11y + test gaps + build warnings) using static analysis first, then agents. Catalog everything in `docs/audit/AUDIT_REPORT.md` with 4-tier severity. Fix only Critical issues inline.
4. **Phase 3 — Debug APK build:** Run `./gradlew assembleDebug test`. APK build must succeed; test failures are diagnostic and added to the audit report.
5. **Phase 4 — Phone test:** Pre-write `docs/phone-test/PLAN.md` with a checklist covering smoke + 4 tabs (Explore, 4read Web, Library, Bookmarks) + BookDetail + PlayerScreen + audio playback + bookmarks + sleep timer + WebView import. Install via ADB on a USB-connected phone. Capture one screenshot per state. Catalog failures, fix Critical inline.

Each phase ends with one commit on `main`. Total: 5 commits + this spec commit.

---

## User Stories

These stories are from the perspective of "future me, the maintainer returning in 3 months."

1. As a maintainer returning to this project after 3 months, I want to read `docs/CODEMAPS/README.md`, so that I can navigate to documentation for any feature area in 5 seconds.
2. As a maintainer, I want a codemap for the `audio-player` module, so that I can understand ExoPlayer integration, MiniPlayerBar, PlayerScreen, and SleepTimerSheet without re-reading source code.
3. As a maintainer, I want a codemap for the `book-library` module, so that I can understand Room schema, repository pattern, and Home/Library/BookDetail screens.
4. As a maintainer, I want a codemap for the `webview-bridge` module, so that I can understand the HTML import feature and how `playerjs6.js` is integrated.
5. As a maintainer, I want a codemap for the `ui-system` module (theme + components), so that I can change colors/typography/dialogs without breaking the design system.
6. As a maintainer, I want a codemap for the `app-entry` module (MainActivity, MainViewModel, navigation), so that I can add/remove tabs and modify back navigation logic.
7. As a maintainer, I want a codemap for the `build-config` module (Gradle, manifest, ProGuard, network/backup/data extraction configs), so that I can change dependency versions, manifest permissions, and security settings safely.
8. As a maintainer, I want a codemap for the `tests` module, so that I can understand what tests exist, what they cover, and where to add new ones.
9. As a maintainer, I want a `migration-artefacts` codemap covering the ~50 root scripts, so that I know which are dead code, which are still invoked, and what each one originally did.
10. As a maintainer, I want an `AUDIT_REPORT.md` with findings categorized by area (Bugs, Security, Quality, Performance, A11y, Test Gaps, Build Warnings), so that I can see the full landscape of issues at a glance.
11. As a maintainer, I want each audit finding to have a severity (Critical/High/Medium/Low), location, description, and status (Open/Fixed/Deferred), so that I can prioritize.
12. As a maintainer, I want Critical issues fixed inline during the audit, so that I have a clean baseline.
13. As a maintainer, I want a debug APK to be buildable via `./gradlew assembleDebug`, so that I can install it on any device for testing.
14. As a maintainer, I want unit tests to be run as part of the build, so that regressions are caught automatically.
15. As a maintainer, I want a `PLAN.md` with a step-by-step checklist for phone testing, so that I can re-run the same verification in 3 months.
16. As a maintainer, I want a screenshot per key state (smoke, each tab, each player control, bookmark flow, sleep timer, WebView import), so that I have visual evidence that everything works.
17. As a maintainer, I want phone-test failures cataloged, so that I can address them in a follow-up cycle.
18. As a maintainer, I want Critical phone-test failures fixed inline, so that the verified state is genuinely clean.
19. As a maintainer, I want each phase committed separately on `main`, so that I can `git log` and see the audit trail.
20. As a maintainer, I want this spec committed, so that future me can see the original plan and rationale.

---

## Implementation Decisions

### Phase 0 (Smoke build)

- **Command:** `./gradlew assembleDebug`
- **Pre-condition:** `gradlew` wrapper, `local.properties` with `sdk.dir`, and `ANDROID_HOME` environment variable must all be present.
- **Failure mode:** stop and discuss; do not proceed to Phase 1 until build is green.
- **Known risk:** project was committed without `gradlew` wrapper; `ANDROID_HOME` is unset on the development machine. If absent, create `local.properties`, install Android SDK, generate wrapper, and retry.

### Phase 1 (Codemaps)

- **Tool:** `update-codemaps` skill (read-only code → markdown generation).
- **Output:** 8 codemap files + 1 index:
  - `docs/CODEMAPS/audio-player.md`
  - `docs/CODEMAPS/book-library.md`
  - `docs/CODEMAPS/webview-bridge.md`
  - `docs/CODEMAPS/ui-system.md`
  - `docs/CODEMAPS/app-entry.md`
  - `docs/CODEMAPS/build-config.md`
  - `docs/CODEMAPS/tests.md`
  - `docs/CODEMAPS/migration-artefacts.md`
  - `docs/CODEMAPS/README.md` (index linking all 8)
- **Codemaps mirror the existing module structure** in `app/src/main/java/com/example/{audio-player, book-library, webview-bridge, ui-system, app-entry}/` — no new abstraction introduced.
- **Root scripts handling:** one shared `migration-artefacts.md` with a table — filename | size | still invoked? | original purpose | recommended action (delete / document / keep). Detailed codemaps are NOT created for individual scripts; Phase 2 audit determines which scripts are still live and worth documenting individually in a follow-up.

### Phase 2 (Audit)

- **Two-layer approach:** static analysis first (mechanical, fast, comprehensive), then agents (architectural, conceptual).
- **Static analysis tools:**
  - **detekt** for Kotlin code smells
  - **ktlint** for style
  - **android lint** for Android-specific (resources, manifest, accessibility, performance)
  - **Gradle build warnings** captured during Phase 3
- **Agent layer:**
  - **code-reviewer** for architecture, design patterns, anti-patterns
  - **security-reviewer** for OWASP Top 10 + Android-specific (secrets, intent permissions, network security, WebView JS interface)
  - **performance-optimizer** for cold start, jank, memory, allocations
- **Output:** `docs/audit/AUDIT_REPORT.md` with sections per category (Bugs, Security, Quality, Performance, A11y, Test Gaps, Build Warnings).
- **Severity scale:** 4-tier (Critical / High / Medium / Low):
  - **Critical** = blocks build / crashes on launch / data loss / security hole. Fixed inline during Phase 2.
  - **High** = serious bug that hurts daily use. Cataloged; fix deferred unless cheap.
  - **Medium** = code smell that will slow future changes. Cataloged; deferred.
  - **Low** = cosmetic, docs, minor style. Cataloged; deferred.
- **Finding row format:** `| Severity | Location | Description | Status |` where Status = Open / Fixed / Deferred.
- **Fix commits** use `fix(audit-2026-07-30): <description>` prefix.
- **Test failures from Phase 3** are added as findings in AUDIT_REPORT.md, not as separate Phase 2 blockers.

### Phase 3 (Build)

- **Command:** `./gradlew assembleDebug test`.
- **Success criterion:** `assembleDebug` exits 0. Test task is diagnostic.
- **Failed tests →** added to AUDIT_REPORT.md as findings (Status = Open).
- **No signing config required:** debug keystore is auto-generated by Gradle.
- **Output:** `app/build/outputs/apk/debug/app-debug.apk`.

### Phase 4 (Phone test)

- **Pre-write:** `docs/phone-test/PLAN.md` — checklist of 11-15 states, each with: name, expected behavior, screenshot filename, PASS/FAIL/DEFERRED checkbox.
- **States covered:**
  - Smoke launch (app opens to Explore tab, no crash)
  - Explore tab (9 seeded books visible)
  - Library tab (1 downloaded book: Neuromancer)
  - Bookmarks tab (2 seeded bookmarks visible)
  - 4read Web tab (URL input form visible)
  - BookDetail screen (after tap on a book)
  - PlayerScreen overlay (after tap on play)
  - Audio plays (verified by state change in MiniPlayerBar + audible playback)
  - Pause works
  - Seek works (drag to midpoint)
  - Skip chapter works
  - Add bookmark works
  - Sleep timer sheet opens and accepts input
  - WebView URL import (paste a 4read URL → import → play)
- **Auto-seeded test data:** app seeds 9 books (Neuromancer with 6 chapters + 2 bookmarks + playback progress, plus 1984, Fahrenheit 451, Dune, Solaris, Roadside Picnic, Master and Margarita, Sherlock Holmes, Cyber Dystopia 2077) on first launch via `seedInitialDataIfEmpty()` in `AudiobookRepository`. No manual data setup needed.
- **Install:** `adb install -r app-debug.apk` when phone is connected via USB with USB debugging enabled.
- **Evidence:** `adb exec-out screencap -p > docs/phone-test/screenshots/{state}.png` per state.
- **Failure handling:** catalog all in AUDIT_REPORT.md; Critical failures fixed inline (allows rebuild + re-test within Phase 4). Non-critical → catalog only.
- **Audio source:** streams from `archive.org` (public domain LibriVox content) — works for online playback; offline chapters are those marked `isDownloaded = true`.

### Git strategy

- **Single branch:** `main`. Linear history.
- **One commit per phase boundary:**
  - `chore: phase 0 smoke build passes` (or `fix(build): <issue>` if build required fixes)
  - `docs: phase 1 add codemaps for 8 modules`
  - `chore(audit): phase 2 catalog findings` (+ `fix(audit-2026-07-30): ...` per Critical fix)
  - `chore: phase 3 debug apk builds`
  - `test: phase 4 phone verification done` (+ `fix(audit-2026-07-30): ...` per Critical phone-test fix)
- **Spec commit:** `docs: add maintenance and audit cycle spec` (this file).

---

## Testing Decisions

### What makes a good test for this work

Each phase's commit must be self-contained and reversible: revert one commit = undo one phase. The "test" of success for each phase is:

- **Phase 0 success:** `./gradlew assembleDebug` exits 0. `app/build/outputs/apk/debug/app-debug.apk` exists.
- **Phase 1 success:** 8 codemap files exist in `docs/CODEMAPS/`, README index links to all of them, and each codemap accurately reflects the source code (verified by spot-checking 2-3 file references per codemap).
- **Phase 2 success:** `AUDIT_REPORT.md` exists, has all 7 sections, every finding has severity/location/description/status, Critical count = 0 (all Critical issues fixed inline).
- **Phase 3 success:** APK file exists at expected path; `adb install -r app-debug.apk` succeeds against a real or virtual device; `app/build/reports/tests/testDebugUnitTest/index.html` exists (test report).
- **Phase 4 success:** every state in `PLAN.md` is checked (PASS or DEFERRED), corresponding screenshot exists at `docs/phone-test/screenshots/{state}.png`.

### Modules being tested

- Each codemap module (8) is itself a testable unit — the codemap accurately reflects the code (spot-checked, not exhaustively diffed).
- Audit findings have locations that point to specific files (verifiable by `grep`).
- Phone test states have visual evidence (screenshots) that can be re-inspected.

### Prior art

- Existing `app/src/test/java/com/example/` tests (5 files: `AudioParsingTest`, `ButtonTesting`, `ExampleRobolectricTest`, `ExampleUnitTest`, `GreetingScreenshotTest`) are NOT modified by this work — they may be broken, but Phase 3 surfaces test failures as audit findings rather than fixing them inline.
- Existing `app/src/androidTest/java/com/example/ExampleInstrumentedTest.kt` is NOT modified.
- This spec does NOT add new automated tests — it adds documentation artifacts and audit artifacts.

### Phone test as end-to-end seam

The highest seam is the entire app running on a real device. All 14+ states are exercised manually by the developer with the assistant providing ADB commands and capturing screenshots. This is the most realistic "does it actually work" check given the project is a UI-heavy consumer app.

---

## Out of Scope

- Adding new features.
- Refactoring code (unless a Critical audit finding requires it).
- Adding automated tests for existing untested code (audit can flag this; fixing is deferred).
- Publishing to Google Play Store.
- Release signing / production keystore.
- iOS / KMP / desktop variants.
- Continuous Integration setup (no GitHub Actions workflows, no CI/CD).
- Performance profiling under load (the audit identifies perf risks but does not benchmark).
- Accessibility fixes beyond flagging in the audit.
- Localization / i18n improvements.
- Updating dependencies to newer versions (audit can flag outdated deps; updating is deferred).

---

## Further Notes

### Seams (testing boundaries)

This work introduces 4 new file-system seams:

1. `docs/CODEMAPS/` — Phase 1 output, 8 markdown files + index
2. `docs/audit/AUDIT_REPORT.md` — Phase 2 output, single markdown
3. `docs/phone-test/PLAN.md` — Phase 4 input, checklist
4. `docs/phone-test/screenshots/` — Phase 4 output, PNG per state

Existing seams leveraged (no new abstraction introduced):

- `app/src/main/java/com/example/{audio-player, book-library, webview-bridge, ui-system, app-entry}/` — mirrors Phase 1 codemap modules
- `app/src/test/`, `app/src/androidTest/` — Phase 3 verification
- `gradle/libs.versions.toml` — version catalog (Phase 3)

The highest behavioral seam is Phase 4's end-to-end phone test — the entire app on a real device is the unit being verified.

### Decisions log

The 21 questions resolved during the grilling session on 2026-07-30 are the source of truth for this spec:

| Q# | Topic | Decision |
|---|---|---|
| Q1 | Goal | B — Personal maintenance |
| Q2 | Documentation scope | B — Everything |
| Q3 | Documentation format | B — Codemaps |
| Q4 | Codemap organization | A — Per feature/module |
| Q5 | Root scripts handling | A — One shared + details only for live |
| Q6 | Module list | A — 8 modules as proposed |
| Q7 | Audit scope | D — All-inclusive |
| Q8 | Audit approach | C — Static first, then agents |
| Q9 | Audit fix policy | A — Catalog all, fix only Critical |
| Q10 | Audit output format | A — Single AUDIT_REPORT.md |
| Q11 | Audit severity | A — 4-tier |
| Q12 | Build variant | A — Debug APK |
| Q13 | Build verification | B — assembleDebug + unit tests |
| Q14 | Test failure handling | A — Catalog, don't block |
| Q15 | Phone device strategy | A — USB + adb |
| Q16 | Phone test scope | D — Full coverage |
| Q17 | Phone test evidence | A — Screenshots per state |
| Q18 | Phone test failure handling | A — Catalog + fix Critical inline |
| Q19 | Phone test plan structure | A — Pre-written PLAN.md with checklist |
| Q20 | Cross-phase sequencing | B — Phase 0 smoke build before Phase 1 |
| Q21 | Git strategy | A — Single branch, one commit per phase |

### Known risks at time of writing

1. **Phase 0 may fail** because the project lacks `gradlew` wrapper and `ANDROID_HOME` is unset. Mitigation: Phase 0 explicitly stops on failure.
2. **Phase 2 may surface Critical findings** that require non-trivial fixes (e.g., security issues, dependency upgrades). Each fix becomes its own commit with `fix(audit-2026-07-30):` prefix.
3. **Phase 4 requires a physical Android phone** with USB debugging enabled. If unavailable at the scheduled time, the work stalls at the end of Phase 3.
4. **Auto-seeded test data depends on `https://4read.org/`** being reachable for `fetchCatalogFrom4Read()`. If the site is down, only Neuromancer (which has hardcoded chapters) will be available for end-to-end audio testing.
5. **The five existing unit tests may fail** post-ExoPlayer migration. They are diagnostic, not blocking — failures are added to AUDIT_REPORT.md as Phase 2 findings.

### Communication

Working language for this cycle: Ukrainian (for dialogue, commit messages stay in English per Conventional Commits).
