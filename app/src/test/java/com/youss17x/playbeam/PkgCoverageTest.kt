package com.youss17x.playbeam

import org.junit.Assert.assertEquals
import org.junit.Test

class PkgCoverageTest {
    @Test fun tailProbeDoesNotCompleteDownload() {
        val coverage = PkgCoverage()
        assertEquals(10L, coverage.add(990, 1000))
        assertEquals(110L, coverage.add(0, 100))
        assertEquals(1000L, coverage.add(100, 990))
    }

    @Test fun retriesAndOverlappingRequestsAreCountedOnce() {
        val coverage = PkgCoverage()
        assertEquals(100L, coverage.add(0, 100))
        assertEquals(100L, coverage.add(0, 100))
        assertEquals(150L, coverage.add(50, 150))
        assertEquals(150L, coverage.add(20, 80))
    }

    @Test fun bridgeMergesMultipleDisjointRequests() {
        val coverage = PkgCoverage()
        coverage.add(0, 10)
        coverage.add(20, 30)
        coverage.add(40, 50)
        assertEquals(50L, coverage.add(10, 40))
    }
}
