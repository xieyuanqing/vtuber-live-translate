package com.xyq.livetranslate.ui

import android.content.Context
import android.graphics.Color
import android.graphics.drawable.GradientDrawable
import com.google.android.material.slider.Slider
import com.xyq.livetranslate.R
import com.xyq.livetranslate.SettingsStore

/** 字幕与悬浮窗子页：预览、字号、不透明度和断句参数。 */
internal class SubtitleSettingsSection(
    private val context: Context,
    private val views: SettingsViews,
    private val toast: (String) -> Unit,
) {
    fun setup() {
        bindSettingsDisclosure(context, views.btnSubtitleAdvanced, views.subtitleAdvanced)
        setupStyleSliders()
        views.slIdle.value = SettingsStore.stabIdleMs(context).toFloat()
        views.slMaxChars.value = SettingsStore.stabMaxChars(context).toFloat()
        updateSegmentLabels()
        val change = Slider.OnChangeListener { _, _, _ -> updateSegmentLabels() }
        val touch = object : Slider.OnSliderTouchListener {
            override fun onStartTrackingTouch(slider: Slider) = Unit
            override fun onStopTrackingTouch(slider: Slider) {
                SettingsStore.saveStabIdleMs(context, views.slIdle.value.toInt())
                SettingsStore.saveStabMaxChars(context, views.slMaxChars.value.toInt())
            }
        }
        listOf(views.slIdle, views.slMaxChars).forEach {
            it.addOnChangeListener(change)
            it.addOnSliderTouchListener(touch)
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
            views.slIdle.value = SettingsStore.DEFAULT_STAB_IDLE_MS.toFloat()
            views.slMaxChars.value = SettingsStore.DEFAULT_STAB_MAX_CHARS.toFloat()
            updateSegmentLabels()
            toast(context.getString(R.string.rt_toast_subtitle_params_reset))
        }
    }

    private fun updateSegmentLabels() {
        val secs = views.slIdle.value.toInt() / 1000.0
        val secsText = if (secs % 1.0 == 0.0) secs.toInt().toString() else secs.toString()
        views.tvIdleVal.text = context.getString(R.string.rt_settings_idle_commit_seconds, secsText)
        views.tvMaxCharsVal.text = context.getString(R.string.rt_settings_max_chars, views.slMaxChars.value.toInt())
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
        views.tvFontVal.text = context.getString(R.string.rt_settings_font_size, views.slFont.value.toInt())
        views.tvOpacityVal.text = context.getString(R.string.rt_settings_bg_opacity, views.slOpacity.value.toInt())
        views.tvLinesVal.text = context.getString(R.string.rt_settings_max_lines, views.slLines.value.toInt())
        views.tvSubtitlePreview.apply {
            textSize = views.slFont.value
            maxLines = views.slLines.value.toInt()
            background = GradientDrawable().apply {
                cornerRadius = 22f * context.resources.displayMetrics.density
                setColor(Color.argb((views.slOpacity.value * 255 / 100).toInt(), 20, 29, 43))
            }
        }
    }

}
