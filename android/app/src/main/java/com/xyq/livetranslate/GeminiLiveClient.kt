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
import java.util.concurrent.atomic.AtomicInteger

/**
 * Gemini Live Translate WebSocket 客户端。
 * - setup 结构照 docs/02-tech-notes.md，字段位置不能动
 * - 单连接约 590s 被服务端 GoAway 断开：505s 主动轮换 + goAway 消息即时轮换
 * - 异常断线：指数退避重连，并把最近 1 秒已发送音频塞回队首弥补断点
 * - 音频块进有界队列，断线期间自动积压、恢复后追发
 *
 * 并发约定：所有连接生命周期操作（connect / rotate / scheduleReconnect / 看门狗）
 * 一律串行在 scheduler 单线程上执行——WebSocket 读线程（goAway / setupComplete）
 * 只往 scheduler 投递任务，绝不直接改 generation/ws/rotateTask。这样避免定时轮换与
 * goAway 即时轮换跨线程同时进 connect() 造成 generation 竞态、连接分叉、ready 卡死。
 * 另加握手看门狗：连上后 HANDSHAKE_TIMEOUT_MS 内拿不到 setupComplete 就强制重连自愈。
 */
class GeminiLiveClient(
    private val keyProvider: () -> String,
    private val baseUrl: String,
    private val prompt: String,
    private val targetLang: String = TranslationPlan.DEFAULT_TARGET_LANGUAGE,
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
        private const val HANDSHAKE_TIMEOUT_MS = 12_000L // 连上后多久没 setupComplete 就判握手卡死
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
    private val generation = AtomicInteger(0)
    @Volatile private var reconnectDelayMs = 1000L
    // rotateTask / watchdogTask 只在 scheduler 线程与 stop() 里改，volatile 保证可见性
    @Volatile private var rotateTask: ScheduledFuture<*>? = null
    @Volatile private var watchdogTask: ScheduledFuture<*>? = null
    private var senderThread: Thread? = null

    fun start() {
        if (!running.compareAndSet(false, true)) return
        senderThread = Thread(::senderLoop, "gemini-sender").also { it.start() }
        runOnScheduler { connect() }
    }

    fun stop() {
        if (!running.compareAndSet(true, false)) return
        generation.incrementAndGet()
        rotateTask?.cancel(false)
        watchdogTask?.cancel(false)
        scheduler.shutdownNow()
        runCatching { ws?.close(1000, "bye") }
        ws = null
        ready = false
        senderThread?.interrupt()
        listener.onState("stopped")
    }

    /** 把连接生命周期操作投递到 scheduler 单线程串行执行；停机或已关闭则安静丢弃。 */
    private fun runOnScheduler(block: () -> Unit) {
        if (!running.get()) return
        runCatching { scheduler.execute { if (running.get()) block() } }
    }

    /** 捕获线程调用：塞入一块 100ms/16k/mono 的 PCM。 */
    fun feedChunk(chunk: ByteArray) {
        if (!running.get()) return
        if (queue.size >= MAX_QUEUE) queue.pollFirst()
        queue.offerLast(chunk)
    }

    // ---------- 连接管理 ----------

    /** 只在 scheduler 线程调用。 */
    private fun connect() {
        if (!running.get()) return
        ready = false
        rotateTask?.cancel(false) // 取消可能残留的旧轮换任务，防止过期任务乱触发
        val gen = generation.incrementAndGet()
        listener.onState(if (gen == 1) "connecting" else "reconnecting")
        val key = keyProvider()
        if (key.isEmpty()) {
            listener.onState("error:未配置 API key")
            return
        }
        val url = baseUrl.trimEnd('/') + WS_PATH + "?key=" + key
        val req = Request.Builder().url(url).build()
        ws = http.newWebSocket(req, WsListener(gen))
        armWatchdog(gen)
    }

    /** 握手看门狗：到点仍是这一代且没 ready，说明连上却卡在握手，强制重连自愈。 */
    private fun armWatchdog(gen: Int) {
        watchdogTask?.cancel(false)
        watchdogTask = runCatching {
            scheduler.schedule({
                if (running.get() && generation.get() == gen && !ready) {
                    Log.w(TAG, "handshake watchdog fired gen=$gen, forcing reconnect")
                    val old = ws
                    generation.incrementAndGet() // 作废旧连接回调，避免其 onFailure 再触发一次重连
                    runCatching { old?.cancel() }
                    scheduleReconnect(abrupt = true)
                }
            }, HANDSHAKE_TIMEOUT_MS, TimeUnit.MILLISECONDS)
        }.getOrNull()
    }

    /**
     * 轮换。fromGen 是触发时的代际：定时任务与 goAway 可能同时排队，
     * 谁先在 scheduler 上跑谁生效，后到的看到代际已推进就跳过，避免重复轮换。
     */
    private fun rotate(fromGen: Int) {
        if (!running.get()) return
        if (generation.get() != fromGen) return
        Log.i(TAG, "rotation from gen=$fromGen")
        listener.onState("rotating")
        val old = ws
        connect() // generation++ 之后旧连接的回调全部作废，并取消旧 rotateTask
        runCatching { old?.close(1000, "rotate") }
    }

    /** 从 WebSocket 回调线程调用；实际重连排到 scheduler 线程串行执行。 */
    private fun scheduleReconnect(abrupt: Boolean) {
        if (!running.get()) return
        ready = false
        watchdogTask?.cancel(false)
        if (abrupt) prependOverlap()
        val d = reconnectDelayMs
        reconnectDelayMs = (reconnectDelayMs * 2).coerceAtMost(15_000L)
        runCatching { scheduler.schedule({ if (running.get()) connect() }, d, TimeUnit.MILLISECONDS) }
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
            if (gen != generation.get()) return
            webSocket.send(buildSetupJson())
        }

        override fun onMessage(webSocket: WebSocket, text: String) = handle(gen, text)
        override fun onMessage(webSocket: WebSocket, bytes: ByteString) = handle(gen, bytes.utf8())

        override fun onClosing(webSocket: WebSocket, code: Int, reason: String) {
            Log.i(TAG, "onClosing gen=$gen code=$code reason=$reason")
            runCatching { webSocket.close(1000, null) }
        }

        override fun onClosed(webSocket: WebSocket, code: Int, reason: String) {
            if (gen != generation.get() || !running.get()) return
            Log.w(TAG, "closed by server: $code $reason")
            scheduleReconnect(abrupt = false)
        }

        override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) {
            if (gen != generation.get() || !running.get()) return
            Log.w(TAG, "ws failure: ${t.message}")
            listener.onState("error:${t.message ?: "unknown"}")
            scheduleReconnect(abrupt = true)
        }
    }

    private fun handle(gen: Int, text: String) {
        if (gen != generation.get() || !running.get()) return
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
            // 轮换/看门狗任务的重排统一挪到 scheduler 线程，避免与 connect() 抢 rotateTask
            runOnScheduler {
                if (generation.get() != gen) return@runOnScheduler
                watchdogTask?.cancel(false)
                rotateTask?.cancel(false)
                rotateTask = runCatching {
                    scheduler.schedule({ rotate(gen) }, rotateAfterMs, TimeUnit.MILLISECONDS)
                }.getOrNull()
            }
            return
        }
        if (o.has("goAway")) {
            Log.i(TAG, "server goAway")
            runOnScheduler { rotate(gen) }
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
