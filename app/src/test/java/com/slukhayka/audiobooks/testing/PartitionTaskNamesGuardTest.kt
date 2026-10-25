package com.slukhayka.audiobooks.testing

import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * #1431 — the partition command `AGENTS.md` tells you to run must be the one the
 * build registers.
 *
 * Gradle accepts a camel-case ABBREVIATION of a task name, so
 * `:app:testRoomRoBolectricOnly` resolves to the registered
 * `testRoomRobolectricOnly` and the build starts. But
 * `requestedPartitionAliases` in `app/build.gradle.kts` matches the **typed**
 * name against the alias map, finds nothing, and the partition configuration is
 * never applied: no `-Ptest.selectedClasses` filter, no `forkEvery = 1`, no
 * `maxParallelForks`. The whole `testDebugUnitTest` set then runs in one JVM.
 *
 * That is not cosmetic. Measured on 2026-10-25 in a lane, following the wrong
 * spelling ran the full suite in a single JVM and Room deadlocked:
 * `arch_disk_io_*` coroutines parked on one `ReentrantLock` while its holder
 * looped in `FrameworkSQLiteOpenHelper.innerGetDatabase`; the first Room class
 * never finished. A local run that follows the docs therefore looks exactly
 * like "Room Robolectric-only hangs". The CI workflow always used the correct
 * spelling; the documented one was wrong since #1192.
 *
 * This guard is a disk read on the JVM — no device, no Android runtime.
 */
class PartitionTaskNamesGuardTest {

    private val repoRoot: File by lazy {
        // Gradle runs unit tests with cwd = app/; the IDE may use the repo root.
        val cwd = File(System.getProperty("user.dir"))
        generateSequence(cwd) { it.parentFile }
            .firstOrNull { File(it, "AGENTS.md").isFile && File(it, "app/build.gradle.kts").isFile }
            ?: error("repo root not found from $cwd")
    }

    /** `"testFoo" to partitionClasses(...)` — the aliases the build registers. */
    private val registered: Set<String> by lazy {
        val text = File(repoRoot, "app/build.gradle.kts").readText()
        ALIAS.findAll(text).map { it.groupValues[1] }.toSet()
    }

    /** `:app:testFoo` — the aliases the repository documentation promises. */
    private val documented: Set<String> by lazy {
        val text = File(repoRoot, "AGENTS.md").readText()
        DOCUMENTED.findAll(text).map { it.groupValues[1] }.toSet()
    }

    @Test
    fun `every partition task documented in AGENTS md is registered by the build`() {
        assertTrue(
            "AGENTS.md should document the JVM partition commands; found $documented",
            documented.size >= 6
        )
        val unknown = documented - registered
        assertTrue(
            "AGENTS.md names partition tasks the build does not register: $unknown. " +
                "Registered aliases: $registered. Gradle accepts a camel-case abbreviation of a " +
                "registered name, so such a run starts — but the partition filter and fork " +
                "isolation are NOT applied, the whole unit-test suite runs in one JVM, and Room " +
                "can deadlock (#1431). Use the exact registered spelling.",
            unknown.isEmpty()
        )
    }

    private companion object {
        val ALIAS = Regex("""(?m)^\s*"([A-Za-z0-9]+)"\s+to\s+partitionClasses\(""")
        val DOCUMENTED = Regex(""":app:(test[A-Za-z0-9]+)""")
    }
}
