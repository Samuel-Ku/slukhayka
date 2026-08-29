package com.slukhayka.audiobooks.testing

import org.junit.Assert.assertEquals
import org.junit.Test

class PartitionCoverageProbePureTest {
    @Test
    fun `pure JVM partition contributes to merged coverage`() {
        assertEquals("pure-jvm", PartitionCoverageProbe.pureJvm())
    }
}
