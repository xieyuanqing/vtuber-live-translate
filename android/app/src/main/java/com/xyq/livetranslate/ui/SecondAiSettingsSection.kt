package com.xyq.livetranslate.ui

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.view.View
import android.widget.ArrayAdapter
import android.widget.ListPopupWindow
import androidx.core.widget.doAfterTextChanged
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.xyq.livetranslate.AiTextClient
import com.xyq.livetranslate.OpenCodeZenCatalog
import com.xyq.livetranslate.R
import com.xyq.livetranslate.SettingsStore

/** 背景分析 AI 子页：服务切换、模型下拉、连通性自检。 */
internal class SecondAiSettingsSection(
    private val context: Context,
    private val views: SettingsViews,
    private val postToUi: (() -> Unit) -> Unit,
    private val isHostActive: () -> Boolean,
    private val launchIntent: (Intent) -> Unit,
    private val toast: (String) -> Unit,
    private val showApiKeyTutorial: () -> Unit,
) {
    fun setup() {
        setupSecondAiServiceToggle()
        setupSecondAiFormatToggle()
        setupSecondAiModelPicker()
        views.btnUseTranslateKeyForSecondAi.setOnClickListener { useTranslateKeyForSecondAi() }
        views.btnSecondAiTutorial.setOnClickListener { showApiKeyTutorial() }
        views.btnPasteSecondAiKey.setOnClickListener { pasteSecondAiKey() }
        views.btnZenDocLink.setOnClickListener { openExternalUrl(OpenCodeZenCatalog.DOCS_URL) }
        views.btnSecondAiSwitchService.setOnClickListener { showSwitchServiceDialog() }
        views.btnTestSecondAi.setOnClickListener { testSecondAi() }
    }

    fun persist() {
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

    private fun pasteSecondAiKey() {
        val text = getClipboardText()
        if (text.isNullOrBlank()) {
            toast(context.getString(R.string.rt_toast_clipboard_empty))
            return
        }
        views.etSecondAiKey.setText(text)
        persist()
        invalidateSecondAiModels()
        toast(context.getString(R.string.rt_toast_pasted_key))
    }

    private fun useTranslateKeyForSecondAi() {
        val raw = views.etApiKeys.text?.toString().orEmpty().trim()
        val firstKey = SettingsStore.extractFirstApiKey(raw)
        if (firstKey.isEmpty()) {
            toast(context.getString(R.string.rt_toast_fill_translation_api_key_first))
            return
        }
        views.etSecondAiKey.setText(firstKey)
        SettingsStore.saveSecondAiGeminiApiKey(context, firstKey)
        invalidateSecondAiModels()
        if (raw.contains(',')) {
            toast(context.getString(R.string.rt_toast_copied_first_gemini_key))
        } else {
            toast(context.getString(R.string.rt_toast_copied_translation_gemini_key))
        }
    }

    private fun secondAiFormat(): AiTextClient.Format =
        AiTextClient.Format.fromKey(SettingsStore.secondAiFormat(context))

    private var syncingSecondAiFormatUi = false
    private var syncingSecondAiServiceUi = false
    private var cachedSecondAiModels: List<String> = emptyList()
    private var secondAiModelsFetching = false
    private var secondAiModelsRevision = 0
    private var secondAiTesting = false
    private var modelPopup: ListPopupWindow? = null

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
            persist()
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
                views.rowSecondAiKey.visibility = View.VISIBLE
                views.containerZenInfo.visibility = View.GONE
                views.containerCustomFormat.visibility = View.GONE
                views.tilSecondAiKey.visibility = View.VISIBLE
                views.tilSecondAiUrl.visibility = View.VISIBLE
                views.tilSecondAiModel.visibility = View.VISIBLE
                views.tilSecondAiModel.helperText = context.getString(R.string.helper_second_ai_model)
                views.etSecondAiModel.isEnabled = true

                views.etSecondAiKey.setText(SettingsStore.secondAiGeminiApiKey(context))
                views.etSecondAiUrl.setText(SettingsStore.secondAiGeminiBaseUrl(context))
                views.etSecondAiModel.setText(SettingsStore.secondAiGeminiModel(context))
            }
            SettingsStore.SERVICE_OPENCODE_ZEN -> {
                views.containerGeminiActions.visibility = View.GONE
                views.rowSecondAiKey.visibility = View.GONE
                views.containerZenInfo.visibility = View.VISIBLE
                views.containerCustomFormat.visibility = View.GONE
                views.tilSecondAiKey.visibility = View.GONE
                views.tilSecondAiUrl.visibility = View.GONE
                views.tilSecondAiModel.visibility = View.VISIBLE
                views.tilSecondAiModel.helperText = context.getString(R.string.zen_model_restricted, OpenCodeZenCatalog.CANDIDATE_MODEL)
                views.etSecondAiModel.isEnabled = false

                views.etSecondAiKey.setText(OpenCodeZenCatalog.PUBLIC_KEY)
                views.etSecondAiUrl.setText(OpenCodeZenCatalog.BASE_URL)
                views.etSecondAiModel.setText(SettingsStore.secondAiZenModel(context))
                renderSecondAiTestStatus(false, context.getString(R.string.rt_zen_ready_status))
            }
            SettingsStore.SERVICE_CUSTOM -> {
                views.containerGeminiActions.visibility = View.GONE
                views.rowSecondAiKey.visibility = View.VISIBLE
                views.containerZenInfo.visibility = View.GONE
                views.containerCustomFormat.visibility = View.VISIBLE
                views.tilSecondAiKey.visibility = View.VISIBLE
                views.tilSecondAiUrl.visibility = View.VISIBLE
                views.tilSecondAiModel.visibility = View.VISIBLE
                views.tilSecondAiModel.helperText = context.getString(R.string.helper_second_ai_model)
                views.etSecondAiModel.isEnabled = true

                views.etSecondAiKey.setText(SettingsStore.secondAiCustomApiKey(context))
                views.etSecondAiUrl.setText(SettingsStore.secondAiCustomBaseUrl(context))
                views.etSecondAiModel.setText(SettingsStore.secondAiCustomModel(context))
                renderSecondAiFormatToggle()
            }
        }
    }

    private fun showSwitchServiceDialog() {
        val items = arrayOf(context.getString(R.string.rt_service_option_gemini), context.getString(R.string.rt_service_option_custom))
        MaterialAlertDialogBuilder(context)
            .setTitle(R.string.btn_switch_service)
            .setItems(items) { _, which ->
                val newService = if (which == 0) SettingsStore.SERVICE_GEMINI else SettingsStore.SERVICE_CUSTOM
                persist()
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
            toast(context.getString(R.string.rt_settings_toast_format_switched, formatLabel(format)))
            return
        }
        renderSecondAiModelsHint(
            true,
            context.getString(R.string.rt_settings_hint_format_url_mismatch, formatLabel(format)),
        )
        views.etSecondAiUrl.requestFocus()
    }

    private fun formatLabel(format: AiTextClient.Format): String = when (format) {
        AiTextClient.Format.GEMINI -> context.getString(R.string.rt_format_gemini_native)
        AiTextClient.Format.OPENAI -> context.getString(R.string.rt_format_openai_compatible)
    }

    private fun setupSecondAiModelPicker() {
        views.etSecondAiKey.doAfterTextChanged { invalidateSecondAiModels() }
        views.etSecondAiUrl.doAfterTextChanged { invalidateSecondAiModels() }
        views.etSecondAiModel.doAfterTextChanged { renderSecondAiTestStatus(false, "") }
        // 刷新图标就该刷新：每次都重新拉，避免服务端加了模型这边还是旧列表。
        views.btnRefreshSecondAiModels.setOnClickListener { fetchSecondAiModels() }
    }

    private fun fetchSecondAiModels() {
        if (secondAiModelsFetching) return
        // 使用刚输入的配置，避免首次填写 Key 后还要离开页面才能刷新。
        persist()
        val service = SettingsStore.secondAiService(context)
        val apiKey = SettingsStore.secondAiApiKey(context)
        val baseUrl = SettingsStore.secondAiBaseUrl(context)
        val format = secondAiFormat()

        if (service == SettingsStore.SERVICE_GEMINI && apiKey.isBlank()) {
            requireSecondAiKey()
            return
        }
        if (service == SettingsStore.SERVICE_CUSTOM && baseUrl.isBlank()) {
            renderSecondAiModelsHint(true, context.getString(R.string.rt_settings_hint_fill_custom_url_first))
            views.etSecondAiUrl.requestFocus()
            return
        }

        val revision = secondAiModelsRevision
        secondAiModelsFetching = true
        views.btnRefreshSecondAiModels.isEnabled = false
        val hintText = if (service == SettingsStore.SERVICE_OPENCODE_ZEN) {
            context.getString(R.string.rt_settings_fetching_zen_models)
        } else {
            context.getString(R.string.rt_settings_fetching_models_from_service, formatLabel(format))
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
                views.btnRefreshSecondAiModels.isEnabled = true
                if (revision != secondAiModelsRevision) return@postToUi
                result.onSuccess { models ->
                    cachedSecondAiModels = models
                    if (models.isEmpty()) {
                        if (service == SettingsStore.SERVICE_OPENCODE_ZEN) {
                            renderSecondAiModelsHint(true, context.getString(R.string.rt_settings_zen_no_free_models))
                        } else {
                            renderSecondAiModelsHint(true, context.getString(R.string.rt_settings_no_models_returned))
                        }
                        return@onSuccess
                    }
                    renderSecondAiModelsHint(false, context.getString(R.string.rt_settings_models_fetched_count, models.size))
                    showModelPicker()
                }.onFailure { error ->
                    val rawMsg = error.message ?: context.getString(R.string.rt_fetch_failed)
                    val localized = if (service == SettingsStore.SERVICE_OPENCODE_ZEN) {
                        OpenCodeZenCatalog.localizeZenError(rawMsg, context)
                    } else {
                        AiTextClient.sanitizeError(rawMsg)
                    }
                    renderSecondAiModelsHint(
                        true,
                        context.getString(R.string.rt_settings_fetch_models_failed, localized),
                    )
                }
            }
        }, "ai-models-fetch").start()
    }

    /**
     * 模型就地下拉：锚在模型输入框上，选一行即填入，不弹全屏面板。
     * 列表一律给全的——不能拿输入框里已有的模型名去筛，否则永远只看得到同名系列，
     * 换不到别的模型；当前模型改为滚动定位。
     */
    private fun showModelPicker() {
        if (cachedSecondAiModels.isEmpty()) return
        dismissModelPopup()
        val anchor = views.tilSecondAiModel
        val popup = ListPopupWindow(context).apply {
            anchorView = anchor
            width = anchor.width.takeIf { it > 0 } ?: ListPopupWindow.WRAP_CONTENT
            isModal = true
            setAdapter(ArrayAdapter(context, R.layout.item_model_option, cachedSecondAiModels))
            // 列表长的时候固定高度让它自己滚，而不是顶满整屏。
            height = if (cachedSecondAiModels.size > MODEL_POPUP_MAX_ROWS) {
                (MODEL_POPUP_MAX_ROWS * MODEL_POPUP_ROW_DP * context.resources.displayMetrics.density).toInt()
            } else {
                ListPopupWindow.WRAP_CONTENT
            }
            setOnItemClickListener { _, _, position, _ ->
                val model = cachedSecondAiModels.getOrNull(position).orEmpty()
                if (model.isNotEmpty()) {
                    views.etSecondAiModel.setText(model)
                    persist()
                    renderSecondAiModelsHint(false, context.getString(R.string.rt_settings_model_selected, model))
                }
                dismissModelPopup()
            }
            setOnDismissListener { modelPopup = null }
        }
        modelPopup = popup
        popup.show()
        // 已选中的模型直接滚到可见位置，省得在几十行里找。
        cachedSecondAiModels.indexOf(views.etSecondAiModel.text?.toString()?.trim().orEmpty())
            .takeIf { it >= 0 }
            ?.let { popup.setSelection(it) }
    }

    fun dismissModelPopup() {
        modelPopup?.let {
            it.setOnDismissListener(null)
            it.dismiss()
        }
        modelPopup = null
    }

    /** 测试当前 Key + 地址 + 模型能否真的完成一次分析。 */
    private fun testSecondAi() {
        if (secondAiTesting) return
        persist()
        val service = SettingsStore.secondAiService(context)
        val apiKey = SettingsStore.secondAiApiKey(context)
        val baseUrl = SettingsStore.secondAiBaseUrl(context)
        val model = SettingsStore.secondAiModel(context)
        val format = secondAiFormat()

        if (service == SettingsStore.SERVICE_CUSTOM) {
            if (baseUrl.isBlank()) {
                renderSecondAiTestStatus(true, context.getString(R.string.rt_settings_hint_custom_fill_url))
                views.etSecondAiUrl.requestFocus()
                return
            }
            if (model.isBlank()) {
                renderSecondAiTestStatus(true, context.getString(R.string.rt_settings_hint_custom_fill_model))
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
        setSecondAiTestBusy(true)
        renderSecondAiTestStatus(false, context.getString(R.string.rt_settings_testing_second_ai_with_model, model))
        Thread({
            val result = runCatching {
                AiTextClient.probe(
                    baseUrl = baseUrl,
                    apiKey = apiKey,
                    model = model,
                    format = format,
                    extraHeaders = SettingsStore.secondAiExtraHeaders(context),
                )
            }
            postToUi {
                secondAiTesting = false
                if (!isHostActive()) return@postToUi
                setSecondAiTestBusy(false)
                result.onSuccess {
                    renderSecondAiTestStatus(false, context.getString(R.string.rt_settings_second_ai_available, model))
                }.onFailure { error ->
                    val rawMsg = error.message ?: context.getString(R.string.rt_settings_check_config)
                    val localized = if (service == SettingsStore.SERVICE_OPENCODE_ZEN) {
                        OpenCodeZenCatalog.localizeZenError(rawMsg, context)
                    } else {
                        AiTextClient.sanitizeError(rawMsg)
                    }
                    renderSecondAiTestStatus(
                        true,
                        context.getString(R.string.rt_settings_second_ai_unavailable, localized),
                    )
                }
            }
        }, "ai-probe").start()
    }

    /** 缺 Key 时把用户直接送到该填的那个框，而不是让他自己找。 */
    private fun requireSecondAiKey() {
        renderSecondAiModelsHint(true, context.getString(R.string.rt_settings_hint_fill_key_first))
        views.etSecondAiKey.requestFocus()
    }

    private fun setSecondAiTestBusy(busy: Boolean) {
        views.btnTestSecondAi.isEnabled = !busy
        views.btnTestSecondAi.text = if (busy) {
            context.getString(R.string.rt_testing_ellipsis)
        } else {
            context.getString(R.string.btn_test_connection)
        }
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
        renderSecondAiModelsHint(false, context.getString(R.string.rt_settings_hint_config_modified))
        renderSecondAiTestStatus(false, "")
    }

    private fun getClipboardText(): String? {
        val clipboard = context.getSystemService(Context.CLIPBOARD_SERVICE) as? android.content.ClipboardManager
        val clip = clipboard?.primaryClip ?: return null
        if (clip.itemCount <= 0) return null
        return clip.getItemAt(0)?.text?.toString()?.trim()
    }

    private fun openExternalUrl(url: String) {
        runCatching {
            launchIntent(Intent(Intent.ACTION_VIEW, Uri.parse(url)))
        }.onFailure {
            toast(context.getString(R.string.rt_toast_no_browser))
        }
    }

    private companion object {
        const val MODEL_POPUP_MAX_ROWS = 6
        const val MODEL_POPUP_ROW_DP = 44
    }
}
