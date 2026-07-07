package com.xyq.livetranslate

import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicLong

/** 服务 → 界面 的状态单例。MainActivity 每秒轮询刷新显示。 */
object StatusBus {
    @Volatile var serviceRunning = false
    @Volatile var connState = "idle"
    @Volatile var transcriptPath = ""
    @Volatile var jaTail = ""
    @Volatile var zhTail = ""
    @Volatile var currentKeyLabel = ""
    val chunksSent = AtomicLong(0)

    /** 悬浮窗样式版本号：设置保存时 +1，悬浮窗发现变化就重新读取应用。 */
    val styleVersion = AtomicInteger(0)

    fun reset() {
        connState = "idle"
        transcriptPath = ""
        jaTail = ""
        zhTail = ""
        currentKeyLabel = ""
        chunksSent.set(0)
    }
}
