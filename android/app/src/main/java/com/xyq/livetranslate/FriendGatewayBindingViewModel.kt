package com.xyq.livetranslate

import android.app.Application
import android.os.Looper
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.LiveData
import androidx.lifecycle.MutableLiveData

internal interface FriendGatewayBindingBackend {
    fun bind(inviteCode: String, appVersion: String): FriendGatewayBinding
    fun saveBinding(binding: FriendGatewayBinding): Boolean
    fun useFriend(): Boolean
    fun clearBinding()
}

private class AndroidFriendGatewayBindingBackend(
    application: Application,
) : FriendGatewayBindingBackend {
    private val appContext = application.applicationContext

    override fun bind(inviteCode: String, appVersion: String): FriendGatewayBinding =
        FriendGatewayClient(appContext).bind(inviteCode, appVersion)

    override fun saveBinding(binding: FriendGatewayBinding): Boolean =
        FriendGatewayStore.saveBinding(
            appContext,
            binding.accessToken,
            binding.label,
            binding.tokenExpiresAt,
        )

    override fun useFriend(): Boolean = FriendGatewayStore.useFriend(appContext)

    override fun clearBinding() = FriendGatewayStore.clearBinding(appContext)
}

enum class FriendGatewayBindingPhase {
    IDLE,
    BINDING,
    SUCCESS,
    FAILURE,
}

data class FriendGatewayBindingState(
    val generation: Long = 0,
    val phase: FriendGatewayBindingPhase = FriendGatewayBindingPhase.IDLE,
    val message: String = "",
)

class FriendGatewayBindingViewModel internal constructor(
    application: Application,
    private val backend: FriendGatewayBindingBackend,
) : AndroidViewModel(application) {
    constructor(application: Application) : this(
        application,
        AndroidFriendGatewayBindingBackend(application),
    )

    private val lock = Any()
    private val mutableState = MutableLiveData(FriendGatewayBindingState())
    val state: LiveData<FriendGatewayBindingState> = mutableState

    @Volatile
    private var currentState = FriendGatewayBindingState()

    fun isBinding(): Boolean = currentState.phase == FriendGatewayBindingPhase.BINDING

    fun bind(
        inviteCode: String,
        appVersion: String,
        enableFriendOnSuccess: Boolean,
    ): Boolean {
        val generation: Long
        synchronized(lock) {
            if (currentState.phase == FriendGatewayBindingPhase.BINDING) return false
            generation = currentState.generation + 1
            publishLocked(
                FriendGatewayBindingState(
                    generation = generation,
                    phase = FriendGatewayBindingPhase.BINDING,
                ),
            )
        }

        Thread({
            val result = runCatching { backend.bind(inviteCode, appVersion) }
            synchronized(lock) {
                if (
                    currentState.generation != generation ||
                    currentState.phase != FriendGatewayBindingPhase.BINDING
                ) return@synchronized

                val next = result.fold(
                    onSuccess = { binding ->
                        val stored = runCatching { backend.saveBinding(binding) }
                            .getOrDefault(false)
                        val activated = stored && (
                            !enableFriendOnSuccess ||
                                runCatching { backend.useFriend() }.getOrDefault(false)
                            )
                        if (activated) {
                            FriendGatewayBindingState(
                                generation,
                                FriendGatewayBindingPhase.SUCCESS,
                                "好友测试凭据已保存",
                            )
                        } else {
                            FriendGatewayBindingState(
                                generation,
                                FriendGatewayBindingPhase.FAILURE,
                                if (stored) {
                                    "绑定成功，但无法启用好友测试通道"
                                } else {
                                    "绑定成功，但系统安全存储不可用"
                                },
                            )
                        }
                    },
                    onFailure = { error ->
                        FriendGatewayBindingState(
                            generation,
                            FriendGatewayBindingPhase.FAILURE,
                            "绑定失败：${error.message ?: "未知错误"}",
                        )
                    },
                )
                publishLocked(next)
            }
        }, "friend-gateway-bind").start()
        return true
    }

    fun clearBinding() {
        synchronized(lock) {
            runCatching { backend.clearBinding() }
            publishLocked(
                FriendGatewayBindingState(
                    generation = currentState.generation + 1,
                    phase = FriendGatewayBindingPhase.IDLE,
                ),
            )
        }
    }

    internal fun snapshot(): FriendGatewayBindingState = currentState

    private fun publishLocked(state: FriendGatewayBindingState) {
        currentState = state
        if (Looper.myLooper() == Looper.getMainLooper()) {
            mutableState.value = state
        } else {
            mutableState.postValue(state)
        }
    }
}
