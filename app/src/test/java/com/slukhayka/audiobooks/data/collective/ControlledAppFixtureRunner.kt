package com.slukhayka.audiobooks.data.collective

import java.nio.file.Files
import java.nio.file.Path
import java.security.MessageDigest
import java.util.Properties
import org.robolectric.RobolectricTestRunner

/** Public runner extension selects a provider-free local-test package BEFORE Android setup. */
class ControlledAppFixtureRunner(testClass: Class<*>) : RobolectricTestRunner(testClass) {
    override fun getBuildSystemApiProperties(): Properties {
        val gate = Path.of(requireNotNull(System.getProperty("slukhayka.s2.fixtureGate")))
        fun sha(path: Path) = MessageDigest.getInstance("SHA-256").digest(Files.readAllBytes(path))
            .joinToString("") { "%02x".format(it.toInt() and 255) }
        check(sha(gate) == System.getProperty("slukhayka.s2.fixtureGateSHA256"))
        val verified = Properties().apply { Files.newBufferedReader(gate).use { load(it) } }
        for (key in listOf("originalApk", "isolatedApk", "isolatedManifest")) {
            check(sha(Path.of(verified.getProperty(key))) == verified.getProperty("${key}SHA256"))
        }
        check(verified.getProperty("negativeFirebaseResourceGate") == "PASS")
        check(verified.getProperty("allOtherZipEntryBytesPreserved") == "true")
        println("S2_FIXTURE_WORKER_PID=${ProcessHandle.current().pid()}; gateSHA256=${sha(gate)}")
        return super.getBuildSystemApiProperties().apply {
            val original = Path.of(getProperty("android_resource_apk")).toAbsolutePath().normalize()
            val pinnedOriginal = Path.of(verified.getProperty("originalApk"))
            println("S2_FIXTURE_ORIGINAL_PATHS; actual=$original; pinned=$pinnedOriginal")
            val sameOriginal = Files.isSameFile(original, pinnedOriginal)
            println("S2_FIXTURE_ORIGINAL_PHYSICAL_IDENTITY=$sameOriginal")
            check(sameOriginal) { "Runtime resource archive is a different filesystem file" }
            setProperty("android_resource_apk", verified.getProperty("isolatedApk"))
            setProperty("android_merged_manifest", verified.getProperty("isolatedManifest"))
        }
    }
}
