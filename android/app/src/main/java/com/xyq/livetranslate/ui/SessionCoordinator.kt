package com.xyq.livetranslate.ui

import android.Manifest
import android.app.Activity
import android.content.Context
import android.content.Intent
import android.media.projection.MediaProjectionManager
import android.os.Build
import android.os.Bundle
import com.xyq.livetranslate.CaptureService
import com.xyq.livetranslate.FriendGatewayStore
import com.xyq.livetranslate.PromptBuilder
import com.xyq.livetranslate.SceneLibraryStore
import com.xyq.livetranslate.SessionPromptContext
import com.xyq.livetranslate.SettingsStore
import com.xyq.livetranslate.StatusBus
import com.xyq.livetranslate.TranslationMode
import com.xyq.livetranslate.TranslationPlanStore

internal enum class PendingSessionStage {
    READY,
    WAITING_PERMISSION,
    WAITING_PROJECTION,
}

/**
 * Activity 在启动外部权限流程前冻结的内容/模式快照。
 *
 * 这里只保存 Service Intent 所需的非敏感内容和凭据模式选择；API Key、好友 token 与运行参数
 * 仍由 CaptureService 在真正启动时从 Store 读取并冻结。
 */
internal data class PendingSessionSnapshot(
    val captureMode: String,
    val credentialMode: String,
    val prompt: String,
    val sourceLanguageCode: String,
    val targetLanguageCode: String,
    val scenePresetId: String,
    val sceneLabel: String,
    val sessionTitle: String,
    val sessionContext: String,
    val stage: PendingSessionStage = PendingSessionStage.READY,
)

internal interface SessionContextAccess {
    fun current(mode: TranslationMode): SessionPromptContext
    fun clearAfterSuccessfulStart(mode: TranslationMode)
}

/** ActivityResultLauncher 与系统动作的窄 Activity 边界。 */
internal interface SessionHost {
    fun checkPermission(permission: String): Boolean
    fun requestPermissions(permissions: Array<String>)
    fun canDrawOverlays(): Boolean
    fun openOverlaySettings()
    fun launchProjection(intent: Intent)
    fun startForegroundService(intent: Intent)
    fun startService(intent: Intent)
    fun openTranslationSettings()
    fun toast(message: String)
}

/** 统一协调会话启停、运行时权限、MediaProjection 与冻结快照消费。 */
internal class SessionCoordinator(
    private val context: Context,
    private val persistDraftInputs: () -> Unit,
    sessionContextAccess: SessionContextAccess? = null,
    private val host: SessionHost,
) {
    private companion object {
        const val STATE_PENDING_START_MODE = "pending_start_mode"
        const val STATE_PENDING_CREDENTIAL_MODE = "pending_credential_mode"
        const val STATE_PERMISSION_REQUESTED = "permission_requested"
        const val STATE_PROJECTION_REQUESTED = "projection_requested"
        const val STATE_PENDING_STAGE = "pending_session_stage"
        const val STATE_PENDING_PROMPT = "pending_prompt"
        const val STATE_PENDING_SOURCE = "pending_source"
        const val STATE_PENDING_TARGET = "pending_target"
        const val STATE_PENDING_SCENE = "pending_scene"
        const val STATE_PENDING_SCENE_LABEL = "pending_scene_label"
        const val STATE_PENDING_TITLE = "pending_title"
        const val STATE_PENDING_CONTEXT = "pending_context"
    }

    private var pendingSnapshot: PendingSessionSnapshot? = null
    private var sessionContextAccess: SessionContextAccess? = sessionContextAccess

    /** 第二阶段接线：所有页面 controller 创建完成后再安装上下文访问边界。 */
    fun bindSessionContextAccess(access: SessionContextAccess) {
        check(sessionContextAccess == null || sessionContextAccess === access) {
            "SessionContextAccess 已绑定"
        }
        sessionContextAccess = access
    }

    fun onModeToggle(captureMode: String) {
        if (StatusBus.serviceRunning) {
            if (StatusBus.captureMode.isNotEmpty() && StatusBus.captureMode != captureMode) {
                val other = if (StatusBus.captureMode == StatusBus.MODE_MIC) "同传" else "视频字幕"
                host.toast("当前正在运行「$other」，请先停止后再切换")
                return
            }
            host.startService(
                Intent(context, CaptureService::class.java).setAction(CaptureService.ACTION_STOP),
            )
            return
        }
        startCapture(captureMode)
    }

    /** 权限 launcher 的结果只恢复原冻结快照，不重新读取 View 或 Store。 */
    fun onAudioPermissionResult() {
        val snapshot = pendingSnapshot
            ?.takeIf { it.stage == PendingSessionStage.WAITING_PERMISSION }
            ?: return
        val ready = snapshot.copy(stage = PendingSessionStage.READY)
        pendingSnapshot = ready
        continueStart(ready, returningFromPermissionRequest = true)
    }

    /** 投屏 launcher 的结果只消费处于投屏等待态的原冻结快照。 */
    fun onProjectionResult(resultCode: Int, data: Intent?) {
        val snapshot = pendingSnapshot
            ?.takeIf {
                it.captureMode == StatusBus.MODE_VIDEO &&
                    it.stage == PendingSessionStage.WAITING_PROJECTION
            }
            ?: return
        if (resultCode != Activity.RESULT_OK || data == null) {
            pendingSnapshot = snapshot.copy(stage = PendingSessionStage.READY)
            host.toast("未获得屏幕捕获授权，无法内录")
            return
        }

        val startIntent = captureStartIntent(snapshot)
            .putExtra(CaptureService.EXTRA_RESULT_CODE, resultCode)
            .putExtra(CaptureService.EXTRA_RESULT_DATA, data)
        host.startForegroundService(startIntent)
        consumeStartedSession(snapshot)
    }

    fun saveState(outState: Bundle) {
        val snapshot = pendingSnapshot ?: return
        outState.putString(STATE_PENDING_START_MODE, snapshot.captureMode)
        outState.putString(STATE_PENDING_CREDENTIAL_MODE, snapshot.credentialMode)
        outState.putBoolean(
            STATE_PERMISSION_REQUESTED,
            snapshot.stage == PendingSessionStage.WAITING_PERMISSION,
        )
        outState.putBoolean(
            STATE_PROJECTION_REQUESTED,
            snapshot.stage == PendingSessionStage.WAITING_PROJECTION,
        )
        outState.putString(STATE_PENDING_STAGE, snapshot.stage.name)
        outState.putString(STATE_PENDING_PROMPT, snapshot.prompt)
        outState.putString(STATE_PENDING_SOURCE, snapshot.sourceLanguageCode)
        outState.putString(STATE_PENDING_TARGET, snapshot.targetLanguageCode)
        outState.putString(STATE_PENDING_SCENE, snapshot.scenePresetId)
        outState.putString(STATE_PENDING_SCENE_LABEL, snapshot.sceneLabel)
        outState.putString(STATE_PENDING_TITLE, snapshot.sessionTitle)
        outState.putString(STATE_PENDING_CONTEXT, snapshot.sessionContext)
    }

    fun restoreState(savedState: Bundle?) {
        if (savedState == null) return
        val captureMode = savedState.getString(STATE_PENDING_START_MODE)
            ?.takeIf(::isCaptureMode)
            ?: return
        val credentialMode = savedState.getString(STATE_PENDING_CREDENTIAL_MODE)
            ?.takeIf(::isCredentialMode)
            ?: return
        val stage = savedState.getString(STATE_PENDING_STAGE)
            ?.let { stored -> PendingSessionStage.entries.firstOrNull { it.name == stored } }
            ?: when {
                savedState.getBoolean(STATE_PROJECTION_REQUESTED) ->
                    PendingSessionStage.WAITING_PROJECTION
                savedState.getBoolean(STATE_PERMISSION_REQUESTED) ->
                    PendingSessionStage.WAITING_PERMISSION
                else -> PendingSessionStage.READY
            }
        val restored = PendingSessionSnapshot(
            captureMode = captureMode,
            credentialMode = credentialMode,
            prompt = savedState.getString(STATE_PENDING_PROMPT).orEmpty(),
            sourceLanguageCode = savedState.getString(STATE_PENDING_SOURCE).orEmpty(),
            targetLanguageCode = savedState.getString(STATE_PENDING_TARGET).orEmpty(),
            scenePresetId = savedState.getString(STATE_PENDING_SCENE).orEmpty(),
            sceneLabel = savedState.getString(STATE_PENDING_SCENE_LABEL).orEmpty(),
            sessionTitle = savedState.getString(STATE_PENDING_TITLE).orEmpty(),
            sessionContext = savedState.getString(STATE_PENDING_CONTEXT).orEmpty(),
            stage = stage,
        )
        pendingSnapshot = restored.takeIf(::isValidSnapshot)
    }

    private fun startCapture(captureMode: String) {
        if (!isCaptureMode(captureMode)) return
        val reusable = pendingSnapshot?.takeIf {
            it.stage == PendingSessionStage.WAITING_PERMISSION &&
                it.captureMode == captureMode &&
                it.prompt.isNotBlank()
        }
        if (reusable != null) {
            val ready = reusable.copy(stage = PendingSessionStage.READY)
            pendingSnapshot = ready
            continueStart(ready, returningFromPermissionRequest = true)
            return
        }

        val prepared = prepareSessionSettings(captureMode) ?: return
        pendingSnapshot = prepared
        continueStart(prepared, returningFromPermissionRequest = false)
    }

    private fun prepareSessionSettings(captureMode: String): PendingSessionSnapshot? {
        val mode = promptMode(captureMode)
        persistDraftInputs()
        val plan = TranslationPlanStore.loadDraft(context, mode)
        val friendSelected = FriendGatewayStore.mode(context) == FriendGatewayStore.MODE_FRIEND
        val friendAccess = FriendGatewayStore.isActive(context)
        val credentialMode = if (friendSelected) {
            FriendGatewayStore.MODE_FRIEND
        } else {
            FriendGatewayStore.MODE_PERSONAL
        }
        if (friendSelected && !friendAccess) {
            host.toast("好友测试凭据已失效，请重新绑定邀请码")
            host.openTranslationSettings()
            return null
        }
        if (!friendAccess && SettingsStore.apiKeyList(context).isEmpty()) {
            host.toast("请先填 Gemini API Key")
            host.openTranslationSettings()
            return null
        }

        val scene = SceneLibraryStore.resolve(context, mode, plan.scenePresetId)
        val sessionContext = requireSessionContextAccess().current(mode)
        return PendingSessionSnapshot(
            captureMode = captureMode,
            credentialMode = credentialMode,
            prompt = PromptBuilder.build(
                scene = scene,
                context = sessionContext,
                plan = plan,
            ),
            sourceLanguageCode = plan.sourceLanguageCode,
            targetLanguageCode = plan.targetLanguageCode,
            scenePresetId = scene.id,
            sceneLabel = scene.label,
            sessionTitle = when (mode) {
                TranslationMode.INTERPRETATION -> "同传记录"
                TranslationMode.VIDEO -> "视频翻译"
            },
            sessionContext = sessionContext.manualContext.trim(),
        )
    }

    private fun continueStart(
        snapshot: PendingSessionSnapshot,
        returningFromPermissionRequest: Boolean,
    ) {
        if (!host.checkPermission(Manifest.permission.RECORD_AUDIO)) {
            if (returningFromPermissionRequest) {
                pendingSnapshot = snapshot.copy(stage = PendingSessionStage.READY)
                host.toast("缺少录音权限，请在系统设置中授予")
                return
            }
            val permissions = mutableListOf(Manifest.permission.RECORD_AUDIO)
            if (
                Build.VERSION.SDK_INT >= 33 &&
                !host.checkPermission(Manifest.permission.POST_NOTIFICATIONS)
            ) {
                permissions += Manifest.permission.POST_NOTIFICATIONS
            }
            pendingSnapshot = snapshot.copy(stage = PendingSessionStage.WAITING_PERMISSION)
            host.requestPermissions(permissions.toTypedArray())
            return
        }

        val ready = snapshot.copy(stage = PendingSessionStage.READY)
        pendingSnapshot = ready
        if (ready.captureMode == StatusBus.MODE_VIDEO) {
            if (!host.canDrawOverlays()) {
                host.toast("请开启悬浮窗权限，开启后回到本页再点开始")
                host.openOverlaySettings()
                return
            }
            pendingSnapshot = ready.copy(stage = PendingSessionStage.WAITING_PROJECTION)
            val projectionManager = context.getSystemService(MediaProjectionManager::class.java)
            host.launchProjection(projectionManager.createScreenCaptureIntent())
            return
        }

        host.startForegroundService(captureStartIntent(ready))
        consumeStartedSession(ready)
    }

    private fun consumeStartedSession(snapshot: PendingSessionSnapshot) {
        requireSessionContextAccess().clearAfterSuccessfulStart(promptMode(snapshot.captureMode))
        if (pendingSnapshot == snapshot || pendingSnapshot?.captureMode == snapshot.captureMode) {
            pendingSnapshot = null
        }
    }

    private fun captureStartIntent(snapshot: PendingSessionSnapshot): Intent =
        Intent(context, CaptureService::class.java)
            .setAction(CaptureService.ACTION_START)
            .putExtra(CaptureService.EXTRA_MODE, snapshot.captureMode)
            .putExtra(CaptureService.EXTRA_CREDENTIAL_MODE, snapshot.credentialMode)
            .putExtra(CaptureService.EXTRA_SESSION_PROMPT, snapshot.prompt)
            .putExtra(CaptureService.EXTRA_SOURCE_LANGUAGE, snapshot.sourceLanguageCode)
            .putExtra(CaptureService.EXTRA_TARGET_LANGUAGE, snapshot.targetLanguageCode)
            .putExtra(CaptureService.EXTRA_SCENE_PRESET, snapshot.scenePresetId)
            .putExtra(CaptureService.EXTRA_SCENE_LABEL, snapshot.sceneLabel)
            .putExtra(CaptureService.EXTRA_SESSION_TITLE, snapshot.sessionTitle)
            .putExtra(CaptureService.EXTRA_SESSION_CONTEXT, snapshot.sessionContext)

    private fun promptMode(captureMode: String): TranslationMode =
        if (captureMode == StatusBus.MODE_MIC) {
            TranslationMode.INTERPRETATION
        } else {
            TranslationMode.VIDEO
        }

    private fun isCaptureMode(value: String): Boolean =
        value == StatusBus.MODE_MIC || value == StatusBus.MODE_VIDEO

    private fun isCredentialMode(value: String): Boolean =
        value == FriendGatewayStore.MODE_PERSONAL || value == FriendGatewayStore.MODE_FRIEND

    private fun isValidSnapshot(snapshot: PendingSessionSnapshot): Boolean =
        isCaptureMode(snapshot.captureMode) &&
            isCredentialMode(snapshot.credentialMode) &&
            snapshot.prompt.isNotBlank() &&
            snapshot.sourceLanguageCode.isNotBlank() &&
            snapshot.targetLanguageCode.isNotBlank() &&
            snapshot.scenePresetId.isNotBlank() &&
            snapshot.sceneLabel.isNotBlank()

    private fun requireSessionContextAccess(): SessionContextAccess =
        checkNotNull(sessionContextAccess) { "SessionContextAccess 尚未绑定" }

    // 窄 internal 测试入口：只允许安装一个完整不可变 fixture，不暴露可变 pending 字段。
    internal fun installPendingSnapshotForTest(snapshot: PendingSessionSnapshot) {
        require(isValidSnapshot(snapshot))
        pendingSnapshot = snapshot
    }

    internal fun beginPreparedSessionForTest(snapshot: PendingSessionSnapshot) {
        require(isValidSnapshot(snapshot))
        val ready = snapshot.copy(stage = PendingSessionStage.READY)
        pendingSnapshot = ready
        continueStart(ready, returningFromPermissionRequest = false)
    }

    internal fun pendingSnapshotForTest(): PendingSessionSnapshot? = pendingSnapshot

    internal fun captureStartIntentForTest(): Intent =
        captureStartIntent(requireNotNull(pendingSnapshot) { "没有待启动会话快照" })

    internal fun prepareSessionSettingsForTest(captureMode: String): Boolean {
        val prepared = prepareSessionSettings(captureMode) ?: return false
        pendingSnapshot = prepared
        return true
    }
}
