package com.xyq.livetranslate

import android.util.Base64
import android.util.Log
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import okio.ByteString
import org.json.JSONArray
import org.json.JSONObject
import java.util.concurrent.Executors
import java.util.concurrent.LinkedBlockingDeque
import java.util.concurrent.ScheduledFuture
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Gemini Live Translate WebSocket 客户端。
 * - setup 结构照 docs/02-tech-notes.md，字段位置不能动
 * - 单连接约 590s 被服务端 GoAway 断开：505s 主动轮换 + goAway 消息即时轮换
 * - 异常断线：指数退避重连，并把最近 1 秒已发送音频塞回队首弥补断点
 * - 音频块进有界队列，断线期间自动积压、恢复后追发
 */
class GeminiLiveClient(
    private val keyProvider: () -> String,
    private val baseUrl: String,
    private val prompt: String,
    private val targetLang: String = SettingsStore.DEFAULT_TARGET_LANG,
    private val echoTargetLanguage: Boolean = true,
    private val rotateAfterMs: Long = SettingsStore.DEFAULT_ROTATE_SECONDS * 1000L,
    private val listener: Listener,
) {
    interface Listener {
        fun onState(state: String)
        fun onInputText(text: String)
        fun onOutputText(text: String)
    }

    companion object {
        private const val TAG = "GeminiLive"
        private const val MODEL = "models/gemini-3.5-live-translate-preview"
        private const val WS_PATH =
            "/ws/google.ai.generativelanguage.v1beta.GenerativeService.BidiGenerateContent"
        private const val MAX_QUEUE = 200      // 约 20 秒积压上限，超出丢最旧
        private const val OVERLAP_CHUNKS = 10  // 异常断线重发最近 1 秒
    }

    private val http = OkHttpClient.Builder()
        .pingInterval(15, TimeUnit.SECONDS)
        .connectTimeout(15, TimeUnit.SECONDS)
        .build()

    private val queue = LinkedBlockingDeque<ByteArray>()
    private val sentRing = ArrayDeque<ByteArray>()
    private val running = AtomicBoolean(false)
    private val scheduler = Executors.newSingleThreadScheduledExecutor()

    @Volatile private var ws: WebSocket? = null
    @Volatile private var ready = false
    @Volatile private var generation = 0
    @Volatile private var reconnectDelayMs = 1000L
    private var rotateTask: ScheduledFuture<*>? = null
    private var senderThread: Thread? = null

    fun start() {
        if (!running.compareAndSet(false, true)) return
        senderThread = Thread(::senderLoop, "gemini-sender").also { it.start() }
        connect()
    }

    fun stop() {
        if (!running.compareAndSet(true, false)) return
        generation++
        rotateTask?.cancel(false)
        scheduler.shutdownNow()
        runCatching { ws?.close(1000, "bye") }
        ws = null
        ready = false
        senderThread?.interrupt()
        listener.onState("stopped")
    }

    /** 捕获线程调用：塞入一块 100ms/16k/mono 的 PCM。 */
    fun feedChunk(chunk: ByteArray) {
        if (!running.get()) return
        if (queue.size >= MAX_QUEUE) queue.pollFirst()
        queue.offerLast(chunk)
    }

    // ---------- 连接管理 ----------

    private fun connect() {
        if (!running.get()) return
        ready = false
        generation++
        val gen = generation
        listener.onState(if (gen == 1) "connecting" else "reconnecting")
        val key = keyProvider()
        if (key.isEmpty()) {
            listener.onState("error:未配置 API key")
            return
        }
        val url = baseUrl.trimEnd('/') + WS_PATH + "?key=" + key
        val req = Request.Builder().url(url).build()
        ws = http.newWebSocket(req, WsListener(gen))
    }

    private fun rotate() {
        if (!running.get()) return
        Log.i(TAG, "planned rotation")
        listener.onState("rotating")
        val old = ws
        connect() // generation++ 之后旧连接的回调全部作废
        runCatching { old?.close(1000, "rotate") }
    }

    private fun scheduleReconnect(abrupt: Boolean) {
        if (!running.get()) return
        ready = false
        if (abrupt) prependOverlap()
        val d = reconnectDelayMs
        reconnectDelayMs = (reconnectDelayMs * 2).coerceAtMost(15_000L)
        runCatching { scheduler.schedule({ connect() }, d, TimeUnit.MILLISECONDS) }
    }

    private fun prependOverlap() {
        synchronized(sentRing) {
            sentRing.reversed().forEach { queue.offerFirst(it) }
            sentRing.clear()
        }
    }

    // ---------- 收发 ----------

    private inner class WsListener(private val gen: Int) : WebSocketListener() {
        override fun onOpen(webSocket: WebSocket, response: Response) {
            if (gen != generation) return
            webSocket.send(buildSetupJson())
        }

        override fun onMessage(webSocket: WebSocket, text: String) = handle(gen, text)
        override fun onMessage(webSocket: WebSocket, bytes: ByteString) = handle(gen, bytes.utf8())

        override fun onClosing(webSocket: WebSocket, code: Int, reason: String) {
            Log.i(TAG, "onClosing gen=$gen code=$code reason=$reason")
            runCatching { webSocket.close(1000, null) }
        }

        override fun onClosed(webSocket: WebSocket, code: Int, reason: String) {
            if (gen != generation || !running.get()) return
            Log.w(TAG, "closed by server: $code $reason")
            scheduleReconnect(abrupt = false)
        }

        override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) {
            if (gen != generation || !running.get()) return
            Log.w(TAG, "ws failure: ${t.message}")
            listener.onState("error:${t.message ?: "unknown"}")
            scheduleReconnect(abrupt = true)
        }
    }

    private fun handle(gen: Int, text: String) {
        if (gen != generation || !running.get()) return
        val o = try {
            JSONObject(text)
        } catch (e: Exception) {
            Log.w(TAG, "bad json: ${text.take(120)}")
            return
        }
        if (o.has("setupComplete")) {
            ready = true
            reconnectDelayMs = 1000L
            listener.onState("ready")
            rotateTask?.cancel(false)
            if (running.get()) {
                runCatching {
                    rotateTask = scheduler.schedule({ rotate() }, rotateAfterMs, TimeUnit.MILLISECONDS)
                }
            }
            return
        }
        if (o.has("goAway")) {
            Log.i(TAG, "server goAway")
            rotate()
            return
        }
        val sc = o.optJSONObject("serverContent") ?: return
        sc.optJSONObject("inputTranscription")?.optString("text")
            ?.takeIf { it.isNotEmpty() }?.let { listener.onInputText(it) }
        sc.optJSONObject("outputTranscription")?.optString("text")
            ?.takeIf { it.isNotEmpty() }?.let { listener.onOutputText(it) }
        // modelTurn 里的翻译语音块直接忽略，不播放
    }

    private fun buildSetupJson(): String {
        val setup = JSONObject()
            .put("model", MODEL)
            .put(
                "generationConfig", JSONObject()
                    .put("responseModalities", JSONArray().put("AUDIO"))
                    .put(
                        "translationConfig", JSONObject()
                            .put("targetLanguageCode", targetLang)
                            .put("echoTargetLanguage", echoTargetLanguage)
                    )
            )
            .put("inputAudioTranscription", JSONObject())
            .put("outputAudioTranscription", JSONObject())
        if (prompt.isNotBlank()) {
            setup.put(
                "systemInstruction",
                JSONObject().put("parts", JSONArray().put(JSONObject().put("text", prompt)))
            )
        }
        return JSONObject().put("setup", setup).toString()
    }

    private fun senderLoop() {
        while (running.get()) {
            try {
                val chunk = queue.pollFirst(100, TimeUnit.MILLISECONDS) ?: continue
                if (!ready) {
                    queue.offerFirst(chunk)
                    Thread.sleep(60)
                    continue
                }
                val b64 = Base64.encodeToString(chunk, Base64.NO_WRAP)
                val msg =
                    "{\"realtimeInput\":{\"audio\":{\"data\":\"$b64\",\"mimeType\":\"audio/pcm;rate=16000\"}}}"
                val w = ws
                if (w != null && w.send(msg)) {
                    synchronized(sentRing) {
                        sentRing.addLast(chunk)
                        while (sentRing.size > OVERLAP_CHUNKS) sentRing.removeFirst()
                    }
                    StatusBus.chunksSent.incrementAndGet()
                } else {
                    queue.offerFirst(chunk)
                    Thread.sleep(120)
                }
            } catch (e: InterruptedException) {
                return
            } catch (e: Exception) {
                Log.w(TAG, "sender error: ${e.message}")
            }
        }
    }
}
