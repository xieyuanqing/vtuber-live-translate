package com.xyq.livetranslate

import android.os.Handler

/**
 * 字幕稳定器：把 Live Translate 的碎片输出整理成「确认行 + 当前行」。
 * - 碎片边界重叠合并（服务端偶尔把结尾几个字重发一遍）
 * - 句末标点切句；与上一句相同/被包含的句子直接丢弃（治复读）
 * - idleCommitMs 无新碎片、或当前行超 maxCurrentChars 时，强制把当前行转正（高级设置可调）
 * 所有方法必须在主线程调用（handler 即主线程 Handler）。
 */
class SubtitleStabilizer(
    private val handler: Handler,
    private val idleCommitMs: Long = SettingsStore.DEFAULT_STAB_IDLE_MS.toLong(),
    private val maxCurrentChars: Int = SettingsStore.DEFAULT_STAB_MAX_CHARS,
    private val onRender: (confirmed: String, current: String) -> Unit,
) {
    companion object {
        private val TERMINATORS = charArrayOf('。', '！', '？', '…', '～', '!', '?')
    }

    private val current = StringBuilder()
    private var lastCommitted = ""
    private val idleCommit = Runnable {
        commit(force = true)
        render()
    }

    fun onFragment(t: String) {
        if (t.isEmpty()) return
        appendWithOverlap(t)
        commit(force = current.length >= maxCurrentChars)
        handler.removeCallbacks(idleCommit)
        if (current.isNotEmpty()) handler.postDelayed(idleCommit, idleCommitMs)
        render()
    }

    fun reset() {
        handler.removeCallbacks(idleCommit)
        current.setLength(0)
        lastCommitted = ""
    }

    private fun render() = onRender(lastCommitted, current.toString())

    /** 若新碎片的开头和缓冲区结尾重叠（>=2 字），只追加不重叠的部分。 */
    private fun appendWithOverlap(frag: String) {
        val tail = current.toString()
        var k = minOf(tail.length, frag.length)
        while (k > 0) {
            if (tail.regionMatches(tail.length - k, frag, 0, k)) break
            k--
        }
        if (k < 2) k = 0 // 单字重叠多半是巧合（"…的" + "的…"），不按重叠处理
        current.append(frag, k, frag.length)
    }

    /** 把 current 中已到句末的部分切出去转正。force = 无句末标点也全部转正。 */
    private fun commit(force: Boolean) {
        val text = current.toString()
        val cut = text.indexOfLast { it in TERMINATORS }
        val done: String
        val rest: String
        when {
            cut >= 0 -> {
                done = text.substring(0, cut + 1)
                rest = text.substring(cut + 1)
            }
            force && text.isNotBlank() -> {
                done = text
                rest = ""
            }
            else -> return
        }
        current.setLength(0)
        current.append(rest)

        val committed = ArrayList<String>()
        for (s in splitSentences(done)) {
            val sentence = s.trim()
            if (sentence.isEmpty()) continue
            // 连续重复：和上一句相同，或整句包含在上一句里 → 丢弃
            if (sentence == lastCommitted || lastCommitted.contains(sentence)) continue
            lastCommitted = sentence
            committed += sentence
        }
        // 一个服务端碎片可能同时包含多句；合并后一次交给下游，不能只留下最后一句。
        if (committed.isNotEmpty()) lastCommitted = committed.joinToString(separator = "")
    }

    private fun splitSentences(s: String): List<String> {
        val out = ArrayList<String>()
        val cur = StringBuilder()
        for (ch in s) {
            cur.append(ch)
            if (ch in TERMINATORS) {
                out.add(cur.toString())
                cur.setLength(0)
            }
        }
        if (cur.isNotBlank()) out.add(cur.toString())
        return out
    }
}
