package com.slukhayka.audiobooks.testing

import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * #984 — the guard that stops the instrumented set from growing silently.
 *
 * The symptom: the `app/src/androidTest` tree declares 19 test classes, but CI
 * executes exactly one of them. `.github/scripts/run-accessibility-test.sh`
 * runs `:app:connectedDebugAndroidTest` with a single-class
 * `-Pandroid.testInstrumentationRunnerArguments.class=…MainActivityAccessibilityTest`
 * filter (line 20), while `.github/workflows/ci.yml` still *compiles* the whole
 * `androidTest` source set in the "Prebuild accessibility APKs" step
 * (`:app:assembleDebugAndroidTest`, line 479). Compiled-but-never-run classes
 * create the illusion of coverage — the defect class #956/#958/#966 each caught
 * once in miniature.
 *
 * The owner has NOT decided to widen the CI run (that is a CI-cost decision).
 * So this guard widens nothing. It only pins the current set: a new
 * instrumented test class must be a deliberate act — wired into the run,
 * silenced with a reasoned `@Ignore("…")`, or explicitly recorded as known debt
 * below.
 *
 * The scan is a source scan on purpose — the same style as
 * [com.slukhayka.audiobooks.ui.CanonicalRowWrappersGuardTest],
 * [com.slukhayka.audiobooks.ui.ResidualOldNamesGuardTest] and
 * [SingleEnglishWalkGuardTest] — and stays on the JVM: no device and no
 * instrumentation. It deliberately does NOT use the JUnit4 Android runner
 * annotation as its marker: that annotation appears in only 5 of the 19
 * classes here, so it is not a reliable instrumented marker. What all 19 share
 * is a JUnit `@Test` method, which is the marker this guard uses. The 20th
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
                appendLine("CI executes only ${EXECUTED_BY_CI.joinToString()} (see ${RUNNER_SCRIPT_PATH}:20).")
                appendLine("A new class must be a deliberate act — pick one:")
                appendLine("  1. run it: add it to the class filter in $RUNNER_SCRIPT_PATH")
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

        // The one class this guard claims CI runs must actually be named by the
        // runner script. If the filter is renamed, removed or widened, this
        // claim would otherwise become another silent lie.
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
         * The one class CI actually executes: `.github/scripts/run-accessibility-test.sh`
         * line 20 passes `-Pandroid.testInstrumentationRunnerArguments.class=` with
         * this single class and no other, so it is the whole executed set.
         */
        val EXECUTED_BY_CI = listOf(
            "MainActivity" + "AccessibilityTest"
        )

        /**
         * The #984 debt, frozen rather than fixed: these classes are compiled
         * into the androidTest APK on every CI run but executed by none. They
         * are listed explicitly so the set cannot grow by accident — adding one
         * more is a visible edit here. Each entry may only ever leave this list
         * by being wired into the run. `TelegramLoginSpikeTest` also has a
         * manual, out-of-CI entry point (`scripts/telegram-login-spike.sh`), but
         * no CI filter runs it, so it stays debt here.
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
            "Settings" + "Navigation",
            "UiSurface" + "Audit",
            // audio/
            "AudioPlayback" + "Espresso",
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
