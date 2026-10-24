package com.xyq.livetranslate.ui

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.view.View
import androidx.core.widget.doAfterTextChanged
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.google.android.material.slider.Slider
import com.xyq.livetranslate.AiTextClient
import com.xyq.livetranslate.GeminiLiveClient
import com.xyq.livetranslate.R
import com.xyq.livetranslate.SettingsStore

/** 翻译服务子页：API Key、地址、连接自检，以及轮换和回声这两个高级项。 */
internal class TranslateSettingsSection(
    private val context: Context,
    private val views: SettingsViews,
    private val postToUi: (() -> Unit) -> Unit,
    private val isHostActive: () -> Boolean,
    private val launchIntent: (Intent) -> Unit,
    private val toast: (String) -> Unit,
) {
    private var translateTesting = false

    fun setup() {
        views.etApiKeys.setText(SettingsStore.apiKeysRaw(context))
        views.etBaseUrl.setText(SettingsStore.baseUrl(context))
        views.btnViewApiKeyTutorial.setOnClickListener { showApiKeyTutorialDialog() }
        views.btnPasteApiKey.setOnClickListener { pasteTranslateApiKey() }
        views.btnTestTranslateConnection.setOnClickListener { testTranslateConnection() }
        views.etApiKeys.doAfterTextChanged { invalidateTranslateTestStatus() }
        views.etBaseUrl.doAfterTextChanged { invalidateTranslateTestStatus() }
        bindSettingsDisclosure(context, views.btnConnectionOptions, views.connectionOptions)
        bindSettingsDisclosure(context, views.btnTranslateAdvanced, views.translateAdvanced)
        views.swEchoTarget.isChecked = SettingsStore.echoTargetLanguage(context)
        views.slRotate.value = SettingsStore.rotateSeconds(context).toFloat()
        updateRotateLabel()
        views.swEchoTarget.setOnCheckedChangeListener { _, checked ->
            SettingsStore.saveEchoTargetLanguage(context, checked)
        }
        views.slRotate.addOnChangeListener { _, _, _ -> updateRotateLabel() }
        views.slRotate.addOnSliderTouchListener(object : Slider.OnSliderTouchListener {
            override fun onStartTrackingTouch(slider: Slider) = Unit
            override fun onStopTrackingTouch(slider: Slider) {
                SettingsStore.saveRotateSeconds(context, views.slRotate.value.toInt())
            }
        })
        views.btnResetTranslate.setOnClickListener {
            SettingsStore.saveEchoTargetLanguage(context, true)
            SettingsStore.saveRotateSeconds(context, SettingsStore.DEFAULT_ROTATE_SECONDS)
            views.swEchoTarget.isChecked = true
            views.slRotate.value = SettingsStore.DEFAULT_ROTATE_SECONDS.toFloat()
            updateRotateLabel()
            toast(context.getString(R.string.rt_toast_translate_params_reset))
        }
    }

    fun persist() {
        SettingsStore.saveApiKeys(context, views.etApiKeys.text?.toString().orEmpty())
        SettingsStore.saveBaseUrl(
            context,
            views.etBaseUrl.text?.toString().orEmpty().trim().ifEmpty { SettingsStore.DEFAULT_BASE_URL },
        )
    }

    fun showTutorial() = showApiKeyTutorialDialog()

    private fun updateRotateLabel() {
        views.tvRotateVal.text = context.getString(R.string.rt_settings_rotate_seconds, views.slRotate.value.toInt())
    }

    private fun openExternalUrl(url: String) {
        runCatching {
            launchIntent(Intent(Intent.ACTION_VIEW, Uri.parse(url)))
        }.onFailure {
            toast(context.getString(R.string.rt_toast_no_browser))
        }
    }

    private fun showApiKeyTutorialDialog() {
        val message = context.getString(R.string.rt_dialog_tutorial_message)

        MaterialAlertDialogBuilder(context)
            .setTitle(R.string.dialog_tutorial_title)
            .setMessage(message)
            .setPositiveButton(R.string.dialog_btn_go_apply) { _, _ ->
                openExternalUrl("https://aistudio.google.com/apikey")
            }
            .setNeutralButton(context.getString(R.string.rt_dialog_btn_official_and_pricing)) { _, _ ->
                showTutorialLinksDialog()
            }
            .setNegativeButton(R.string.dialog_btn_close, null)
            .show()
    }

    private fun showTutorialLinksDialog() {
        val items = arrayOf(context.getString(R.string.rt_tutorial_link_official), context.getString(R.string.rt_tutorial_link_pricing))
        MaterialAlertDialogBuilder(context)
            .setTitle(context.getString(R.string.rt_dialog_official_pricing_title))
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
            toast(context.getString(R.string.rt_toast_clipboard_empty))
            return
        }
        views.etApiKeys.setText(text)
        SettingsStore.saveApiKeys(context, text)
        invalidateTranslateTestStatus()
        toast(context.getString(R.string.rt_toast_pasted_key))
    }

    private fun testTranslateConnection() {
        if (translateTesting) return
        val baseUrl = views.etBaseUrl.text?.toString().orEmpty().trim().ifEmpty { SettingsStore.DEFAULT_BASE_URL }
        val apiKey = SettingsStore.extractFirstApiKey(views.etApiKeys.text?.toString().orEmpty())
        if (apiKey.isEmpty()) {
            renderTranslateTestStatus(true, context.getString(R.string.rt_settings_hint_fill_gemini_key_first))
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
                result.onSuccess {
                    renderTranslateTestStatus(false, context.getString(R.string.rt_live_probe_success))
                }.onFailure { err ->
                    val sanitized = AiTextClient.sanitizeError(err.message ?: context.getString(R.string.rt_connection_failed))
                    renderTranslateTestStatus(true, context.getString(R.string.rt_test_failed_prefix, sanitized))
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
        views.btnTestTranslateConnection.text = if (busy) context.getString(R.string.rt_testing_ellipsis) else context.getString(R.string.btn_test_connection)
    }

    private fun renderTranslateTestStatus(warn: Boolean, message: String) {
        views.tvTranslateTestStatus.apply {
            text = message
            visibility = if (message.isBlank()) View.GONE else View.VISIBLE
            setTextColor(context.getColor(if (warn) R.color.warning else R.color.brand))
        }
    }
}
