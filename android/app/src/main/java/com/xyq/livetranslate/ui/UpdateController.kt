package com.xyq.livetranslate.ui

import android.app.Activity
import android.content.Context
import android.view.LayoutInflater
import android.widget.ProgressBar
import android.widget.TextView
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.xyq.livetranslate.AppUpdateInfo
import com.xyq.livetranslate.R
import com.xyq.livetranslate.SettingsStore
import com.xyq.livetranslate.UpdateCheckResult
import com.xyq.livetranslate.UpdateChecker
import com.xyq.livetranslate.UpdateDownloader
import com.xyq.livetranslate.UpdateInstaller
import java.util.concurrent.atomic.AtomicBoolean

/**
 * 检查更新 / 下载 / 安装协调。
 * 自动检查静默忽略失败；手动检查会 toast 失败原因。
 */
internal class UpdateController(
    private val activity: Activity,
    private val postToUi: (() -> Unit) -> Unit,
    private val isHostActive: () -> Boolean,
    private val launchIntent: (android.content.Intent) -> Unit,
    private val toast: (String) -> Unit,
) {
    private val busy = AtomicBoolean(false)
    private var pendingInstallApk: java.io.File? = null

    fun currentVersionCode(context: Context = activity): Long =
        context.packageManager.getPackageInfo(context.packageName, 0).longVersionCode

    fun currentVersionName(context: Context = activity): String {
        return runCatching {
            context.packageManager.getPackageInfo(context.packageName, 0).versionName
        }.getOrNull().orEmpty().ifBlank { "?" }
    }

    /** 启动时自动检查（可关闭）。 */
    fun autoCheckOnLaunch() {
        if (!SettingsStore.autoCheckUpdate(activity)) return
        check(manual = false)
    }

    fun check(manual: Boolean) {
        if (!busy.compareAndSet(false, true)) {
            if (manual) toast(activity.getString(R.string.rt_toast_checking_or_downloading_update))
            return
        }
        if (manual) toast(activity.getString(R.string.rt_toast_checking_update))
        Thread({
            val current = currentVersionCode()
            val ignored = SettingsStore.ignoredUpdateVersionCode(activity)
            val result = UpdateChecker.check(current, ignored)
            postToUi {
                busy.set(false)
                if (!isHostActive()) return@postToUi
                handleCheckResult(result, manual)
            }
        }, "update-check").start()
    }

    private fun handleCheckResult(result: UpdateCheckResult, manual: Boolean) {
        when (result) {
            is UpdateCheckResult.Available -> showUpdateDialog(result.info)
            is UpdateCheckResult.UpToDate -> if (manual) {
                toast(activity.getString(R.string.rt_toast_up_to_date, currentVersionName(), result.currentCode))
            }
            UpdateCheckResult.Ignored -> if (manual) {
                toast(activity.getString(R.string.rt_toast_version_ignored))
            }
            is UpdateCheckResult.Failed -> if (manual) {
                toast(result.message)
            }
            // 自动检查失败只静默；关于页可看到小字提示由调用方设置。
        }
        lastFailureMessage = (result as? UpdateCheckResult.Failed)?.message
        onStatusChanged?.invoke(lastFailureMessage)
    }

    var lastFailureMessage: String? = null
        private set
    var onStatusChanged: ((String?) -> Unit)? = null

    private fun showUpdateDialog(info: AppUpdateInfo) {
        val message = buildString {
            append(activity.getString(R.string.rt_update_dialog_version_info, currentVersionName(), currentVersionCode(), info.versionName, info.versionCode))
            if (info.notes.isNotBlank()) append(info.notes.trim())
            else append(activity.getString(R.string.rt_update_dialog_default_notes))
        }
        MaterialAlertDialogBuilder(activity)
            .setTitle(info.title.ifBlank { activity.getString(R.string.rt_update_dialog_title, info.versionName) })
            .setMessage(message)
            .setPositiveButton(activity.getString(R.string.rt_update_action_download_and_install)) { _, _ -> startDownload(info) }
            .setNeutralButton(activity.getString(R.string.rt_update_action_ignore_version)) { _, _ ->
                SettingsStore.saveIgnoredUpdateVersionCode(activity, info.versionCode)
                toast(activity.getString(R.string.rt_toast_ignored_version, info.versionName))
            }
            .setNegativeButton(activity.getString(R.string.rt_update_action_dont_remind)) { _, _ ->
                SettingsStore.saveAutoCheckUpdate(activity, false)
                onStatusChanged?.invoke(lastFailureMessage)
                toast(activity.getString(R.string.rt_toast_auto_check_disabled_hint))
            }
            .setCancelable(true)
            .show()
    }

    private fun startDownload(info: AppUpdateInfo) {
        if (!busy.compareAndSet(false, true)) {
            toast(activity.getString(R.string.rt_toast_download_in_progress))
            return
        }
        val view = LayoutInflater.from(activity).inflate(R.layout.dialog_update_progress, null)
        val tv = view.findViewById<TextView>(R.id.tvUpdateProgress)
        val bar = view.findViewById<ProgressBar>(R.id.pbUpdateProgress)
        tv.text = activity.getString(R.string.rt_update_preparing_download)
        bar.isIndeterminate = true
        val dialog = MaterialAlertDialogBuilder(activity)
            .setTitle(activity.getString(R.string.rt_update_download_dialog_title))
            .setView(view)
            .setCancelable(false)
            .create()
        dialog.show()

        Thread({
            try {
                val file = UpdateDownloader.download(activity, info) { progress ->
                    postToUi {
                        if (!isHostActive()) return@postToUi
                        val pct = progress.percent
                        if (pct >= 0) {
                            bar.isIndeterminate = false
                            bar.max = 100
                            bar.progress = pct
                            tv.text = activity.getString(R.string.rt_update_progress_pct, progress.sourceIndex, progress.sourceTotal, progress.sourceLabel, pct)
                        } else {
                            bar.isIndeterminate = true
                            val kb = progress.bytesRead / 1024
                            tv.text = activity.getString(R.string.rt_update_progress_kb, progress.sourceIndex, progress.sourceTotal, progress.sourceLabel, kb)
                        }
                    }
                }
                postToUi {
                    busy.set(false)
                    dialog.dismiss()
                    if (!isHostActive()) return@postToUi
                    installApk(file)
                }
            } catch (e: Exception) {
                postToUi {
                    busy.set(false)
                    dialog.dismiss()
                    if (!isHostActive()) return@postToUi
                    toast(activity.getString(R.string.rt_update_download_failed, e.message ?: activity.getString(R.string.rt_unknown_error)))
                }
            }
        }, "update-download").start()
    }

    fun installApk(file: java.io.File) {
        if (!UpdateInstaller.canRequestPackageInstalls(activity)) {
            pendingInstallApk = file
            toast(activity.getString(R.string.rt_update_toast_unknown_sources_required))
            runCatching {
                launchIntent(UpdateInstaller.unknownSourcesSettingsIntent(activity))
            }.onFailure { toast(activity.getString(R.string.rt_update_toast_cannot_open_install_settings)) }
            return
        }
        pendingInstallApk = null
        runCatching {
            launchIntent(UpdateInstaller.installIntent(activity, file))
        }.onFailure {
            toast(activity.getString(R.string.rt_update_toast_cannot_open_installer, it.message ?: activity.getString(R.string.rt_unknown_error)))
        }
    }

    /** 从未知来源设置返回后继续安装。 */
    fun onHostResume() {
        val apk = pendingInstallApk ?: return
        if (!UpdateInstaller.canRequestPackageInstalls(activity)) return
        pendingInstallApk = null
        installApk(apk)
    }
}
