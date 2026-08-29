package com.slukhayka.audiobooks.testing

/** Deliberate Kover merge probe: each public JVM partition covers one method. */
object PartitionCoverageProbe {
    fun pureJvm() = "pure-jvm"
    fun roomRobolectric() = "room-robolectric"
    fun composeRoborazzi() = "compose-roborazzi"
}
