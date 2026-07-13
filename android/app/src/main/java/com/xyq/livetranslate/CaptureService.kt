package com.xyq.livetranslate

import android.app.Activity
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.content.pm.ServiceInfo
import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioPlaybackCaptureConfiguration
import android.media.AudioRecord
import android.media.MediaRecorder
import android.media.projection.MediaProjection
import android.media.projection.MediaProjectionManager
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.os.PowerManager
import android.provider.Settings
import android.util.Log
import androidx.core.app.NotificationCompat
import androidx.core.app.ServiceCompat
import kotlin.math.sqrt

/**
 * 前台服务：整条管线的宿主。
 *
 * 两种音频源共用同一翻译管线：
 * - video：AudioPlaybackCapture 内录 + 悬浮字幕（YouTube / 其他 App）
 * - mic：麦克风同传，App 内字幕为主，有悬浮窗权限时也挂悬浮窗
 *
 * 链路：AudioRecord → PcmProcessor → GeminiLiveClient → SubtitleStabilizer → Overlay / TranscriptLogger
 */
class CaptureService : Service() {

    companion object {
        const val ACTION_START = "com.xyq.livetranslate.START"
        const val ACTION_STOP = "com.xyq.livetranslate.STOP"
        const val EXTRA_RESULT_CODE = "resultCode"
        const val EXTRA_RESULT_DATA = "resultData"
        /** [StatusBus.MODE_VIDEO] 或 [StatusBus.MODE_MIC] */
        const val EXTRA_MODE = "mode"
        private const val TAG = "CaptureService"
        private const val NOTIF_ID = 1
        private const val CHANNEL = "livetranslate"
    }

    private val mainHandler = Handler(Looper.getMainLooper())
    private var projection: MediaProjection? = null
    private var audioRecord: AudioRecord? = null
    private var captureThread: Thread? = null
    private var client: GeminiLiveClient? = null
    private var overlay: SubtitleOverlay? = null
    private var logger: TranscriptLogger? = null

    @Volatile private var capturing = false
    private var activeMode: String = ""
    private val jaTail = StringBuilder()
    private val zhLines = ArrayDeque<String>()
    private var lastConfirmedZh = ""
    private var stabilizer: SubtitleStabilizer? = null
    private var wakeLock: PowerManager.WakeLock? = null

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_START -> start(intent)
            ACTION_STOP -> stopEverything()
        }
        return START_NOT_STICKY
    }

    private fun start(intent: Intent) {
        if (capturing) {
            Log.w(TAG, "already capturing mode=$activeMode, ignore new start")
            return
        }

        val mode = intent.getStringExtra(EXTRA_MODE)?.takeIf { it.isNotBlank() } ?: StatusBus.MODE_VIDEO
        activeMode = mode
        StatusBus.captureMode = mode

        createChannel()
        val fgsType = if (mode == StatusBus.MODE_MIC) {
            if (Build.VERSION.SDK_INT >= 34) {
                ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE
            } else {
                0
            }
        } else {
            ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PROJECTION
        }
        try {
            ServiceCompat.startForeground(this, NOTIF_ID, buildNotification(mode), fgsType)
        } catch (e: Exception) {
            Log.e(TAG, "startForeground failed: ${e.message}")
            StatusBus.connState = "error:前台服务启动失败"
            stopEverything()
            return
        }

        // CPU 保活（屏幕大多亮着，但防 ROM 深度休眠）；上限 4 小时兜底
        wakeLock = getSystemService(PowerManager::class.java)
            .newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "livetranslate:capture").apply {
                setReferenceCounted(false)
                acquire(4 * 60 * 60 * 1000L)
            }

        val record = if (mode == StatusBus.MODE_MIC) {
            createMicAudioRecord()
        } else {
            val proj = obtainProjection(intent) ?: return
            createPlaybackAudioRecord(proj)
        }
        if (record == null) {
            Log.e(TAG, "AudioRecord init failed mode=$mode")
            StatusBus.connState = if (mode == StatusBus.MODE_MIC) {
                "error:麦克风初始化失败"
            } else {
                "error:内录初始化失败"
            }
            stopEverything()
            return
        }
        audioRecord = record

        StatusBus.reset()
        StatusBus.captureMode = mode
        zhLines.clear()
        lastConfirmedZh = ""
        logger = TranscriptLogger(this)
        StatusBus.transcriptPath = logger?.pathHint ?: ""

        val useOverlay = mode == StatusBus.MODE_VIDEO || Settings.canDrawOverlays(this)
        if (useOverlay) {
            overlay = SubtitleOverlay(this)
        }
        stabilizer = SubtitleStabilizer(
            mainHandler,
            idleCommitMs = SettingsStore.stabIdleMs(this).toLong(),
            maxCurrentChars = SettingsStore.stabMaxChars(this),
        ) { confirmed, current ->
            overlay?.maybeReapplyStyle()
            overlay?.setLines(confirmed, current)
            updateZhPanel(confirmed, current)
        }
        if (useOverlay) {
            mainHandler.post { overlay?.show() }
        }

        val c = GeminiLiveClient(
            keyProvider = {
                val ks = SettingsStore.apiKeyList(this)
                if (ks.isEmpty()) "" else ks.random().also { k ->
                    StatusBus.currentKeyLabel = "key#${ks.indexOf(k) + 1}/${ks.size}"
                }
            },
            baseUrl = SettingsStore.baseUrl(this),
            prompt = SettingsStore.composedPrompt(this),
            targetLang = SettingsStore.targetLang(this),
            echoTargetLanguage = SettingsStore.echoTargetLanguage(this),
            rotateAfterMs = SettingsStore.rotateSeconds(this) * 1000L,
            listener = object : GeminiLiveClient.Listener {
                override fun onState(state: String) {
                    StatusBus.connState = state
                    val color = when {
                        state == "ready" -> "#4CAF50"
                        state.startsWith("error") -> "#F44336"
                        state == "stopped" -> "#9E9E9E"
                        else -> "#FFC107"
                    }
                    mainHandler.post { overlay?.setStateColor(color) }
                }

                override fun onInputText(text: String) {
                    logger?.logJa(text)
                    appendTail(jaTail, text)
                    StatusBus.jaTail = jaTail.toString()
                }

                override fun onOutputText(text: String) {
                    logger?.logZh(text)
                    mainHandler.post { stabilizer?.onFragment(text) }
                }
            }
        )
        client = c
        c.start()

        val processor = PcmProcessor(
            record.sampleRate,
            record.channelCount,
        ) { chunk -> c.feedChunk(chunk) }

        capturing = true
        StatusBus.serviceRunning = true
        record.startRecording()
        captureThread = Thread({
            val buf = ShortArray(2048)
            while (capturing) {
                val n = audioRecord?.read(buf, 0, buf.size) ?: break
                if (n > 0) {
                    StatusBus.audioLevelPct = estimateLevelPct(buf, n)
                    processor.feed(buf, n)
                } else if (n < 0) {
                    Log.w(TAG, "AudioRecord.read error $n")
                    break
                }
            }
        }, "capture").also { it.start() }

        Log.i(TAG, "pipeline started mode=$mode rate=${record.sampleRate} ch=${record.channelCount} overlay=$useOverlay")
    }

    /** 视频模式：从 Intent 取出 MediaProjection 授权。失败时会 stopEverything。 */
    private fun obtainProjection(intent: Intent): MediaProjection? {
        val resultCode = intent.getIntExtra(EXTRA_RESULT_CODE, Activity.RESULT_CANCELED)
        @Suppress("DEPRECATION")
        val data = intent.getParcelableExtra<Intent>(EXTRA_RESULT_DATA)
        if (resultCode != Activity.RESULT_OK || data == null) {
            Log.e(TAG, "no media projection grant")
            StatusBus.connState = "error:未获得屏幕捕获授权"
            stopEverything()
            return null
        }

        val mpm = getSystemService(Context.MEDIA_PROJECTION_SERVICE) as MediaProjectionManager
        val proj = try {
            mpm.getMediaProjection(resultCode, data)
        } catch (e: Exception) {
            Log.e(TAG, "getMediaProjection failed: ${e.message}")
            StatusBus.connState = "error:屏幕捕获启动失败"
            stopEverything()
            return null
        }
        projection = proj
        proj.registerCallback(object : MediaProjection.Callback() {
            override fun onStop() {
                Log.w(TAG, "projection revoked by system/user")
                mainHandler.post { stopEverything() }
            }
        }, mainHandler)
        return proj
    }

    private fun appendTail(sb: StringBuilder, t: String) {
        sb.append(t)
        if (sb.length > 100) sb.delete(0, sb.length - 100)
    }

    private fun updateZhPanel(confirmed: String, current: String) {
        val c = confirmed.trim()
        if (c.isNotEmpty() && c != lastConfirmedZh) {
            zhLines.addLast(c)
            lastConfirmedZh = c
            while (zhLines.size > 4) zhLines.removeFirst()
        }
        StatusBus.zhTail = buildString {
            zhLines.forEachIndexed { index, line ->
                if (index > 0) append('\n')
                append(line)
            }
            if (current.isNotBlank()) {
                if (isNotEmpty()) append('\n')
                append('▏').append(current.trim())
            }
        }
    }

    private fun estimateLevelPct(buf: ShortArray, n: Int): Int {
        if (n <= 0) return 0
        var sum = 0.0
        for (i in 0 until n) {
            val v = buf[i] / 32768.0
            sum += v * v
        }
        val rms = sqrt(sum / n)
        return (rms * 180).toInt().coerceIn(0, 100)
    }

    /** 麦克风同传：优先语音识别源 + 16k mono，失败再回退。 */
    private fun createMicAudioRecord(): AudioRecord? {
        if (checkSelfPermission(android.Manifest.permission.RECORD_AUDIO)
            != PackageManager.PERMISSION_GRANTED
        ) return null

        val sources = listOf(
            MediaRecorder.AudioSource.VOICE_RECOGNITION,
            MediaRecorder.AudioSource.VOICE_COMMUNICATION,
            MediaRecorder.AudioSource.MIC,
        )
        val combos = listOf(
            16000 to AudioFormat.CHANNEL_IN_MONO,
            48000 to AudioFormat.CHANNEL_IN_MONO,
            44100 to AudioFormat.CHANNEL_IN_MONO,
            48000 to AudioFormat.CHANNEL_IN_STEREO,
            44100 to AudioFormat.CHANNEL_IN_STEREO,
        )
        for (source in sources) {
            for ((rate, chMask) in combos) {
                try {
                    val minBuf = AudioRecord.getMinBufferSize(rate, chMask, AudioFormat.ENCODING_PCM_16BIT)
                    if (minBuf <= 0) continue
                    val rec = AudioRecord(
                        source,
                        rate,
                        chMask,
                        AudioFormat.ENCODING_PCM_16BIT,
                        minBuf * 4,
                    )
                    if (rec.state == AudioRecord.STATE_INITIALIZED) {
                        Log.i(TAG, "Mic AudioRecord ok: source=$source $rate Hz mask=$chMask")
                        return rec
                    }
                    rec.release()
                } catch (e: Exception) {
                    Log.w(TAG, "mic combo source=$source $rate/$chMask failed: ${e.message}")
                }
            }
        }
        return null
    }

    /** 视频模式：系统内录其他 App 播放声音。 */
    private fun createPlaybackAudioRecord(proj: MediaProjection): AudioRecord? {
        if (checkSelfPermission(android.Manifest.permission.RECORD_AUDIO)
            != PackageManager.PERMISSION_GRANTED
        ) return null

        val config = AudioPlaybackCaptureConfiguration.Builder(proj)
            .addMatchingUsage(AudioAttributes.USAGE_MEDIA)
            .addMatchingUsage(AudioAttributes.USAGE_GAME)
            .addMatchingUsage(AudioAttributes.USAGE_UNKNOWN)
            .build()

        val combos = listOf(
            48000 to AudioFormat.CHANNEL_IN_STEREO,
            44100 to AudioFormat.CHANNEL_IN_STEREO,
            48000 to AudioFormat.CHANNEL_IN_MONO,
            44100 to AudioFormat.CHANNEL_IN_MONO,
        )
        for ((rate, chMask) in combos) {
            try {
                val minBuf = AudioRecord.getMinBufferSize(rate, chMask, AudioFormat.ENCODING_PCM_16BIT)
                if (minBuf <= 0) continue
                val rec = AudioRecord.Builder()
                    .setAudioPlaybackCaptureConfig(config)
                    .setAudioFormat(
                        AudioFormat.Builder()
                            .setEncoding(AudioFormat.ENCODING_PCM_16BIT)
                            .setSampleRate(rate)
                            .setChannelMask(chMask)
                            .build()
                    )
                    .setBufferSizeInBytes(minBuf * 4)
                    .build()
                if (rec.state == AudioRecord.STATE_INITIALIZED) {
                    Log.i(TAG, "Playback AudioRecord ok: $rate Hz mask=$chMask")
                    return rec
                }
                rec.release()
            } catch (e: Exception) {
                Log.w(TAG, "playback combo $rate/$chMask failed: ${e.message}")
            }
        }
        return null
    }

    private fun stopEverything() {
        capturing = false
        runCatching { captureThread?.join(500) }
        captureThread = null
        runCatching { audioRecord?.stop() }
        runCatching { audioRecord?.release() }
        audioRecord = null
        runCatching { projection?.stop() }
        projection = null
        client?.stop()
        client = null
        logger?.close()
        logger = null
        mainHandler.post {
            stabilizer?.reset()
            stabilizer = null
            overlay?.hide()
            overlay = null
        }
        runCatching { wakeLock?.release() }
        wakeLock = null
        activeMode = ""
        StatusBus.serviceRunning = false
        StatusBus.captureMode = ""
        StatusBus.audioLevelPct = 0
        if (!StatusBus.connState.startsWith("error")) StatusBus.connState = "idle"
        ServiceCompat.stopForeground(this, ServiceCompat.STOP_FOREGROUND_REMOVE)
        stopSelf()
    }

    override fun onDestroy() {
        if (capturing) stopEverything()
        super.onDestroy()
    }

    private fun createChannel() {
        val nm = getSystemService(NotificationManager::class.java)
        nm.createNotificationChannel(
            NotificationChannel(CHANNEL, "实时翻译", NotificationManager.IMPORTANCE_LOW)
        )
    }

    private fun buildNotification(mode: String): Notification {
        val stopIntent = PendingIntent.getService(
            this, 1,
            Intent(this, CaptureService::class.java).setAction(ACTION_STOP),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )
        val openIntent = PendingIntent.getActivity(
            this, 2,
            Intent(this, MainActivity::class.java),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )
        val title = if (mode == StatusBus.MODE_MIC) "同传运行中" else "视频字幕运行中"
        val text = if (mode == StatusBus.MODE_MIC) {
            "正在用麦克风实时同传"
        } else {
            "正在捕获系统音频并实时翻译"
        }
        return NotificationCompat.Builder(this, CHANNEL)
            .setSmallIcon(R.drawable.ic_stat_subtitle)
            .setContentTitle(title)
            .setContentText(text)
            .setOngoing(true)
            .setContentIntent(openIntent)
            .addAction(0, "停止", stopIntent)
            .build()
    }
}
