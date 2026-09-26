package com.slukhayka.audiobooks.testing

import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * #984 — the guard that stops the instrumented set from growing silently.
 *
 * The symptom: the `app/src/androidTest` tree declares 20 test classes, but CI
 * executed exactly one of them. `.github/scripts/run-accessibility-test.sh`
 * ran `:app:connectedDebugAndroidTest` with a single-class
 * `-Pandroid.testInstrumentationRunnerArguments.class=…MainActivityAccessibilityTest`
 * filter, while `.github/workflows/ci.yml` still *compiles* the whole
 * `androidTest` source set in the "Prebuild accessibility APKs" step
 * (`:app:assembleDebugAndroidTest`). Compiled-but-never-run classes create the
 * illusion of coverage — the defect class #956/#958/#966 each caught once in
 * miniature.
 *
 * #1017 (25.09) widened the run: the five #852 suites the device verification
 * made green — the journey plus the four it used to leave behind — all execute
 * in the `Accessibility journey (API 35)` leg now. That is why they sit in
 * [EXECUTED_BY_CI] and no longer in the debt list; the classes still listed
 * there remain debt, and the owner still decides before any of them joins a CI
 * run. So this guard widens nothing on its own. It only pins the current set: a
 * new instrumented test class must be a deliberate act — wired into the run,
 * silenced with a reasoned `@Ignore("…")`, or explicitly recorded as known debt
 * below.
 *
 * The scan is a source scan on purpose — the same style as
 * [com.slukhayka.audiobooks.ui.CanonicalRowWrappersGuardTest],
 * [com.slukhayka.audiobooks.ui.ResidualOldNamesGuardTest] and
 * [SingleEnglishWalkGuardTest] — and stays on the JVM: no device and no
 * instrumentation. It deliberately does NOT use the JUnit4 Android runner
 * annotation as its marker: that annotation appears in only 5 of the 20
 * classes here, so it is not a reliable instrumented marker. What all 20 share
 * is a JUnit `@Test` method, which is the marker this guard uses. The 21st
 * `.kt` file in the tree, `IsolatedDatabaseTestRunner.kt`, is the
 * `AndroidJUnitRunner` itself and declares no `@Test`, so it is correctly not a
 * test class.
 *
 * Class names below are assembled from fragments at runtime so this file can
 * never match its own scan.
 */
class InstrumentedCoverageGuardTest {

    private val androidTestRoot: File by lazy {
        // Gradle runs unit tests with cwd = app/.
        listOf(
            File(System.getProperty("user.dir"), "src/androidTest/java"),
            File(System.getProperty("user.dir"), "app/src/androidTest/java")
        ).firstOrNull { it.isDirectory }
            ?: error("androidTest source root not found from ${System.getProperty("user.dir")}")
    }

    private val accessibilityRunner: File by lazy {
        listOf(
            File(System.getProperty("user.dir"), ".github/scripts/run-accessibility-test.sh"),
            File(System.getProperty("user.dir"), "../.github/scripts/run-accessibility-test.sh")
        ).map { it.canonicalFile }.firstOrNull { it.isFile }
            ?: error("run-accessibility-test.sh not found from ${System.getProperty("user.dir")}")
    }

    @Test
    fun `every instrumented test class is either run by CI or recorded as known 984 debt`() {
        val offenders = instrumentedTestClasses().filterNot { it.isAcknowledged() }

        assertTrue(
            buildString {
                appendLine("these instrumented tests are not covered by any CI run and are not recorded debt:")
                offenders.forEach { appendLine("  ${it.file}:${it.line}  ${it.name}") }
                appendLine()
                appendLine("CI executes only ${EXECUTED_BY_CI.joinToString()}")
                appendLine("(see the `suites` list in $RUNNER_SCRIPT_PATH).")
                appendLine("A new class must be a deliberate act — pick one:")
                appendLine("  1. run it: add it to that list")
                appendLine("     (this widens the CI run, so it is the owner's call); or")
                appendLine("  2. silence it with a reason: annotate the class @Ignore(\"why\") —")
                appendLine("     a bare @Ignore with no reason does not count; or")
                appendLine("  3. record it as known debt: add its name to $KNOWN_UNEXECUTED_PROPERTY")
                appendLine("     in this file, with the reason it is not run.")
                appendLine("Do not just let it compile — that is the illusion of coverage #984 is about.")
            },
            offenders.isEmpty()
        )
    }

    @Test
    fun `the coverage lists and the CI filter still agree`() {
        val discovered = instrumentedTestClasses().map { it.name }.toSet()

        val stale = ALLOWED.filterNot { it in discovered }
        assertTrue(
            "these names are no longer instrumented test classes — update the lists in this guard: " +
                stale.joinToString(),
            stale.isEmpty()
        )

        // Every class this guard claims CI runs must actually be named by the
        // runner script. If the list is renamed, removed or shrunk, this claim
        // would otherwise become another silent lie.
        val script = accessibilityRunner.readText()
        val missing = EXECUTED_BY_CI.filterNot { script.contains(it) }
        assertTrue(
            "$RUNNER_SCRIPT_PATH no longer names ${missing.joinToString()} — if the CI run changed, " +
                "update EXECUTED_BY_CI and the known-debt list in this guard, and delete the debt " +
                "that the new run now covers",
            missing.isEmpty()
        )
    }

    private fun instrumentedTestClasses(): List<InstrumentedTestClass> =
        androidTestRoot.walkTopDown()
            .filter { it.isFile && it.extension == "kt" }
            .flatMap { file ->
                val text = file.readText()
                if (!TEST_ANNOTATION.containsMatchIn(text)) return@flatMap emptySequence<InstrumentedTestClass>()
                PRIMARY_CLASS.findAll(text).map { match ->
                    InstrumentedTestClass(
                        name = match.groupValues[1],
                        file = file.relativeTo(androidTestRoot).invariantSeparatorsPath,
                        line = text.take(match.range.first).count { it == '\n' } + 1,
                        reasonedIgnore = hasReasonedIgnoreBefore(text, match.range.first)
                    )
                }
            }
            .toList()

    /**
     * True when the class carries a JUnit `@Ignore` with a non-blank reason, or
     * is otherwise on an explicit list. A bare `@Ignore` is not enough: silence
     * without a reason is exactly the undocumented debt #984 is about.
     */
    private fun InstrumentedTestClass.isAcknowledged(): Boolean =
        name in ALLOWED || reasonedIgnore

    /** Walks back over the annotation lines immediately above a class declaration. */
    private fun hasReasonedIgnoreBefore(text: String, classIndex: Int): Boolean {
        val before = text.take(classIndex)
        val annotations = before.lines()
            .takeLastWhile { it.isBlank() || it.trimStart().startsWith("@") }
            .joinToString("\n")
        return IGNORE_WITH_REASON.containsMatchIn(annotations)
    }

    private data class InstrumentedTestClass(
        val name: String,
        val file: String,
        val line: Int,
        val reasonedIgnore: Boolean
    )

    private companion object {

        /** Built at runtime so the guard file itself never matches. */
        const val RUNNER_SCRIPT_PATH = ".github/scripts/run-accessibility-test.sh"

        /** Name of the known-debt list, assembled for the same reason. */
        val KNOWN_UNEXECUTED_PROPERTY = "KNOWN_UNEXECUTED_" + "BY_CI"

        /** `@Test` is the one marker every instrumented class here carries. */
        val TEST_ANNOTATION = Regex("""^\s*@Test\b""", RegexOption.MULTILINE)

        /** The top-level declaration of the test class in each source file. */
        val PRIMARY_CLASS = Regex(
            """^(?:internal\s+|abstract\s+|open\s+)*class\s+([A-Za-z_]\w*)""",
            RegexOption.MULTILINE
        )

        /** `@Ignore("reason")` — an empty reason deliberately does not match. */
        val IGNORE_WITH_REASON = Regex("@Ignore\\s*\\(\\s*\"[^\"]+\"", RegexOption.MULTILINE)

        /**
         * The classes CI actually executes: the `suites` list of
         * `.github/scripts/run-accessibility-test.sh`, one gradle invocation per
         * class. #1017 widened this from the single journey class to the five
         * #852 suites the device verification made green.
         */
        val EXECUTED_BY_CI = listOf(
            "MainActivity" + "AccessibilityTest",
            "AudioPlayback" + "EspressoTest",
            "UiSurface" + "AuditTest",
            "Settings" + "NavigationTest",
            "BottomBar" + "LargeText" + "LayoutTest"
        )

        /**
         * The #984 debt, frozen rather than fixed: these classes are compiled
         * into the androidTest APK on every CI run but executed by none. They
         * are listed explicitly so the set cannot grow by accident — adding one
         * more is a visible edit here. Each entry may only ever leave this list
         * by being wired into the run — #1017 took out the four #852 classes
         * that used to sit here. `TelegramLoginSpikeTest` also has a manual,
         * out-of-CI entry point (`scripts/telegram-login-spike.sh`), but no CI
         * run executes it, so it stays debt here.
         */
        val KNOWN_UNEXECUTED_BY_CI = listOf(
            // accessibility/
            "BookDetail" + "CoverLayout",
            "BookDetail" + "PeopleLayout",
            "BookFeedback" + "Ui",
            "EditionLanguageFilter" + "Device",
            "LanguageFilter" + "Layout",
            "LanguageFilter",
            "LiveBookDetail" + "Navigation",
            "LiveChapter" + "Focus",
            "RecommendationCover" + "Failure",
            "SearchReturn" + "Navigation",
            // audio/
            // #533 AC5 — opt-in live network runs (`-P…liveSources=true`): both
            // import real books from sluhayua/soundbooks/audiobookmp3, so they
            // can never be part of an offline CI leg. The first downloads a
            // chapter, the second resumes a saved position.
            "LiveStreaming" + "Sources",
            "LiveLocal" + "Chapter",
            "LiveResume" + "AfterRestart",
            "LiveLihtar" + "StreamOnly",
            "LiveBookDetail" + "Actions",
            "LiveBrowser" + "Recovery",
            "LiveCandidate" + "Playback",
            "LiveSource" + "Regression",
            // telegram/
            "TelegramLogin" + "Spike"
        ).map { it + "Test" }

        val ALLOWED = EXECUTED_BY_CI + KNOWN_UNEXECUTED_BY_CI
    }
}
