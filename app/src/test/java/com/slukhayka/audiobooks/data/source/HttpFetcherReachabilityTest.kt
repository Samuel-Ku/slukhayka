package com.slukhayka.audiobooks.data.source

import org.junit.Assert.assertFalse
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * Spec-51 follow-up (#745) — the reachability probe must not attempt a
 * cleartext URL: the platform's network security policy rejects it outright,
 * so the probe can only burn the socket budget and log a stack trace. It is
 * honestly unreachable and skipped before any network.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36])
class HttpFetcherReachabilityTest {

    @Test
    fun `a cleartext url is unreachable without any probe`() {
        assertFalse(HttpFetcher().isReachable("http://archive.org/download/x/a.mp3"))
        assertFalse(HttpFetcher().isReachable("HTTP://archive.org/download/x/a.mp3"))
    }
}
