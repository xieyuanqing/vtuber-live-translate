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
            if (manual) toast("正在检查或下载更新…")
            return
        }
        if (manual) toast("正在检查更新…")
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
                toast("已是最新版本 ${currentVersionName()}（${result.currentCode}）")
            }
            UpdateCheckResult.Ignored -> if (manual) {
                toast("已忽略此版本；可在关于页再次检查")
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
            append("当前 ${currentVersionName()}（${currentVersionCode()}）\n")
            append("最新 ${info.versionName}（${info.versionCode}）\n\n")
            if (info.notes.isNotBlank()) append(info.notes.trim())
            else append("有新版本可用。")
        }
        MaterialAlertDialogBuilder(activity)
            .setTitle(info.title.ifBlank { "发现新版本 ${info.versionName}" })
            .setMessage(message)
            .setPositiveButton("下载并安装") { _, _ -> startDownload(info) }
            .setNeutralButton("忽略此版本") { _, _ ->
                SettingsStore.saveIgnoredUpdateVersionCode(activity, info.versionCode)
                toast("已忽略 ${info.versionName}")
            }
            .setNegativeButton("不再提醒") { _, _ ->
                SettingsStore.saveAutoCheckUpdate(activity, false)
                onStatusChanged?.invoke(lastFailureMessage)
                toast("已关闭启动时自动检查；可在关于页手动检查")
            }
            .setCancelable(true)
            .show()
    }

    private fun startDownload(info: AppUpdateInfo) {
        if (!busy.compareAndSet(false, true)) {
            toast("已有下载任务进行中")
            return
        }
        val view = LayoutInflater.from(activity).inflate(R.layout.dialog_update_progress, null)
        val tv = view.findViewById<TextView>(R.id.tvUpdateProgress)
        val bar = view.findViewById<ProgressBar>(R.id.pbUpdateProgress)
        tv.text = "准备下载…"
        bar.isIndeterminate = true
        val dialog = MaterialAlertDialogBuilder(activity)
            .setTitle("下载更新")
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
                            tv.text = "源 ${progress.sourceIndex}/${progress.sourceTotal} · ${progress.sourceLabel}\n$pct%"
                        } else {
                            bar.isIndeterminate = true
                            val kb = progress.bytesRead / 1024
                            tv.text = "源 ${progress.sourceIndex}/${progress.sourceTotal} · ${progress.sourceLabel}\n已下载 ${kb} KB"
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
                    toast("下载失败：${e.message ?: "未知错误"}")
                }
            }
        }, "update-download").start()
    }

    fun installApk(file: java.io.File) {
        if (!UpdateInstaller.canRequestPackageInstalls(activity)) {
            pendingInstallApk = file
            toast("请允许安装未知应用，返回后将继续安装")
            runCatching {
                launchIntent(UpdateInstaller.unknownSourcesSettingsIntent(activity))
            }.onFailure { toast("无法打开安装权限设置") }
            return
        }
        pendingInstallApk = null
        runCatching {
            launchIntent(UpdateInstaller.installIntent(activity, file))
        }.onFailure {
            toast("无法打开安装界面：${it.message ?: "未知错误"}")
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
