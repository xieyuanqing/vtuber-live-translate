package com.xyq.livetranslate

/**
 * 部分唤醒锁按固定窗口持有，在到期前续期，超过 4 小时的会话不会被这次超时掐掉。
 * 停止时先取消续期，再释放锁。
 */
internal class CaptureWakeLock(
    private val acquire: (timeoutMs: Long) -> Unit,
    private val releaseLock: () -> Unit,
    private val postDelayed: (Runnable, Long) -> Unit,
    private val removeCallbacks: (Runnable) -> Unit,
    private val holdMs: Long = HOLD_MS,
    private val renewLeadMs: Long = RENEW_LEAD_MS,
) {
    private val renewRunnable = Runnable { renew() }
    private var running = false

    fun start() {
        running = true
        acquire(holdMs)
        scheduleRenewal()
    }

    fun renew() {
        if (!running) return
        acquire(holdMs)
        scheduleRenewal()
    }

    fun stop() {
        running = false
        removeCallbacks(renewRunnable)
        runCatching { releaseLock() }
    }

    private fun scheduleRenewal() {
        removeCallbacks(renewRunnable)
        postDelayed(renewRunnable, renewDelayMs(holdMs, renewLeadMs))
    }

    companion object {
        const val HOLD_MS = 4L * 60L * 60L * 1000L
        const val RENEW_LEAD_MS = 60_000L

        fun renewDelayMs(holdMs: Long = HOLD_MS, renewLeadMs: Long = RENEW_LEAD_MS): Long =
            (holdMs - renewLeadMs).coerceAtLeast(renewLeadMs)
    }
}
