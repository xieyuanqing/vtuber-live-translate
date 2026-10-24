package com.xyq.livetranslate.ui

import android.content.Context
import android.content.Intent
import android.view.View
import android.widget.Button
import android.widget.EditText
import android.widget.TextView
import androidx.core.view.ViewCompat
import com.google.android.material.button.MaterialButton
import com.google.android.material.button.MaterialButtonToggleGroup
import com.google.android.material.materialswitch.MaterialSwitch
import com.google.android.material.slider.Slider
import com.google.android.material.textfield.TextInputLayout
import com.xyq.livetranslate.R
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
    val btnViewApiKeyTutorial: Button,
    val btnPasteApiKey: Button,
    val btnTestTranslateConnection: Button,
    val tvTranslateTestStatus: TextView,
    val btnConnectionOptions: MaterialButton,
    val connectionOptions: View,
    val btnTranslateAdvanced: MaterialButton,
    val translateAdvanced: View,
    val btnSubtitleAdvanced: MaterialButton,
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
    val rowSecondAiKey: View,
    val btnUseTranslateKeyForSecondAi: Button,
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
    val btnRefreshSecondAiModels: MaterialButton,
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
                btnViewApiKeyTutorial = root.findViewById(R.id.btnViewApiKeyTutorial),
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
                rowSecondAiKey = root.findViewById(R.id.rowSecondAiKey),
                btnUseTranslateKeyForSecondAi = root.findViewById(R.id.btnUseTranslateKeyForSecondAi),
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

internal fun bindSettingsDisclosure(context: Context, button: MaterialButton, content: View) {
    fun render() {
        val expanded = content.visibility == View.VISIBLE
        button.setIconResource(if (expanded) R.drawable.ic_chevron_down_24 else R.drawable.ic_chevron_right_24)
        ViewCompat.setStateDescription(
            button,
            if (expanded) context.getString(R.string.rt_state_expanded) else context.getString(R.string.rt_state_collapsed),
        )
    }
    render()
    button.setOnClickListener {
        content.visibility = if (content.visibility == View.VISIBLE) View.GONE else View.VISIBLE
        render()
    }
}

/** 设置页外壳。各子页的输入和自检在各自的 section 里。 */
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
    private val translate = TranslateSettingsSection(
        context, views, postToUi, isHostActive, launchIntent, toast,
    )
    private val subtitle = SubtitleSettingsSection(context, views, toast)
    private val secondAi = SecondAiSettingsSection(
        context, views, postToUi, isHostActive, launchIntent, toast, translate::showTutorial,
    )
    private val maintenance = MaintenanceSettingsSection(
        context, views, launchIntent, toast, onCheckUpdate,
    )

    fun setup() {
        views.rowSetTranslate.setOnClickListener { openSubPage(R.id.pageSettingsTranslate) }
        views.rowSetSubtitle.setOnClickListener { openSubPage(R.id.pageSettingsSubtitle) }
        views.rowSetSceneLibrary.setOnClickListener { openSceneLibrary(TranslationMode.INTERPRETATION) }
        views.rowSetProfileAi.setOnClickListener { openSubPage(R.id.pageSettingsProfileAi) }
        views.rowSetDiagnostics.setOnClickListener { openSubPage(R.id.pageSettingsDiagnostics) }
        views.rowSetAbout.setOnClickListener { openSubPage(R.id.pageSettingsAbout) }
        translate.setup()
        subtitle.setup()
        secondAi.setup()
        maintenance.setup()
    }

    fun persistSecondAiInputs() = secondAi.persist()

    fun persistDraftInputs() {
        // 模型下拉挂在 WindowManager 上，离开设置页时必须收掉。
        secondAi.dismissModelPopup()
        translate.persist()
        persistSecondAiInputs()
    }

    fun renderDiagnostics(state: SettingsDiagnosticsState) = maintenance.renderDiagnostics(state)

    fun renderUpdateStatus(message: String?) = maintenance.renderUpdateStatus(message)
}
