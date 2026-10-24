package com.xyq.livetranslate.ui

import android.annotation.SuppressLint
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.PowerManager
import android.provider.Settings
import android.view.View
import com.xyq.livetranslate.R
import com.xyq.livetranslate.SettingsStore

/** 诊断、关于与更新。 */
internal class MaintenanceSettingsSection(
    private val context: Context,
    private val views: SettingsViews,
    private val launchIntent: (Intent) -> Unit,
    private val toast: (String) -> Unit,
    private val onCheckUpdate: () -> Unit,
) {
    private var syncingAutoCheckUpdateUi = false

    fun setup() {
        setupAbout()
        views.btnBattery.setOnClickListener { requestBatteryWhitelist() }
    }

    fun renderDiagnostics(state: SettingsDiagnosticsState) {
        val level = state.audioLevelPct.coerceIn(0, 100)
        views.tvStatus.text = buildString {
            append(if (state.serviceRunning) context.getString(R.string.rt_diag_status_running) else context.getString(R.string.rt_diag_status_stopped))
            if (state.captureMode.isNotEmpty()) {
                val modeStr = if (state.captureMode == "mic") context.getString(R.string.rt_mode_interpretation) else context.getString(R.string.rt_mode_video)
                append(context.getString(R.string.rt_diag_mode_prefix)).append(modeStr)
            }
            append(context.getString(R.string.rt_diag_connection_prefix)).append(state.connState)
            if (state.currentKeyLabel.isNotEmpty()) append("  ").append(state.currentKeyLabel)
            append(context.getString(R.string.rt_diag_volume_prefix)).append(level).append("%")
            append(context.getString(R.string.rt_diag_sent_chunks, state.chunksSent))
            if (state.transcriptPath.isNotEmpty()) append(context.getString(R.string.rt_diag_record_prefix)).append(state.transcriptPath).append("\n")
            if (state.jaTail.isNotEmpty()) append("ja: …").append(state.jaTail).append("\n")
            if (state.zhTail.isNotEmpty()) append("zh: …").append(state.zhTail)
        }
        views.tvApiStatus?.text = context.getString(R.string.rt_diag_connection_label, state.connState)
        views.tvAudioStatus?.text = context.getString(R.string.rt_diag_volume_label, level)
        views.tvPerfStatus?.text = context.getString(R.string.rt_diag_sent_label, state.chunksSent)
        views.tvCredentialStatus?.text = state.currentKeyLabel.ifBlank { context.getString(R.string.rt_diag_no_credential) }
    }
    private fun setupAbout() {
        val info = runCatching {
            context.packageManager.getPackageInfo(context.packageName, 0)
        }.getOrNull()
        views.tvAboutVersion.text = if (info != null) {
            context.getString(R.string.rt_about_version_format, info.versionName, info.longVersionCode)
        } else {
            context.getString(R.string.rt_about_version_unknown)
        }
        syncingAutoCheckUpdateUi = true
        views.swAutoCheckUpdate.isChecked = SettingsStore.autoCheckUpdate(context)
        syncingAutoCheckUpdateUi = false
        views.swAutoCheckUpdate.setOnCheckedChangeListener { _, checked ->
            if (syncingAutoCheckUpdateUi) return@setOnCheckedChangeListener
            SettingsStore.saveAutoCheckUpdate(context, checked)
            toast(if (checked) context.getString(R.string.rt_toast_auto_check_update_enabled) else context.getString(R.string.rt_toast_auto_check_update_disabled))
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
            }.onFailure { toast(context.getString(R.string.rt_toast_no_browser)) }
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
            toast(context.getString(R.string.rt_toast_battery_already_whitelisted))
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
