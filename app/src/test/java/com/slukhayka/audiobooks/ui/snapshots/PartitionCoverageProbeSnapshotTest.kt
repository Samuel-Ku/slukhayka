package com.slukhayka.audiobooks.ui.snapshots

import com.slukhayka.audiobooks.testing.PartitionCoverageProbe
import org.junit.Assert.assertEquals
import org.junit.Test

class PartitionCoverageProbeSnapshotTest {
    @Test
    fun `Compose Roborazzi partition contributes to merged coverage`() {
        assertEquals("compose-roborazzi", PartitionCoverageProbe.composeRoborazzi())
    }
}
