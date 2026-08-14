# Phone session — spec-20 T5 (#126): rebrand check on a reinstalled phone

> Runbook for the final device verification of spec-20 (full rebrand —
> «Слухайка» everywhere, sources hidden), per repo convention. Device:
> **OnePlus 8 Pro** (IN2023), Android 14, wireless ADB. The reinstall is
> **mandatory**: the applicationId changed, so the new install is a fresh
> package — the old data does not carry over by design (spec-20 decision 2).
> Fill the step table into `docs/phone-test/RESULT.md` (or a
> `## spec-20 T5` section) with pass/fail + screenshots + DB evidence after
> the run.
>
> Build under test: HEAD of `main` — applicationId `com.slukhayka.app`
> (spec-20 T1), badges/affordances neutralized (T2), legacy placeholder
> cleanup (T3), brand sweep (T4). JVM suite: green before the run.

## What we verify (from the user's perspective)

1. **Fresh package** — after `adb uninstall` of the old id, the app installs
   and launches as a new package `com.slukhayka.app`; the launcher label is
   «Слухайка».
2. **No «4read» anywhere visible** — Огляд, сторінка книги, плеєр, Медіатека,
   пошук, міні-плеєр: no «4read.org Source» pill, no library/global-search
   source badges, no branded feed headers.
3. **Neutral affordances** — the book-page open button says «Відкрити
   оригінал» (both for 4read and sluhay books) and opens the site in the
   system browser.
4. **Catalog playback** — find a book, play it end-to-end after the reinstall
   (fresh DB + fresh catalogue sync).
5. **«Що кажуть джерела» still works** — the multi-source blocks on the book
   page render per source with their pills (feature, not branding — kept).
6. **Legacy data is clean** — on a DB upgraded from the old install (restore
   `audiobooks.db` backup from the previous package into the new one), no row
   keeps author «4read.org», narrator «4read Voice Narrator», genre «4read
   Каталог», or a branded description; the scrub is idempotent across a
   second launch.

## Preconditions

```bash
# 1. Build (debug signing, AGP default keystore)
export JAVA_HOME="/usr/lib/jvm/java-21-openjdk-amd64"
./gradlew :app:testDebugUnitTest        # suite green first
./gradlew :app:assembleDebug

# 2. Reinstall = uninstall of the OLD package first (spec-20 decision 2)
adb uninstall com.aistudio.audiobook.read || true
adb install app/build/outputs/apk/debug/app-debug.apk
adb shell am start -n com.slukhayka.app/com.example.MainActivity
```

Optional legacy-data check (step 6): before uninstalling the old package,
`adb shell run-as com.aistudio.audiobook.read cat databases/audiobooks.db >
/tmp/old.db`, then after reinstall copy it in via
`adb shell run-as com.slukhayka.app sh -c 'cat > databases/audiobooks.db'`.

## Runbook

- [ ] **R1. Fresh install + launcher label** — screenshot of the launcher and
      the app home.
- [ ] **R2. Огляд** — screenshot: rows render, no source pill, no branded
      headers.
- [ ] **R3. Сторінка книги** — screenshot: no «4read.org Source» pill, no
      «Narrated by 4read Voice Narrator»; author/narrator real only.
- [ ] **R4. «Відкрити оригінал»** — tap; system browser opens the source page.
- [ ] **R5. Плеєр + міні-плеєр** — screenshot: no «4read» strings in the
      player and debug overlay.
- [ ] **R6. Медіатека** — screenshot: card rows without source badges.
- [ ] **R7. Пошук** — screenshot: result cards without source pills.
- [ ] **R8. «Що кажуть джерела»** — the blocks still render per source.
- [ ] **R9. Catalog playback after reinstall** — play a catalog book to
      audio.
- [ ] **R10. Legacy-DB cleanup (optional)** — restored DB: branded
      placeholders gone, second launch stays clean.

Screenshots land in `docs/phone-test/` (prefix `20_`), the evidence table in
`RESULT.md`:
`| # | State | Result | Evidence |`.