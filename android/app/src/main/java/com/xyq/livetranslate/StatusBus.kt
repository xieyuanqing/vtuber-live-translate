package com.xyq.livetranslate

import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicLong

/** 服务 → 界面 的状态单例。MainActivity 每秒轮询刷新显示。 */
object StatusBus {
    const val MODE_VIDEO = "video"
    const val MODE_MIC = "mic"

    @Volatile var serviceRunning = false
    /** 当前捕获模式：video / mic / 空 */
    @Volatile var captureMode = ""
    @Volatile var connState = "idle"
    @Volatile var transcriptPath = ""
    @Volatile var jaTail = ""
    @Volatile var zhTail = ""
    @Volatile var currentKeyLabel = ""
    @Volatile var audioLevelPct = 0
    val chunksSent = AtomicLong(0)

    /** 悬浮窗样式版本号：设置保存时 +1，悬浮窗发现变化就重新读取应用。 */
    val styleVersion = AtomicInteger(0)

    fun reset() {
        connState = "idle"
        transcriptPath = ""
        jaTail = ""
        zhTail = ""
        currentKeyLabel = ""
        audioLevelPct = 0
        chunksSent.set(0)
        // captureMode 在 start 时写入，stop 时清空，不在这里抹掉「即将启动」的意图
    }
}
