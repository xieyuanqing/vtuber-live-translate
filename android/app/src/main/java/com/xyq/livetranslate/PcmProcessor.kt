package com.xyq.livetranslate

/**
 * PCM16 处理：立体声混单声道 → 线性插值重采样到 16kHz → 切成 100ms（3200 字节）块。
 * 重采样相位跨缓冲区保持连续。
 */
class PcmProcessor(
    srcRate: Int,
    private val srcChannels: Int,
    private val onChunk: (ByteArray) -> Unit,
) {
    private val samplesPerChunk = 1600 // 100ms @ 16kHz
    private val step = srcRate.toDouble() / 16000.0

    private val pending = ShortArray(samplesPerChunk)
    private var pendingLen = 0

    private var phase = 0.0
    private var lastSample: Short = 0
    private var haveLast = false

    fun feed(buf: ShortArray, len: Int) {
        // 1) 混单声道
        val mono: ShortArray
        val monoLen: Int
        if (srcChannels == 2) {
            monoLen = len / 2
            if (monoLen == 0) return
            mono = ShortArray(monoLen)
            var i = 0
            for (o in 0 until monoLen) {
                mono[o] = ((buf[i].toInt() + buf[i + 1].toInt()) / 2).toShort()
                i += 2
            }
        } else {
            if (len == 0) return
            mono = buf
            monoLen = len
        }

        // 2) 线性插值重采样（ext[0] 是上一批的最后一个样本，保证跨批连续）
        val ext = ShortArray(monoLen + 1)
        ext[0] = if (haveLast) lastSample else mono[0]
        System.arraycopy(mono, 0, ext, 1, monoLen)

        var pos = phase
        val limit = monoLen.toDouble()
        while (pos < limit) {
            val i = pos.toInt()
            val frac = pos - i
            val a = ext[i].toInt()
            val b = ext[i + 1].toInt()
            emit((a + ((b - a) * frac)).toInt().toShort())
            pos += step
        }
        phase = pos - limit
        lastSample = mono[monoLen - 1]
        haveLast = true
    }

    private fun emit(s: Short) {
        pending[pendingLen++] = s
        if (pendingLen == samplesPerChunk) {
            val out = ByteArray(samplesPerChunk * 2)
            var j = 0
            for (k in 0 until samplesPerChunk) {
                val v = pending[k].toInt()
                out[j++] = (v and 0xFF).toByte()
                out[j++] = ((v shr 8) and 0xFF).toByte()
            }
            pendingLen = 0
            onChunk(out)
        }
    }
}
