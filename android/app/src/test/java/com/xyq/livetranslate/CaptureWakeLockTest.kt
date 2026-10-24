package com.xyq.livetranslate

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.concurrent.atomic.AtomicInteger

class CaptureWakeLockTest {
    @Test
    fun renewsBeforeExpiryAndStopReleasesWithoutFurtherAcquire() {
        val acquired = mutableListOf<Long>()
        val released = AtomicInteger()
        val posted = mutableListOf<Pair<Runnable, Long>>()
        val lock = CaptureWakeLock(
            acquire = { acquired += it },
            releaseLock = { released.incrementAndGet() },
            postDelayed = { runnable, delayMs -> posted += runnable to delayMs },
            removeCallbacks = { },
            holdMs = 1_000L,
            renewLeadMs = 100L,
        )

        lock.start()
        assertEquals(listOf(1_000L), acquired)
        assertEquals(900L, posted.single().second)

        posted.single().first.run()
        assertEquals(listOf(1_000L, 1_000L), acquired)
        assertEquals(900L, posted.last().second)

        lock.stop()
        assertEquals(1, released.get())
        val acquiredAfterStop = acquired.size
        posted.last().first.run()
        assertEquals(acquiredAfterStop, acquired.size)
    }

    @Test
    fun defaultRenewalIsOneMinuteBeforeTheFourHourHold() {
        assertEquals(4L * 60L * 60L * 1000L - 60_000L, CaptureWakeLock.renewDelayMs())
        assertTrue(CaptureWakeLock.renewDelayMs() > 0L)
    }
}
