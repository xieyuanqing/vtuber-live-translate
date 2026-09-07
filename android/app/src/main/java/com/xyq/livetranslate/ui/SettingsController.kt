package com.xyq.livetranslate.ui

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.Color
import android.graphics.drawable.GradientDrawable
import androidx.core.view.ViewCompat
import androidx.core.widget.doAfterTextChanged
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
import com.google.android.material.textfield.MaterialAutoCompleteTextView
import com.xyq.livetranslate.AiTextClient
import com.xyq.livetranslate.R
import com.xyq.livetranslate.SettingsStore
import com.xyq.livetranslate.TranslationMode

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
    val btnConnectionOptions: Button,
    val connectionOptions: View,
    val btnTranslateAdvanced: Button,
    val translateAdvanced: View,
    val btnSubtitleAdvanced: Button,
    val subtitleAdvanced: View,
    val tvSubtitlePreview: TextView,
    val etApiKeys: EditText,
    val etBaseUrl: EditText,
    val slFont: Slider,
    val slOpacity: Slider,
    val slLines: Slider,
    val tvFontVal: TextView,
    val tvOpacityVal: TextView,
    val tvLinesVal: TextView,
    val etSecondAiKey: EditText,
    val etSecondAiUrl: EditText,
    val etSecondAiModel: MaterialAutoCompleteTextView,
    val btnRefreshSecondAiModels: Button,
    val tvSecondAiModelsHint: TextView,
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
    val swAutoCheckUpdate: MaterialSwitch,
    val btnCheckUpdate: Button,
    val tvUpdateStatus: TextView,
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
                btnConnectionOptions = root.findViewById(R.id.btnConnectionOptions),
                connectionOptions = root.findViewById(R.id.connectionOptions),
                btnTranslateAdvanced = root.findViewById(R.id.btnTranslateAdvanced),
                translateAdvanced = root.findViewById(R.id.translateAdvanced),
                btnSubtitleAdvanced = root.findViewById(R.id.btnSubtitleAdvanced),
                subtitleAdvanced = root.findViewById(R.id.subtitleAdvanced),
                tvSubtitlePreview = root.findViewById(R.id.tvSubtitlePreview),
                etApiKeys = root.findViewById(R.id.etApiKeys),
                etBaseUrl = root.findViewById(R.id.etBaseUrl),
                slFont = root.findViewById(R.id.slFont),
                slOpacity = root.findViewById(R.id.slOpacity),
                slLines = root.findViewById(R.id.slLines),
                tvFontVal = root.findViewById(R.id.tvFontVal),
                tvOpacityVal = root.findViewById(R.id.tvOpacityVal),
                tvLinesVal = root.findViewById(R.id.tvLinesVal),
                etSecondAiKey = root.findViewById(R.id.etSecondAiKey),
                etSecondAiUrl = root.findViewById(R.id.etSecondAiUrl),
                etSecondAiModel = root.findViewById(R.id.etSecondAiModel),
                btnRefreshSecondAiModels = root.findViewById(R.id.btnRefreshSecondAiModels),
                tvSecondAiModelsHint = root.findViewById(R.id.tvSecondAiModelsHint),
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
                swAutoCheckUpdate = root.findViewById(R.id.swAutoCheckUpdate),
                btnCheckUpdate = root.findViewById(R.id.btnCheckUpdate),
                tvUpdateStatus = root.findViewById(R.id.tvUpdateStatus),
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
    private val openSubPage: (pageId: Int) -> Unit,
    private val openSceneLibrary: (mode: TranslationMode) -> Unit,
    private val postToUi: (() -> Unit) -> Unit,
    private val isHostActive: () -> Boolean,
    private val launchIntent: (Intent) -> Unit,
    private val toast: (String) -> Unit,
    private val onCheckUpdate: () -> Unit = {},
) {
    private var syncingAutoCheckUpdateUi = false

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
        setupSecondAiModelDropdown()

        setupDisclosure(views.btnConnectionOptions, views.connectionOptions, "自定义服务地址")
        setupDisclosure(views.btnTranslateAdvanced, views.translateAdvanced, "高级翻译参数")
        setupDisclosure(views.btnSubtitleAdvanced, views.subtitleAdvanced, "高级断句参数")
        setupStyleSliders()
        setupParamControls()
        setupAbout()
        views.btnBattery.setOnClickListener { requestBatteryWhitelist() }
    }

    private fun setupDisclosure(button: Button, content: View, title: String) {
        fun render() {
            val expanded = content.visibility == View.VISIBLE
            button.text = "$title · ${if (expanded) "收起" else "展开"}"
            ViewCompat.setStateDescription(button, if (expanded) "已展开" else "已折叠")
        }
        render()
        button.setOnClickListener {
            content.visibility = if (content.visibility == View.VISIBLE) View.GONE else View.VISIBLE
            render()
        }
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

    private fun secondAiFormat(): AiTextClient.Format =
        AiTextClient.Format.fromKey(SettingsStore.secondAiFormat(context))

    private fun updateSecondAiFormatLabel() {
        views.btnSecondAiFormat.text = when (secondAiFormat()) {
            AiTextClient.Format.GEMINI -> "Gemini 原生"
            AiTextClient.Format.OPENAI -> "OpenAI 兼容"
        }
    }

    private var secondAiModelsFetched = false
    private var secondAiModelsFetching = false
    private var secondAiModelsRevision = 0

    private fun setupSecondAiModelDropdown() {
        views.etSecondAiModel.setSimpleItems(emptyArray())
        views.etSecondAiKey.doAfterTextChanged { invalidateSecondAiModels() }
        views.etSecondAiUrl.doAfterTextChanged { invalidateSecondAiModels() }
        views.etSecondAiModel.setOnClickListener {
            if (!secondAiModelsFetched && !secondAiModelsFetching) {
                fetchSecondAiModels(lazy = true)
            }
            views.etSecondAiModel.showDropDown()
        }
        views.btnRefreshSecondAiModels.setOnClickListener { fetchSecondAiModels(lazy = false) }
    }

    private fun fetchSecondAiModels(lazy: Boolean) {
        if (secondAiModelsFetching) return
        // 使用刚输入的配置，避免首次填写 Key 后还要离开页面才能刷新。
        persistSecondAiInputs()
        val apiKey = SettingsStore.secondAiApiKey(context)
        if (apiKey.isBlank()) {
            renderSecondAiModelsHint(true, "未填写 API Key，无法拉取模型列表，请手动输入")
            return
        }
        val baseUrl = SettingsStore.secondAiBaseUrl(context)
        val format = secondAiFormat()
        val revision = secondAiModelsRevision
        secondAiModelsFetching = true
        views.btnRefreshSecondAiModels.isEnabled = false
        if (lazy) renderSecondAiModelsHint(false, "正在拉取模型列表…")
        Thread({
            val result = runCatching {
                AiTextClient.listModels(
                    baseUrl = baseUrl,
                    apiKey = apiKey,
                    format = format,
                )
            }
            postToUi {
                secondAiModelsFetching = false
                if (!isHostActive()) return@postToUi
                views.btnRefreshSecondAiModels.isEnabled = true
                if (revision != secondAiModelsRevision) return@postToUi
                result.onSuccess { models ->
                    secondAiModelsFetched = true
                    views.etSecondAiModel.setSimpleItems(models.toTypedArray())
                    val empty = models.isEmpty()
                    renderSecondAiModelsHint(
                        empty,
                        if (empty) "远端未返回模型，请自行输入模型名" else "已加载 ${models.size} 个模型，没有合适的可手动输入",
                    )
                }.onFailure { error ->
                    renderSecondAiModelsHint(true, "拉取失败：${error.message ?: "请检查 Key/Base URL"}，可手动输入")
                }
            }
        }, "ai-models-fetch").start()
    }

    private fun renderSecondAiModelsHint(warn: Boolean, message: String) {
        views.tvSecondAiModelsHint.apply {
            text = message
            visibility = if (message.isBlank()) View.GONE else View.VISIBLE
            setTextColor(context.getColor(if (warn) R.color.warning else R.color.text_muted))
        }
    }

    private fun invalidateSecondAiModels() {
        secondAiModelsRevision++
        secondAiModelsFetched = false
        views.etSecondAiModel.setSimpleItems(emptyArray())
        renderSecondAiModelsHint(false, "配置已修改，请刷新模型列表，也可手动输入")
    }

    private fun toggleSecondAiFormat() {
        val next = when (secondAiFormat()) {
            AiTextClient.Format.GEMINI -> AiTextClient.Format.OPENAI
            AiTextClient.Format.OPENAI -> AiTextClient.Format.GEMINI
        }
        SettingsStore.saveSecondAiFormat(context, next.key)
        updateSecondAiFormatLabel()
        invalidateSecondAiModels()
        toast("已切换到 ${next.key} 格式")
    }

    private fun setupStyleSliders() {
        views.slFont.value = SettingsStore.fontSizeSp(context).toFloat().coerceIn(12f, 26f)
        views.slOpacity.value = SettingsStore.bgOpacityPct(context).toFloat().coerceIn(20f, 95f)
        views.slLines.value = SettingsStore.overlayMaxLines(context).toFloat().coerceIn(1f, 3f)
        updateStyleLabels()
        val change = Slider.OnChangeListener { _, _, fromUser ->
            updateStyleLabels()
            if (fromUser) SettingsStore.saveStyle(
                context,
                views.slFont.value.toInt(),
                views.slOpacity.value.toInt(),
                views.slLines.value.toInt(),
            )
        }
        listOf(views.slFont, views.slOpacity, views.slLines).forEach {
            it.addOnChangeListener(change)
        }
    }

    private fun updateStyleLabels() {
        views.tvFontVal.text = "字号 ${views.slFont.value.toInt()}sp"
        views.tvOpacityVal.text = "背景不透明度 ${views.slOpacity.value.toInt()}%"
        views.tvLinesVal.text = "最多行数 ${views.slLines.value.toInt()}"
        views.tvSubtitlePreview.apply {
            textSize = views.slFont.value
            maxLines = views.slLines.value.toInt()
            background = GradientDrawable().apply {
                cornerRadius = 22f * context.resources.displayMetrics.density
                setColor(Color.argb((views.slOpacity.value * 255 / 100).toInt(), 20, 29, 43))
            }
        }
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
            SettingsStore.saveEchoTargetLanguage(context, true)
            SettingsStore.saveRotateSeconds(context, SettingsStore.DEFAULT_ROTATE_SECONDS)
            renderParamValues()
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
        views.tvIdleVal.text = "停顿 $secsText 秒后确认字幕"
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
        syncingAutoCheckUpdateUi = true
        views.swAutoCheckUpdate.isChecked = SettingsStore.autoCheckUpdate(context)
        syncingAutoCheckUpdateUi = false
        views.swAutoCheckUpdate.setOnCheckedChangeListener { _, checked ->
            if (syncingAutoCheckUpdateUi) return@setOnCheckedChangeListener
            SettingsStore.saveAutoCheckUpdate(context, checked)
            toast(if (checked) "已开启启动时检查更新" else "已关闭启动时检查更新")
        }
        views.btnCheckUpdate.setOnClickListener { onCheckUpdate() }
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

    fun renderUpdateStatus(message: String?) {
        syncingAutoCheckUpdateUi = true
        views.swAutoCheckUpdate.isChecked = SettingsStore.autoCheckUpdate(context)
        syncingAutoCheckUpdateUi = false
        if (message.isNullOrBlank()) {
            views.tvUpdateStatus.visibility = View.GONE
            views.tvUpdateStatus.text = ""
        } else {
            views.tvUpdateStatus.visibility = View.VISIBLE
            views.tvUpdateStatus.text = message
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
