package com.slukhayka.audiobooks.data.metadata

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Spec #681 T4 (#685) — the shared base persists only stable chapter URLs:
 * direct-source links pass; signed CDN hosts, expiring links and any
 * signature/expiry query parameter never do; a mixed profile is refused as a
 * whole so the chapter index pairing cannot silently shift.
 */
class ProfileUrlPolicyTest {

    @Test
    fun `stable direct source urls pass`() {
        assertTrue(ProfileUrlPolicy.isStableChapterUrl("https://s1.reasd.org/book.mp3"))
        assertTrue(ProfileUrlPolicy.isStableChapterUrl("https://arch.sound-books.net/kobzar/1.mp3"))
        assertTrue(ProfileUrlPolicy.isStableChapterUrl("https://4read.org/m3u/book.m3u"))
        assertTrue(ProfileUrlPolicy.isStableChapterUrl("https://sluhay.com.ua/audio/chapter.mp3"))
    }

    @Test
    fun `signed cdn hosts never pass`() {
        assertFalse(ProfileUrlPolicy.isStableChapterUrl("https://9giiu0g54k8c.redirectto.cc/s05/26544.pl.txt"))
        assertFalse(ProfileUrlPolicy.isStableChapterUrl("https://r1---sn-abc.googlevideo.com/videoplayback?itag=22"))
    }

    @Test
    fun `expiry and signature parameters never pass`() {
        assertFalse(ProfileUrlPolicy.isStableChapterUrl("https://cdn.example/book.mp3?expire=1712345678"))
        assertFalse(ProfileUrlPolicy.isStableChapterUrl("https://cdn.example/book.mp3?Expires=1&Signature=abc"))
        assertFalse(ProfileUrlPolicy.isStableChapterUrl("https://cdn.example/book.mp3?token=abc"))
        assertFalse(ProfileUrlPolicy.isStableChapterUrl("https://cdn.example/book.mp3?X-Amz-Signature=abc&X-Amz-Credential=x"))
        assertFalse(ProfileUrlPolicy.isStableChapterUrl("https://cdn.example/book.mp3?Policy=abc&Key-Pair-Id=K123"))
    }

    @Test
    fun `non-http and malformed urls never pass`() {
        assertFalse(ProfileUrlPolicy.isStableChapterUrl("file:///tmp/book.mp3"))
        assertFalse(ProfileUrlPolicy.isStableChapterUrl("not a url"))
    }

    @Test
    fun `a profile with any unstable chapter is refused as a whole`() {
        val stable = BookProfile(
            chapters = listOf(ProfileChapter("1", "https://arch.sound-books.net/1.mp3"))
        )
        assertEquals(stable, ProfileUrlPolicy.stableChapters(stable))

        val mixed = stable.copy(
            chapters = stable.chapters + ProfileChapter("2", "https://x.redirectto.cc/2.mp3")
        )
        assertNull(ProfileUrlPolicy.stableChapters(mixed))
    }
}
