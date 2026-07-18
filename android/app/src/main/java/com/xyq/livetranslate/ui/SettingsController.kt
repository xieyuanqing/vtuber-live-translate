package com.xyq.livetranslate.ui

import android.annotation.SuppressLint
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.PowerManager
import android.provider.Settings
import android.view.View
import android.widget.Button
import android.widget.EditText
import android.widget.TextView
import com.google.android.material.materialswitch.MaterialSwitch
import com.google.android.material.slider.Slider
import com.xyq.livetranslate.AiTextClient
import com.xyq.livetranslate.FriendGatewayBindingPhase
import com.xyq.livetranslate.FriendGatewayBindingState
import com.xyq.livetranslate.FriendGatewayClient
import com.xyq.livetranslate.FriendGatewayStatus
import com.xyq.livetranslate.FriendGatewayStore
import com.xyq.livetranslate.R
import com.xyq.livetranslate.SettingsStore
import com.xyq.livetranslate.TranslationMode
import com.xyq.livetranslate.TranslationPlanStore

internal data class FriendGatewayBindingActions(
    val bind: (inviteCode: String, appVersion: String, enableFriendOnSuccess: Boolean) -> Unit,
    val clear: () -> Unit,
    val isBinding: () -> Boolean,
)

/** 一次状态采集产生的设置诊断快照；controller 不直接读取 StatusBus。 */
internal data class SettingsDiagnosticsState(
    val serviceRunning: Boolean,
    val captureMode: String,
    val connState: String,
    val currentKeyLabel: String,
    val audioLevelPct: Int,
    val chunksSent: Long,
    val transcriptPath: String,
    val jaTail: String,
    val zhTail: String,
)

internal data class SettingsViews(
    val rowSetTranslate: View,
    val rowSetSubtitle: View,
    val rowSetSceneLibrary: View,
    val rowSetProfileAi: View,
    val rowSetDiagnostics: View,
    val rowSetAbout: View,
    val etApiKeys: EditText,
    val etBaseUrl: EditText,
    val swFriendGateway: MaterialSwitch,
    val etFriendInviteCode: EditText,
    val tvFriendGatewayStatus: TextView,
    val btnBindFriendGateway: Button,
    val btnClearFriendGateway: Button,
    val slFont: Slider,
    val slOpacity: Slider,
    val slLines: Slider,
    val tvFontVal: TextView,
    val tvOpacityVal: TextView,
    val tvLinesVal: TextView,
    val etSecondAiKey: EditText,
    val etSecondAiUrl: EditText,
    val etSecondAiModel: EditText,
    val btnSecondAiFormat: Button,
    val swEchoTarget: MaterialSwitch,
    val slRotate: Slider,
    val slIdle: Slider,
    val slMaxChars: Slider,
    val tvRotateVal: TextView,
    val tvIdleVal: TextView,
    val tvMaxCharsVal: TextView,
    val btnResetTranslate: Button,
    val btnResetSubtitle: Button,
    val btnBattery: Button,
    val tvStatus: TextView,
    val tvAboutVersion: TextView,
    val btnAboutRepo: Button,
    val tvApiStatus: TextView?,
    val tvAudioStatus: TextView?,
    val tvPerfStatus: TextView?,
    val tvCredentialStatus: TextView?,
) {
    companion object {
        fun bind(root: View): SettingsViews {
            fun optionalText(idName: String): TextView? {
                val id = root.resources.getIdentifier(idName, "id", root.context.packageName)
                return if (id == 0) null else root.findViewById(id)
            }
            return SettingsViews(
                rowSetTranslate = root.findViewById(R.id.rowSetTranslate),
                rowSetSubtitle = root.findViewById(R.id.rowSetSubtitle),
                rowSetSceneLibrary = root.findViewById(R.id.rowSetSceneLibrary),
                rowSetProfileAi = root.findViewById(R.id.rowSetProfileAi),
                rowSetDiagnostics = root.findViewById(R.id.rowSetDiagnostics),
                rowSetAbout = root.findViewById(R.id.rowSetAbout),
                etApiKeys = root.findViewById(R.id.etApiKeys),
                etBaseUrl = root.findViewById(R.id.etBaseUrl),
                swFriendGateway = root.findViewById(R.id.swFriendGateway),
                etFriendInviteCode = root.findViewById(R.id.etFriendInviteCode),
                tvFriendGatewayStatus = root.findViewById(R.id.tvFriendGatewayStatus),
                btnBindFriendGateway = root.findViewById(R.id.btnBindFriendGateway),
                btnClearFriendGateway = root.findViewById(R.id.btnClearFriendGateway),
                slFont = root.findViewById(R.id.slFont),
                slOpacity = root.findViewById(R.id.slOpacity),
                slLines = root.findViewById(R.id.slLines),
                tvFontVal = root.findViewById(R.id.tvFontVal),
                tvOpacityVal = root.findViewById(R.id.tvOpacityVal),
                tvLinesVal = root.findViewById(R.id.tvLinesVal),
                etSecondAiKey = root.findViewById(R.id.etSecondAiKey),
                etSecondAiUrl = root.findViewById(R.id.etSecondAiUrl),
                etSecondAiModel = root.findViewById(R.id.etSecondAiModel),
                btnSecondAiFormat = root.findViewById(R.id.btnSecondAiFormat),
                swEchoTarget = root.findViewById(R.id.swEchoTarget),
                slRotate = root.findViewById(R.id.slRotate),
                slIdle = root.findViewById(R.id.slIdle),
                slMaxChars = root.findViewById(R.id.slMaxChars),
                tvRotateVal = root.findViewById(R.id.tvRotateVal),
                tvIdleVal = root.findViewById(R.id.tvIdleVal),
                tvMaxCharsVal = root.findViewById(R.id.tvMaxCharsVal),
                btnResetTranslate = root.findViewById(R.id.btnResetTranslate),
                btnResetSubtitle = root.findViewById(R.id.btnResetSubtitle),
                btnBattery = root.findViewById(R.id.btnBattery),
                tvStatus = root.findViewById(R.id.tvStatus),
                tvAboutVersion = root.findViewById(R.id.tvAboutVersion),
                btnAboutRepo = root.findViewById(R.id.btnAboutRepo),
                tvApiStatus = optionalText("tvApiStatus"),
                tvAudioStatus = optionalText("tvAudioStatus"),
                tvPerfStatus = optionalText("tvPerfStatus"),
                tvCredentialStatus = optionalText("tvCredentialStatus"),
            )
        }
    }
}

internal class SettingsController(
    private val context: Context,
    private val views: SettingsViews,
    private val friendActions: FriendGatewayBindingActions,
    private val openSubPage: (pageId: Int) -> Unit,
    private val openSceneLibrary: (mode: TranslationMode) -> Unit,
    private val onTranslateParamsReset: () -> Unit,
    private val postToUi: (() -> Unit) -> Unit,
    private val isHostActive: () -> Boolean,
    private val launchIntent: (Intent) -> Unit,
    private val toast: (String) -> Unit,
) {
    private var syncingFriendGatewayUi = false

    fun setup() {
        views.rowSetTranslate.setOnClickListener { openSubPage(R.id.pageSettingsTranslate) }
        views.rowSetSubtitle.setOnClickListener { openSubPage(R.id.pageSettingsSubtitle) }
        views.rowSetSceneLibrary.setOnClickListener { openSceneLibrary(TranslationMode.INTERPRETATION) }
        views.rowSetProfileAi.setOnClickListener { openSubPage(R.id.pageSettingsProfileAi) }
        views.rowSetDiagnostics.setOnClickListener { openSubPage(R.id.pageSettingsDiagnostics) }
        views.rowSetAbout.setOnClickListener { openSubPage(R.id.pageSettingsAbout) }

        views.etApiKeys.setText(SettingsStore.apiKeysRaw(context))
        views.etBaseUrl.setText(SettingsStore.baseUrl(context))
        views.etSecondAiKey.setText(SettingsStore.secondAiApiKey(context))
        views.etSecondAiUrl.setText(SettingsStore.secondAiBaseUrl(context))
        views.etSecondAiModel.setText(SettingsStore.secondAiModel(context))
        updateSecondAiFormatLabel()
        views.btnSecondAiFormat.setOnClickListener { toggleSecondAiFormat() }

        setupFriendGatewayUi()
        setupStyleSliders()
        setupParamControls()
        setupAbout()
        views.btnBattery.setOnClickListener { requestBatteryWhitelist() }
    }

    fun persistSecondAiInputs() {
        SettingsStore.saveSecondAiApiKey(context, views.etSecondAiKey.text.toString())
        SettingsStore.saveSecondAiBaseUrl(
            context,
            views.etSecondAiUrl.text.toString().trim().ifEmpty { SettingsStore.DEFAULT_BASE_URL },
        )
        SettingsStore.saveSecondAiModel(
            context,
            views.etSecondAiModel.text.toString().trim().ifEmpty { SettingsStore.secondAiModel(context) },
        )
    }

    fun persistDraftInputs() {
        SettingsStore.saveApiKeys(context, views.etApiKeys.text.toString())
        SettingsStore.saveBaseUrl(
            context,
            views.etBaseUrl.text.toString().trim().ifEmpty { SettingsStore.DEFAULT_BASE_URL },
        )
        persistSecondAiInputs()
    }

    fun renderFriendBindingState(state: FriendGatewayBindingState) {
        val binding = state.phase == FriendGatewayBindingPhase.BINDING
        renderFriendGatewayUi(bindingInProgress = binding)
        when (state.phase) {
            FriendGatewayBindingPhase.BINDING -> {
                views.tvFriendGatewayStatus.text = "正在验证邀请码并绑定当前设备…"
            }
            FriendGatewayBindingPhase.SUCCESS -> {
                views.etFriendInviteCode.setText("")
                if (FriendGatewayStore.isBound(context)) refreshFriendGatewayStatus()
            }
            FriendGatewayBindingPhase.FAILURE -> views.tvFriendGatewayStatus.text = state.message
            FriendGatewayBindingPhase.IDLE -> Unit
        }
    }

    internal fun renderFriendGatewayUiForTest(bindingInProgress: Boolean) {
        renderFriendGatewayUi(bindingInProgress = bindingInProgress)
    }

    fun renderDiagnostics(state: SettingsDiagnosticsState) {
        val level = state.audioLevelPct.coerceIn(0, 100)
        views.tvStatus.text = buildString {
            append(if (state.serviceRunning) "● 运行中" else "○ 未运行")
            if (state.captureMode.isNotEmpty()) {
                append("  模式: ").append(if (state.captureMode == "mic") "同传" else "视频")
            }
            append("  连接: ").append(state.connState)
            if (state.currentKeyLabel.isNotEmpty()) append("  ").append(state.currentKeyLabel)
            append("  音量: ").append(level).append("%")
            append("  已发送: ").append(state.chunksSent).append(" 块\n")
            if (state.transcriptPath.isNotEmpty()) append("记录: ").append(state.transcriptPath).append("\n")
            if (state.jaTail.isNotEmpty()) append("ja: …").append(state.jaTail).append("\n")
            if (state.zhTail.isNotEmpty()) append("zh: …").append(state.zhTail)
        }
        views.tvApiStatus?.text = "连接: ${state.connState}"
        views.tvAudioStatus?.text = "音量: $level%"
        views.tvPerfStatus?.text = "已发送: ${state.chunksSent} 块"
        views.tvCredentialStatus?.text = state.currentKeyLabel.ifBlank { "未选择凭据" }
    }

    private fun setupFriendGatewayUi() {
        views.swFriendGateway.setOnCheckedChangeListener { _, checked ->
            if (syncingFriendGatewayUi) return@setOnCheckedChangeListener
            if (checked) {
                if (!FriendGatewayStore.useFriend(context)) toast("请先输入邀请码并完成绑定")
            } else {
                FriendGatewayStore.usePersonal(context)
            }
            renderFriendGatewayUi()
        }
        views.btnBindFriendGateway.setOnClickListener { bindFriendGateway() }
        views.btnClearFriendGateway.setOnClickListener {
            friendActions.clear()
            views.etFriendInviteCode.setText("")
            toast("已清除本机好友凭据")
        }
        renderFriendGatewayUi()
        if (FriendGatewayStore.isBound(context)) refreshFriendGatewayStatus()
    }

    private fun renderFriendGatewayUi(
        remote: FriendGatewayStatus? = null,
        bindingInProgress: Boolean = friendActions.isBinding(),
    ) {
        val bound = FriendGatewayStore.isBound(context)
        val active = FriendGatewayStore.isActive(context)
        syncingFriendGatewayUi = true
        views.swFriendGateway.isEnabled = bound && !bindingInProgress
        views.swFriendGateway.isChecked = active
        syncingFriendGatewayUi = false
        views.etFriendInviteCode.isEnabled = !bindingInProgress
        views.btnBindFriendGateway.isEnabled = !bindingInProgress
        views.btnClearFriendGateway.visibility = if (bound) View.VISIBLE else View.GONE
        views.btnClearFriendGateway.isEnabled = bound && !bindingInProgress
        views.btnBindFriendGateway.text = if (bound) "重新绑定" else "绑定并启用"
        views.tvFriendGatewayStatus.text = when {
            remote != null && active -> {
                val name = remote.label.ifBlank { "好友测试" }
                "$name 已启用 · 今日实时 ${remote.liveSessions} 次 · 内容分析 ${remote.textRequests} 次"
            }
            active -> "好友测试通道已启用，翻译和内容分析均由服务器提供"
            bound -> "邀请码已绑定，当前仍使用你自己的 API Key"
            else -> "当前使用你自己的 API Key"
        }
    }

    private fun bindFriendGateway() {
        val code = views.etFriendInviteCode.text?.toString().orEmpty().trim()
        if (code.isBlank()) {
            toast("请输入好友邀请码")
            return
        }
        val version = runCatching {
            val info = context.packageManager.getPackageInfo(context.packageName, 0)
            "${info.versionName}(${info.longVersionCode})"
        }.getOrDefault("unknown")
        val enableOnSuccess =
            !FriendGatewayStore.isBound(context) || FriendGatewayStore.isActive(context)
        friendActions.bind(code, version, enableOnSuccess)
    }

    private fun refreshFriendGatewayStatus() {
        val token = FriendGatewayStore.token(context)
        if (token.isBlank()) return
        Thread({
            runCatching { FriendGatewayClient(context).status(token) }
                .onSuccess { status ->
                    postToUi {
                        if (isHostActive() && !friendActions.isBinding()) renderFriendGatewayUi(status)
                    }
                }
                .onFailure { error ->
                    postToUi {
                        if (
                            isHostActive() && !friendActions.isBinding() &&
                            FriendGatewayStore.isActive(context)
                        ) {
                            views.tvFriendGatewayStatus.text =
                                "好友通道验证失败：${error.message ?: "未知错误"}"
                        }
                    }
                }
        }, "friend-gateway-status").start()
    }

    private fun secondAiFormat(): AiTextClient.Format =
        AiTextClient.Format.fromKey(SettingsStore.secondAiFormat(context))

    private fun updateSecondAiFormatLabel() {
        views.btnSecondAiFormat.text = when (secondAiFormat()) {
            AiTextClient.Format.GEMINI -> "Gemini 原生"
            AiTextClient.Format.OPENAI -> "OpenAI 兼容"
        }
    }

    private fun toggleSecondAiFormat() {
        val next = when (secondAiFormat()) {
            AiTextClient.Format.GEMINI -> AiTextClient.Format.OPENAI
            AiTextClient.Format.OPENAI -> AiTextClient.Format.GEMINI
        }
        SettingsStore.saveSecondAiFormat(context, next.key)
        updateSecondAiFormatLabel()
        toast("已切换到 ${next.key} 格式")
    }

    private fun setupStyleSliders() {
        views.slFont.value = SettingsStore.fontSizeSp(context).toFloat().coerceIn(12f, 26f)
        views.slOpacity.value = (SettingsStore.bgOpacityPct(context) / 5 * 5).toFloat().coerceIn(20f, 95f)
        views.slLines.value = SettingsStore.overlayMaxLines(context).toFloat().coerceIn(1f, 3f)
        updateStyleLabels()
        val change = Slider.OnChangeListener { _, _, _ -> updateStyleLabels() }
        val touch = object : Slider.OnSliderTouchListener {
            override fun onStartTrackingTouch(slider: Slider) = Unit
            override fun onStopTrackingTouch(slider: Slider) {
                SettingsStore.saveStyle(
                    context,
                    views.slFont.value.toInt(),
                    views.slOpacity.value.toInt(),
                    views.slLines.value.toInt(),
                )
            }
        }
        listOf(views.slFont, views.slOpacity, views.slLines).forEach {
            it.addOnChangeListener(change)
            it.addOnSliderTouchListener(touch)
        }
    }

    private fun updateStyleLabels() {
        views.tvFontVal.text = "字号 ${views.slFont.value.toInt()}sp"
        views.tvOpacityVal.text = "背景不透明度 ${views.slOpacity.value.toInt()}%"
        views.tvLinesVal.text = "最多行数 ${views.slLines.value.toInt()}"
    }

    private fun setupParamControls() {
        renderParamValues()
        views.swEchoTarget.setOnCheckedChangeListener { _, checked ->
            SettingsStore.saveEchoTargetLanguage(context, checked)
        }
        val change = Slider.OnChangeListener { _, _, _ -> updateParamLabels() }
        val touch = object : Slider.OnSliderTouchListener {
            override fun onStartTrackingTouch(slider: Slider) = Unit
            override fun onStopTrackingTouch(slider: Slider) {
                SettingsStore.saveRotateSeconds(context, views.slRotate.value.toInt())
                SettingsStore.saveStabIdleMs(context, views.slIdle.value.toInt())
                SettingsStore.saveStabMaxChars(context, views.slMaxChars.value.toInt())
            }
        }
        listOf(views.slRotate, views.slIdle, views.slMaxChars).forEach {
            it.addOnChangeListener(change)
            it.addOnSliderTouchListener(touch)
        }
        views.btnResetTranslate.setOnClickListener {
            TranslationPlanStore.resetDraft(context, TranslationMode.INTERPRETATION)
            TranslationPlanStore.resetDraft(context, TranslationMode.VIDEO)
            SettingsStore.saveEchoTargetLanguage(context, true)
            SettingsStore.saveRotateSeconds(context, SettingsStore.DEFAULT_ROTATE_SECONDS)
            renderParamValues()
            onTranslateParamsReset()
            toast("翻译参数已恢复默认，下次开始翻译时生效")
        }
        views.btnResetSubtitle.setOnClickListener {
            SettingsStore.saveStabIdleMs(context, SettingsStore.DEFAULT_STAB_IDLE_MS)
            SettingsStore.saveStabMaxChars(context, SettingsStore.DEFAULT_STAB_MAX_CHARS)
            SettingsStore.saveStyle(
                context,
                SettingsStore.DEFAULT_FONT_SP,
                SettingsStore.DEFAULT_BG_OPACITY,
                SettingsStore.DEFAULT_OVERLAY_LINES,
            )
            views.slFont.value = SettingsStore.DEFAULT_FONT_SP.toFloat()
            views.slOpacity.value = SettingsStore.DEFAULT_BG_OPACITY.toFloat()
            views.slLines.value = SettingsStore.DEFAULT_OVERLAY_LINES.toFloat()
            updateStyleLabels()
            renderParamValues()
            toast("字幕设置已恢复默认")
        }
    }

    private fun renderParamValues() {
        views.swEchoTarget.isChecked = SettingsStore.echoTargetLanguage(context)
        views.slRotate.value = SettingsStore.rotateSeconds(context).toFloat()
        views.slIdle.value = SettingsStore.stabIdleMs(context).toFloat()
        views.slMaxChars.value = SettingsStore.stabMaxChars(context).toFloat()
        updateParamLabels()
    }

    private fun updateParamLabels() {
        views.tvRotateVal.text = "连接主动轮换 ${views.slRotate.value.toInt()} 秒"
        val secs = views.slIdle.value.toInt() / 1000.0
        val secsText = if (secs % 1.0 == 0.0) secs.toInt().toString() else secs.toString()
        views.tvIdleVal.text = "静默 $secsText 秒转正"
        views.tvMaxCharsVal.text = "当前行最长 ${views.slMaxChars.value.toInt()} 字"
    }

    private fun setupAbout() {
        val info = runCatching {
            context.packageManager.getPackageInfo(context.packageName, 0)
        }.getOrNull()
        views.tvAboutVersion.text = if (info != null) {
            "版本 ${info.versionName}（${info.longVersionCode}）"
        } else {
            "版本未知"
        }
        views.btnAboutRepo.setOnClickListener {
            runCatching {
                launchIntent(
                    Intent(
                        Intent.ACTION_VIEW,
                        Uri.parse("https://github.com/xieyuanqing/vtuber-live-translate"),
                    ),
                )
            }.onFailure { toast("没有可用的浏览器") }
        }
    }

    @SuppressLint("BatteryLife")
    private fun requestBatteryWhitelist() {
        val powerManager = context.getSystemService(PowerManager::class.java)
        if (powerManager.isIgnoringBatteryOptimizations(context.packageName)) {
            toast("已在电池白名单里")
            return
        }
        runCatching {
            launchIntent(
                Intent(
                    Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS,
                    Uri.parse("package:${context.packageName}"),
                ),
            )
        }.onFailure {
            runCatching { launchIntent(Intent(Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS)) }
        }
    }
}
