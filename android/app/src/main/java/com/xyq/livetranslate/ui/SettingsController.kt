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
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.Button
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.TextView
import com.google.android.material.button.MaterialButton
import com.google.android.material.button.MaterialButtonToggleGroup
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.google.android.material.materialswitch.MaterialSwitch
import com.google.android.material.slider.Slider
import com.google.android.material.textfield.TextInputLayout
import com.xyq.livetranslate.AiTextClient
import com.xyq.livetranslate.GeminiLiveClient
import com.xyq.livetranslate.OpenCodeZenCatalog
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
    val btnApplyApiKey: Button,
    val btnViewApiKeyTutorial: Button,
    val tvFreeQuotaHint: TextView,
    val btnPasteApiKey: Button,
    val btnTestTranslateConnection: Button,
    val tvTranslateTestStatus: TextView,
    val btnConnectionOptions: Button,
    val connectionOptions: View,
    val btnTranslateAdvanced: Button,
    val translateAdvanced: View,
    val btnSubtitleAdvanced: Button,
    val subtitleAdvanced: View,
    val tvSubtitlePreview: TextView,
    val subtitlePreviewStage: View,
    val subtitlePreviewStageToggle: MaterialButtonToggleGroup,
    val btnSubtitlePreviewLight: MaterialButton,
    val etApiKeys: EditText,
    val etBaseUrl: EditText,
    val slFont: Slider,
    val slOpacity: Slider,
    val slLines: Slider,
    val tvFontVal: TextView,
    val tvOpacityVal: TextView,
    val tvLinesVal: TextView,
    val toggleSecondAiService: MaterialButtonToggleGroup,
    val btnSecondAiServiceGemini: MaterialButton,
    val btnSecondAiServiceZen: MaterialButton,
    val btnSecondAiServiceCustom: MaterialButton,
    val containerGeminiActions: View,
    val btnUseTranslateKeyForSecondAi: Button,
    val btnSecondAiApplyKey: Button,
    val btnSecondAiTutorial: Button,
    val btnPasteSecondAiKey: Button,
    val containerZenInfo: View,
    val btnZenDocLink: Button,
    val btnSecondAiSwitchService: Button,
    val containerCustomFormat: View,
    val tilSecondAiKey: View,
    val tilSecondAiUrl: View,
    val tilSecondAiModel: TextInputLayout,
    val etSecondAiKey: EditText,
    val etSecondAiUrl: EditText,
    val etSecondAiModel: EditText,
    val btnRefreshSecondAiModels: Button,
    val tvSecondAiModelsHint: TextView,
    val secondAiFormatToggle: MaterialButtonToggleGroup,
    val btnSecondAiFormatGemini: MaterialButton,
    val btnSecondAiFormatOpenAi: MaterialButton,
    val btnTestSecondAi: Button,
    val tvSecondAiTestStatus: TextView,
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
                btnApplyApiKey = root.findViewById(R.id.btnApplyApiKey),
                btnViewApiKeyTutorial = root.findViewById(R.id.btnViewApiKeyTutorial),
                tvFreeQuotaHint = root.findViewById(R.id.tvFreeQuotaHint),
                btnPasteApiKey = root.findViewById(R.id.btnPasteApiKey),
                btnTestTranslateConnection = root.findViewById(R.id.btnTestTranslateConnection),
                tvTranslateTestStatus = root.findViewById(R.id.tvTranslateTestStatus),
                btnConnectionOptions = root.findViewById(R.id.btnConnectionOptions),
                connectionOptions = root.findViewById(R.id.connectionOptions),
                btnTranslateAdvanced = root.findViewById(R.id.btnTranslateAdvanced),
                translateAdvanced = root.findViewById(R.id.translateAdvanced),
                btnSubtitleAdvanced = root.findViewById(R.id.btnSubtitleAdvanced),
                subtitleAdvanced = root.findViewById(R.id.subtitleAdvanced),
                tvSubtitlePreview = root.findViewById(R.id.tvSubtitlePreview),
                subtitlePreviewStage = root.findViewById(R.id.subtitlePreviewStage),
                subtitlePreviewStageToggle = root.findViewById(R.id.toggleSubtitlePreviewStage),
                btnSubtitlePreviewLight = root.findViewById(R.id.btnSubtitlePreviewLight),
                etApiKeys = root.findViewById(R.id.etApiKeys),
                etBaseUrl = root.findViewById(R.id.etBaseUrl),
                slFont = root.findViewById(R.id.slFont),
                slOpacity = root.findViewById(R.id.slOpacity),
                slLines = root.findViewById(R.id.slLines),
                tvFontVal = root.findViewById(R.id.tvFontVal),
                tvOpacityVal = root.findViewById(R.id.tvOpacityVal),
                tvLinesVal = root.findViewById(R.id.tvLinesVal),
                toggleSecondAiService = root.findViewById(R.id.toggleSecondAiService),
                btnSecondAiServiceGemini = root.findViewById(R.id.btnSecondAiServiceGemini),
                btnSecondAiServiceZen = root.findViewById(R.id.btnSecondAiServiceZen),
                btnSecondAiServiceCustom = root.findViewById(R.id.btnSecondAiServiceCustom),
                containerGeminiActions = root.findViewById(R.id.containerGeminiActions),
                btnUseTranslateKeyForSecondAi = root.findViewById(R.id.btnUseTranslateKeyForSecondAi),
                btnSecondAiApplyKey = root.findViewById(R.id.btnSecondAiApplyKey),
                btnSecondAiTutorial = root.findViewById(R.id.btnSecondAiTutorial),
                btnPasteSecondAiKey = root.findViewById(R.id.btnPasteSecondAiKey),
                containerZenInfo = root.findViewById(R.id.containerZenInfo),
                btnZenDocLink = root.findViewById(R.id.btnZenDocLink),
                btnSecondAiSwitchService = root.findViewById(R.id.btnSecondAiSwitchService),
                containerCustomFormat = root.findViewById(R.id.containerCustomFormat),
                tilSecondAiKey = root.findViewById(R.id.tilSecondAiKey),
                tilSecondAiUrl = root.findViewById(R.id.tilSecondAiUrl),
                tilSecondAiModel = root.findViewById(R.id.tilSecondAiModel),
                etSecondAiKey = root.findViewById(R.id.etSecondAiKey),
                etSecondAiUrl = root.findViewById(R.id.etSecondAiUrl),
                etSecondAiModel = root.findViewById(R.id.etSecondAiModel),
                btnRefreshSecondAiModels = root.findViewById(R.id.btnRefreshSecondAiModels),
                tvSecondAiModelsHint = root.findViewById(R.id.tvSecondAiModelsHint),
                secondAiFormatToggle = root.findViewById(R.id.toggleSecondAiFormat),
                btnSecondAiFormatGemini = root.findViewById(R.id.btnSecondAiFormatGemini),
                btnSecondAiFormatOpenAi = root.findViewById(R.id.btnSecondAiFormatOpenAi),
                btnTestSecondAi = root.findViewById(R.id.btnTestSecondAi),
                tvSecondAiTestStatus = root.findViewById(R.id.tvSecondAiTestStatus),
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

        // 翻译服务
        views.etApiKeys.setText(SettingsStore.apiKeysRaw(context))
        views.etBaseUrl.setText(SettingsStore.baseUrl(context))
        views.btnApplyApiKey.setOnClickListener { openExternalUrl("https://aistudio.google.com/apikey") }
        views.btnViewApiKeyTutorial.setOnClickListener { showApiKeyTutorialDialog() }
        views.btnPasteApiKey.setOnClickListener { pasteTranslateApiKey() }
        views.btnTestTranslateConnection.setOnClickListener { testTranslateConnection() }
        views.etApiKeys.doAfterTextChanged { invalidateTranslateTestStatus() }
        views.etBaseUrl.doAfterTextChanged { invalidateTranslateTestStatus() }

        // 第二 AI（背景分析）
        setupSecondAiServiceToggle()
        setupSecondAiFormatToggle()
        setupSecondAiModelPicker()
        views.btnUseTranslateKeyForSecondAi.setOnClickListener { useTranslateKeyForSecondAi() }
        views.btnSecondAiApplyKey.setOnClickListener { openExternalUrl("https://aistudio.google.com/apikey") }
        views.btnSecondAiTutorial.setOnClickListener { showApiKeyTutorialDialog() }
        views.btnPasteSecondAiKey.setOnClickListener { pasteSecondAiKey() }
        views.btnZenDocLink.setOnClickListener { openExternalUrl(OpenCodeZenCatalog.DOCS_URL) }
        views.btnSecondAiSwitchService.setOnClickListener { showSwitchServiceDialog() }
        views.btnTestSecondAi.setOnClickListener { testSecondAi() }

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
        when (SettingsStore.secondAiService(context)) {
            SettingsStore.SERVICE_GEMINI -> {
                SettingsStore.saveSecondAiGeminiApiKey(context, views.etSecondAiKey.text?.toString().orEmpty())
                SettingsStore.saveSecondAiGeminiBaseUrl(
                    context,
                    views.etSecondAiUrl.text?.toString().orEmpty().trim().ifEmpty { SettingsStore.DEFAULT_BASE_URL },
                )
                SettingsStore.saveSecondAiGeminiModel(
                    context,
                    views.etSecondAiModel.text?.toString().orEmpty().trim().ifEmpty { SettingsStore.DEFAULT_GEMINI_MODEL },
                )
            }
            SettingsStore.SERVICE_OPENCODE_ZEN -> {
                SettingsStore.saveSecondAiZenModel(
                    context,
                    views.etSecondAiModel.text?.toString().orEmpty().trim().ifEmpty { OpenCodeZenCatalog.CANDIDATE_MODEL },
                )
            }
            SettingsStore.SERVICE_CUSTOM -> {
                SettingsStore.saveSecondAiCustomApiKey(context, views.etSecondAiKey.text?.toString().orEmpty())
                SettingsStore.saveSecondAiCustomBaseUrl(
                    context,
                    views.etSecondAiUrl.text?.toString().orEmpty().trim(),
                )
                SettingsStore.saveSecondAiCustomModel(
                    context,
                    views.etSecondAiModel.text?.toString().orEmpty().trim(),
                )
                val format = if (views.secondAiFormatToggle.checkedButtonId == views.btnSecondAiFormatOpenAi.id) {
                    "openai"
                } else {
                    "gemini"
                }
                SettingsStore.saveSecondAiCustomFormat(context, format)
            }
        }
    }

    fun persistDraftInputs() {
        SettingsStore.saveApiKeys(context, views.etApiKeys.text?.toString().orEmpty())
        SettingsStore.saveBaseUrl(
            context,
            views.etBaseUrl.text?.toString().orEmpty().trim().ifEmpty { SettingsStore.DEFAULT_BASE_URL },
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

    // ================= 翻译连接与教程 =================

    private fun openExternalUrl(url: String) {
        runCatching {
            launchIntent(Intent(Intent.ACTION_VIEW, Uri.parse(url)))
        }.onFailure {
            toast("没有可用的浏览器")
        }
    }

    private fun showApiKeyTutorialDialog() {
        val message = "1. 登录 Google 账号并访问 Google AI Studio\n" +
            "2. 创建或选择已有 Google Cloud 项目\n" +
            "3. 创建 API 密钥并复制\n" +
            "4. 返回本应用点击「粘贴 Key」或直接填入\n\n" +
            "注：提供免费额度，实际额度以 Google 为准。"

        MaterialAlertDialogBuilder(context)
            .setTitle(R.string.dialog_tutorial_title)
            .setMessage(message)
            .setPositiveButton(R.string.dialog_btn_go_apply) { _, _ ->
                openExternalUrl("https://aistudio.google.com/apikey")
            }
            .setNeutralButton("官方与定价") { _, _ ->
                showTutorialLinksDialog()
            }
            .setNegativeButton(R.string.dialog_btn_close, null)
            .show()
    }

    private fun showTutorialLinksDialog() {
        val items = arrayOf("查看官方说明 (ai.google.dev)", "查看定价说明 (ai.google.dev)")
        MaterialAlertDialogBuilder(context)
            .setTitle("官方文档与定价")
            .setItems(items) { _, which ->
                when (which) {
                    0 -> openExternalUrl("https://ai.google.dev/gemini-api/docs/api-key")
                    1 -> openExternalUrl("https://ai.google.dev/gemini-api/docs/pricing")
                }
            }
            .setNegativeButton(R.string.dialog_btn_close, null)
            .show()
    }

    private fun getClipboardText(): String? {
        val clipboard = context.getSystemService(Context.CLIPBOARD_SERVICE) as? android.content.ClipboardManager
        val clip = clipboard?.primaryClip ?: return null
        if (clip.itemCount <= 0) return null
        return clip.getItemAt(0)?.text?.toString()?.trim()
    }

    private fun pasteTranslateApiKey() {
        val text = getClipboardText()
        if (text.isNullOrBlank()) {
            toast("剪贴板中没有文本")
            return
        }
        views.etApiKeys.setText(text)
        SettingsStore.saveApiKeys(context, text)
        invalidateTranslateTestStatus()
        toast("已从剪贴板粘贴 Key")
    }

    private fun pasteSecondAiKey() {
        val text = getClipboardText()
        if (text.isNullOrBlank()) {
            toast("剪贴板中没有文本")
            return
        }
        views.etSecondAiKey.setText(text)
        persistSecondAiInputs()
        invalidateSecondAiModels()
        toast("已从剪贴板粘贴 Key")
    }

    private fun useTranslateKeyForSecondAi() {
        val raw = views.etApiKeys.text?.toString().orEmpty().trim()
        val firstKey = SettingsStore.extractFirstApiKey(raw)
        if (firstKey.isEmpty()) {
            toast("请先在翻译服务中填写 API Key")
            return
        }
        views.etSecondAiKey.setText(firstKey)
        SettingsStore.saveSecondAiGeminiApiKey(context, firstKey)
        invalidateSecondAiModels()
        if (raw.contains(',')) {
            toast("已复制首个 Gemini Key（多 Key 仅取第一个）")
        } else {
            toast("已复制翻译服务的 Gemini Key")
        }
    }

    private var translateTesting = false

    private fun testTranslateConnection() {
        if (translateTesting) return
        val baseUrl = views.etBaseUrl.text?.toString().orEmpty().trim().ifEmpty { SettingsStore.DEFAULT_BASE_URL }
        val apiKey = SettingsStore.extractFirstApiKey(views.etApiKeys.text?.toString().orEmpty())
        if (apiKey.isEmpty()) {
            renderTranslateTestStatus(true, "请先填写 Gemini API Key")
            views.etApiKeys.requestFocus()
            return
        }
        translateTesting = true
        setTranslateTestBusy(true)
        val testingMsg = context.getString(R.string.live_model_test_testing, GeminiLiveClient.MODEL)
        renderTranslateTestStatus(false, testingMsg)

        Thread({
            val result = runCatching {
                GeminiLiveClient.probeLive(
                    baseUrl = baseUrl,
                    apiKey = apiKey,
                    timeoutMs = 10_000L,
                )
            }
            postToUi {
                translateTesting = false
                if (!isHostActive()) return@postToUi
                setTranslateTestBusy(false)
                result.onSuccess { msg ->
                    renderTranslateTestStatus(false, msg)
                }.onFailure { err ->
                    val sanitized = AiTextClient.sanitizeError(err.message ?: "连接失败")
                    renderTranslateTestStatus(true, "测试失败：$sanitized")
                }
            }
        }, "live-probe").start()
    }

    private fun invalidateTranslateTestStatus() {
        views.tvTranslateTestStatus.visibility = View.GONE
        views.tvTranslateTestStatus.text = ""
    }

    private fun setTranslateTestBusy(busy: Boolean) {
        views.btnTestTranslateConnection.isEnabled = !busy
        views.btnTestTranslateConnection.text = if (busy) "正在测试…" else context.getString(R.string.btn_test_translate_connection)
    }

    private fun renderTranslateTestStatus(warn: Boolean, message: String) {
        views.tvTranslateTestStatus.apply {
            text = message
            visibility = if (message.isBlank()) View.GONE else View.VISIBLE
            setTextColor(context.getColor(if (warn) R.color.warning else R.color.brand))
        }
    }

    // ================= 第二 AI（背景分析）服务切换 =================

    private fun secondAiFormat(): AiTextClient.Format =
        AiTextClient.Format.fromKey(SettingsStore.secondAiFormat(context))

    private var syncingSecondAiFormatUi = false
    private var syncingSecondAiServiceUi = false
    private var cachedSecondAiModels: List<String> = emptyList()
    private var secondAiModelsFetching = false
    private var secondAiModelsRevision = 0
    private var secondAiTesting = false

    private fun setupSecondAiServiceToggle() {
        renderSecondAiServiceUi(SettingsStore.secondAiService(context))
        views.toggleSecondAiService.addOnButtonCheckedListener { _, checkedId, isChecked ->
            if (!isChecked || syncingSecondAiServiceUi) return@addOnButtonCheckedListener
            val newService = when (checkedId) {
                views.btnSecondAiServiceZen.id -> SettingsStore.SERVICE_OPENCODE_ZEN
                views.btnSecondAiServiceCustom.id -> SettingsStore.SERVICE_CUSTOM
                else -> SettingsStore.SERVICE_GEMINI
            }
            if (newService == SettingsStore.secondAiService(context)) return@addOnButtonCheckedListener
            persistSecondAiInputs()
            SettingsStore.saveSecondAiService(context, newService)
            renderSecondAiServiceUi(newService)
            invalidateSecondAiModels()
        }
    }

    private fun renderSecondAiServiceUi(service: String) {
        val checkedButtonId = when (service) {
            SettingsStore.SERVICE_OPENCODE_ZEN -> views.btnSecondAiServiceZen.id
            SettingsStore.SERVICE_CUSTOM -> views.btnSecondAiServiceCustom.id
            else -> views.btnSecondAiServiceGemini.id
        }
        if (views.toggleSecondAiService.checkedButtonId != checkedButtonId) {
            syncingSecondAiServiceUi = true
            views.toggleSecondAiService.check(checkedButtonId)
            syncingSecondAiServiceUi = false
        }

        when (service) {
            SettingsStore.SERVICE_GEMINI -> {
                views.containerGeminiActions.visibility = View.VISIBLE
                views.containerZenInfo.visibility = View.GONE
                views.containerCustomFormat.visibility = View.GONE
                views.tilSecondAiKey.visibility = View.VISIBLE
                views.tilSecondAiUrl.visibility = View.VISIBLE
                views.tilSecondAiModel.visibility = View.VISIBLE
                views.tilSecondAiModel.helperText = "可直接手动输入模型 ID"
                views.etSecondAiModel.isEnabled = true
                views.btnRefreshSecondAiModels.text = "拉取模型列表"

                views.etSecondAiKey.setText(SettingsStore.secondAiGeminiApiKey(context))
                views.etSecondAiUrl.setText(SettingsStore.secondAiGeminiBaseUrl(context))
                views.etSecondAiModel.setText(SettingsStore.secondAiGeminiModel(context))
            }
            SettingsStore.SERVICE_OPENCODE_ZEN -> {
                views.containerGeminiActions.visibility = View.GONE
                views.containerZenInfo.visibility = View.VISIBLE
                views.containerCustomFormat.visibility = View.GONE
                views.tilSecondAiKey.visibility = View.GONE
                views.tilSecondAiUrl.visibility = View.GONE
                views.tilSecondAiModel.visibility = View.VISIBLE
                views.tilSecondAiModel.helperText = context.getString(R.string.zen_model_restricted, OpenCodeZenCatalog.CANDIDATE_MODEL)
                views.etSecondAiModel.isEnabled = false
                views.btnRefreshSecondAiModels.text = context.getString(R.string.btn_refresh_free_models)

                views.etSecondAiKey.setText(OpenCodeZenCatalog.PUBLIC_KEY)
                views.etSecondAiUrl.setText(OpenCodeZenCatalog.BASE_URL)
                views.etSecondAiModel.setText(SettingsStore.secondAiZenModel(context))
                renderSecondAiTestStatus(true, "未验证可用：当前免费接口限制仅 OpenCode 客户端使用，本 App 未验证可用；可重新测试或切换服务")
            }
            SettingsStore.SERVICE_CUSTOM -> {
                views.containerGeminiActions.visibility = View.GONE
                views.containerZenInfo.visibility = View.GONE
                views.containerCustomFormat.visibility = View.VISIBLE
                views.tilSecondAiKey.visibility = View.VISIBLE
                views.tilSecondAiUrl.visibility = View.VISIBLE
                views.tilSecondAiModel.visibility = View.VISIBLE
                views.tilSecondAiModel.helperText = "可直接手动输入模型 ID"
                views.etSecondAiModel.isEnabled = true
                views.btnRefreshSecondAiModels.text = "拉取模型列表"

                views.etSecondAiKey.setText(SettingsStore.secondAiCustomApiKey(context))
                views.etSecondAiUrl.setText(SettingsStore.secondAiCustomBaseUrl(context))
                views.etSecondAiModel.setText(SettingsStore.secondAiCustomModel(context))
                renderSecondAiFormatToggle()
            }
        }
    }

    private fun showSwitchServiceDialog() {
        val items = arrayOf("Gemini（官方，需自备 API Key）", "自定义服务（反代 / 第三方兼容）")
        MaterialAlertDialogBuilder(context)
            .setTitle(R.string.btn_switch_service)
            .setItems(items) { _, which ->
                val newService = if (which == 0) SettingsStore.SERVICE_GEMINI else SettingsStore.SERVICE_CUSTOM
                persistSecondAiInputs()
                SettingsStore.saveSecondAiService(context, newService)
                renderSecondAiServiceUi(newService)
                invalidateSecondAiModels()
            }
            .setNegativeButton(R.string.dialog_btn_close, null)
            .show()
    }

    private fun setupSecondAiFormatToggle() {
        renderSecondAiFormatToggle()
        views.secondAiFormatToggle.addOnButtonCheckedListener { _, checkedId, isChecked ->
            if (!isChecked || syncingSecondAiFormatUi) return@addOnButtonCheckedListener
            val selected = if (checkedId == views.btnSecondAiFormatOpenAi.id) {
                AiTextClient.Format.OPENAI
            } else {
                AiTextClient.Format.GEMINI
            }
            if (selected == secondAiFormat()) return@addOnButtonCheckedListener
            SettingsStore.saveSecondAiFormat(context, selected.key)
            invalidateSecondAiModels()
            warnIfBaseUrlMismatchesFormat(selected)
        }
    }

    private fun renderSecondAiFormatToggle() {
        val checkedId = when (secondAiFormat()) {
            AiTextClient.Format.GEMINI -> views.btnSecondAiFormatGemini.id
            AiTextClient.Format.OPENAI -> views.btnSecondAiFormatOpenAi.id
        }
        if (views.secondAiFormatToggle.checkedButtonId == checkedId) return
        syncingSecondAiFormatUi = true
        views.secondAiFormatToggle.check(checkedId)
        syncingSecondAiFormatUi = false
    }

    /** 切格式最容易留下「选了 OpenAI，地址还是 Google」这种组合，这里直接点破。 */
    private fun warnIfBaseUrlMismatchesFormat(format: AiTextClient.Format) {
        val url = views.etSecondAiUrl.text?.toString().orEmpty().trim()
        val looksLikeGoogle = url.contains("googleapis.com", ignoreCase = true)
        val mismatched = when (format) {
            AiTextClient.Format.OPENAI -> looksLikeGoogle || url.isBlank()
            AiTextClient.Format.GEMINI -> url.isNotBlank() && !looksLikeGoogle
        }
        if (!mismatched) {
            toast("已切换到 ${formatLabel(format)}，请重新拉取模型列表")
            return
        }
        renderSecondAiModelsHint(
            true,
            "已切换到 ${formatLabel(format)}，但当前服务地址看起来不是这套接口的地址，请确认后再拉取模型",
        )
        views.etSecondAiUrl.requestFocus()
    }

    private fun formatLabel(format: AiTextClient.Format): String = when (format) {
        AiTextClient.Format.GEMINI -> "Gemini 原生"
        AiTextClient.Format.OPENAI -> "OpenAI 兼容"
    }

    private fun setupSecondAiModelPicker() {
        views.etSecondAiKey.doAfterTextChanged { invalidateSecondAiModels() }
        views.etSecondAiUrl.doAfterTextChanged { invalidateSecondAiModels() }
        views.etSecondAiModel.doAfterTextChanged { renderSecondAiTestStatus(false, "") }
        views.btnRefreshSecondAiModels.setOnClickListener {
            // 已经拉过就直接开面板；面板里还留着「重新拉取」。
            if (cachedSecondAiModels.isNotEmpty()) showModelPicker() else fetchSecondAiModels()
        }
    }

    private fun fetchSecondAiModels() {
        if (secondAiModelsFetching) return
        // 使用刚输入的配置，避免首次填写 Key 后还要离开页面才能刷新。
        persistSecondAiInputs()
        val service = SettingsStore.secondAiService(context)
        val apiKey = SettingsStore.secondAiApiKey(context)
        val baseUrl = SettingsStore.secondAiBaseUrl(context)
        val format = secondAiFormat()

        if (service == SettingsStore.SERVICE_GEMINI && apiKey.isBlank()) {
            requireSecondAiKey()
            return
        }
        if (service == SettingsStore.SERVICE_CUSTOM && baseUrl.isBlank()) {
            renderSecondAiModelsHint(true, "请先填写自定义服务地址")
            views.etSecondAiUrl.requestFocus()
            return
        }

        val revision = secondAiModelsRevision
        secondAiModelsFetching = true
        val btnText = if (service == SettingsStore.SERVICE_OPENCODE_ZEN) {
            context.getString(R.string.btn_refresh_free_models)
        } else {
            "拉取模型列表"
        }
        setSecondAiButtonsBusy(views.btnRefreshSecondAiModels, "正在获取模型列表…")
        val hintText = if (service == SettingsStore.SERVICE_OPENCODE_ZEN) {
            "正在拉取 Zen 免费白名单模型…"
        } else {
            "正在从 ${formatLabel(format)} 服务获取可用模型…"
        }
        renderSecondAiModelsHint(false, hintText)

        Thread({
            val result = runCatching {
                val rawModels = AiTextClient.listModels(
                    baseUrl = baseUrl,
                    apiKey = apiKey,
                    format = format,
                )
                if (service == SettingsStore.SERVICE_OPENCODE_ZEN) {
                    OpenCodeZenCatalog.filterAvailableFreeModels(rawModels)
                } else {
                    rawModels
                }
            }
            postToUi {
                secondAiModelsFetching = false
                if (!isHostActive()) return@postToUi
                clearSecondAiButtonsBusy(views.btnRefreshSecondAiModels, btnText)
                if (revision != secondAiModelsRevision) return@postToUi
                result.onSuccess { models ->
                    cachedSecondAiModels = models
                    if (models.isEmpty()) {
                        if (service == SettingsStore.SERVICE_OPENCODE_ZEN) {
                            renderSecondAiModelsHint(true, "远端未返回任何官方免费白名单模型，当前不可用")
                        } else {
                            renderSecondAiModelsHint(true, "远端没有返回任何模型，请手动输入模型 ID")
                        }
                        return@onSuccess
                    }
                    renderSecondAiModelsHint(false, "已获取 ${models.size} 个可用模型")
                    showModelPicker()
                }.onFailure { error ->
                    val rawMsg = error.message ?: "获取失败"
                    val localized = if (service == SettingsStore.SERVICE_OPENCODE_ZEN) {
                        OpenCodeZenCatalog.localizeZenError(rawMsg)
                    } else {
                        AiTextClient.sanitizeError(rawMsg)
                    }
                    renderSecondAiModelsHint(
                        true,
                        "获取失败：$localized",
                    )
                }
            }
        }, "ai-models-fetch").start()
    }

    /** 可搜索的模型选择面板：长模型名在窄下拉里根本看不清。 */
    private fun showModelPicker() {
        val content = LayoutInflater.from(context).inflate(R.layout.dialog_model_picker, null, false)
        val list = content.findViewById<LinearLayout>(R.id.modelPickerList)
        val empty = content.findViewById<TextView>(R.id.tvModelPickerEmpty)
        val search = content.findViewById<EditText>(R.id.etModelPickerSearch)
        val dialog = MaterialAlertDialogBuilder(context)
            .setTitle("选择分析模型")
            .setView(content)
            .setNegativeButton("关闭", null)
            .setNeutralButton("重新拉取", null)
            .create()

        fun render(keyword: String) {
            val matched = cachedSecondAiModels.filter { it.contains(keyword.trim(), ignoreCase = true) }
            list.removeAllViews()
            empty.visibility = if (matched.isEmpty()) View.VISIBLE else View.GONE
            matched.forEach { model ->
                list.addView(
                    TextView(context).apply {
                        text = model
                        textSize = 14f
                        minHeight = resources.getDimensionPixelSize(R.dimen.touch_target)
                        gravity = android.view.Gravity.CENTER_VERTICAL
                        setTextColor(context.getColor(R.color.text_primary))
                        val padding = resources.getDimensionPixelSize(R.dimen.space_12)
                        setPadding(padding, padding, padding, padding)
                        isClickable = true
                        isFocusable = true
                        setBackgroundResource(R.drawable.bg_history_context)
                        layoutParams = LinearLayout.LayoutParams(
                            ViewGroup.LayoutParams.MATCH_PARENT,
                            ViewGroup.LayoutParams.WRAP_CONTENT,
                        ).apply { topMargin = resources.getDimensionPixelSize(R.dimen.grid_4) }
                        setOnClickListener {
                            views.etSecondAiModel.setText(model)
                            persistSecondAiInputs()
                            renderSecondAiModelsHint(false, "已选择模型：$model")
                            dialog.dismiss()
                        }
                    },
                )
            }
        }

        search.doAfterTextChanged { render(it?.toString().orEmpty()) }
        render("")
        dialog.setOnShowListener {
            dialog.getButton(android.app.AlertDialog.BUTTON_NEUTRAL).setOnClickListener {
                dialog.dismiss()
                cachedSecondAiModels = emptyList()
                fetchSecondAiModels()
            }
        }
        dialog.show()
    }

    /** 测试当前 Key + 地址 + 模型能否真的完成一次分析。 */
    private fun testSecondAi() {
        if (secondAiTesting) return
        persistSecondAiInputs()
        val service = SettingsStore.secondAiService(context)
        val apiKey = SettingsStore.secondAiApiKey(context)
        val baseUrl = SettingsStore.secondAiBaseUrl(context)
        val model = SettingsStore.secondAiModel(context)
        val format = secondAiFormat()

        if (service == SettingsStore.SERVICE_CUSTOM) {
            if (baseUrl.isBlank()) {
                renderSecondAiTestStatus(true, "自定义服务请先填写服务地址（Base URL）")
                views.etSecondAiUrl.requestFocus()
                return
            }
            if (model.isBlank()) {
                renderSecondAiTestStatus(true, "自定义服务请先填写分析模型")
                views.etSecondAiModel.requestFocus()
                return
            }
        } else if (service == SettingsStore.SERVICE_GEMINI) {
            if (apiKey.isBlank()) {
                requireSecondAiKey()
                return
            }
        }

        secondAiTesting = true
        setSecondAiButtonsBusy(views.btnTestSecondAi, "正在测试…")
        renderSecondAiTestStatus(false, "正在用 $model 跑一次最短请求…")
        Thread({
            val result = runCatching {
                AiTextClient.probe(
                    baseUrl = baseUrl,
                    apiKey = apiKey,
                    model = model,
                    format = format,
                )
            }
            postToUi {
                secondAiTesting = false
                if (!isHostActive()) return@postToUi
                clearSecondAiButtonsBusy(views.btnTestSecondAi, "测试分析服务")
                result.onSuccess {
                    renderSecondAiTestStatus(false, "可用：$model 已成功返回结果，分析功能可以使用。")
                }.onFailure { error ->
                    val rawMsg = error.message ?: "请检查配置"
                    val localized = if (service == SettingsStore.SERVICE_OPENCODE_ZEN) {
                        OpenCodeZenCatalog.localizeZenError(rawMsg)
                    } else {
                        AiTextClient.sanitizeError(rawMsg)
                    }
                    renderSecondAiTestStatus(
                        true,
                        "不可用：$localized",
                    )
                }
            }
        }, "ai-probe").start()
    }

    /** 缺 Key 时把用户直接送到该填的那个框，而不是让他自己找。 */
    private fun requireSecondAiKey() {
        renderSecondAiModelsHint(true, "请先填写 API Key")
        views.etSecondAiKey.requestFocus()
    }

    private fun setSecondAiButtonsBusy(button: Button, busyText: String) {
        button.isEnabled = false
        button.text = busyText
    }

    private fun clearSecondAiButtonsBusy(button: Button, idleText: String) {
        button.isEnabled = true
        button.text = idleText
    }

    private fun renderSecondAiModelsHint(warn: Boolean, message: String) {
        views.tvSecondAiModelsHint.apply {
            text = message
            visibility = if (message.isBlank()) View.GONE else View.VISIBLE
            setTextColor(context.getColor(if (warn) R.color.warning else R.color.text_muted))
        }
    }

    private fun renderSecondAiTestStatus(warn: Boolean, message: String) {
        views.tvSecondAiTestStatus.apply {
            text = message
            visibility = if (message.isBlank()) View.GONE else View.VISIBLE
            setTextColor(context.getColor(if (warn) R.color.warning else R.color.brand))
        }
    }

    private fun invalidateSecondAiModels() {
        secondAiModelsRevision++
        cachedSecondAiModels = emptyList()
        renderSecondAiModelsHint(false, "配置已修改，请重新拉取模型列表，也可以手动输入模型 ID")
        renderSecondAiTestStatus(false, "")
    }

    private fun setupStyleSliders() {
        // 预览底色只是设置页里的模拟画面，不写入任何设置。
        views.subtitlePreviewStageToggle.addOnButtonCheckedListener { _, _, _ ->
            renderPreviewStage()
        }
        renderPreviewStage()
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

    private fun renderPreviewStage() {
        val light = views.subtitlePreviewStageToggle.checkedButtonId == views.btnSubtitlePreviewLight.id
        views.subtitlePreviewStage.setBackgroundColor(
            context.getColor(if (light) R.color.preview_stage_light else R.color.preview_stage_dark),
        )
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
        views.tvRotateVal.text = "每 ${views.slRotate.value.toInt()} 秒定期重新连接"
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
