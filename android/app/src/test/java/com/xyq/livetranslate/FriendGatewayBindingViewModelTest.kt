package com.xyq.livetranslate

import android.app.Application
import androidx.test.core.app.ApplicationProvider
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33], application = Application::class)
class FriendGatewayBindingViewModelTest {
    @Test
    fun successfulBindingSavesWithoutActivityObserver() {
        val backend = FakeBackend()
        val viewModel = viewModel(backend)

        assertTrue(viewModel.bind("invite", "test", enableFriendOnSuccess = true))
        assertTrue(backend.started.await(2, TimeUnit.SECONDS))
        backend.release.countDown()
        awaitPhase(viewModel, FriendGatewayBindingPhase.SUCCESS)

        assertEquals(1, backend.saved)
        assertEquals(1, backend.friendEnabled)
    }

    @Test
    fun clearInvalidatesLateBindingResult() {
        val backend = FakeBackend()
        val viewModel = viewModel(backend)

        assertTrue(viewModel.bind("invite", "test", enableFriendOnSuccess = true))
        assertTrue(backend.started.await(2, TimeUnit.SECONDS))
        viewModel.clearBinding()
        backend.release.countDown()
        assertTrue(backend.finished.await(2, TimeUnit.SECONDS))
        Thread.sleep(50)

        assertEquals(0, backend.saved)
        assertEquals(0, backend.friendEnabled)
        assertEquals(1, backend.cleared)
        assertEquals(FriendGatewayBindingPhase.IDLE, viewModel.snapshot().phase)
    }

    @Test
    fun personalModeRebindDoesNotEnableFriendMode() {
        val backend = FakeBackend()
        val viewModel = viewModel(backend)

        assertTrue(viewModel.bind("invite", "test", enableFriendOnSuccess = false))
        assertTrue(backend.started.await(2, TimeUnit.SECONDS))
        backend.release.countDown()
        awaitPhase(viewModel, FriendGatewayBindingPhase.SUCCESS)

        assertEquals(1, backend.saved)
        assertEquals(0, backend.friendEnabled)
        assertFalse(viewModel.isBinding())
    }

    private fun viewModel(backend: FriendGatewayBindingBackend) =
        FriendGatewayBindingViewModel(
            ApplicationProvider.getApplicationContext(),
            backend,
        )

    private fun awaitPhase(
        viewModel: FriendGatewayBindingViewModel,
        phase: FriendGatewayBindingPhase,
    ) {
        val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(3)
        while (System.nanoTime() < deadline) {
            if (viewModel.snapshot().phase == phase) return
            Thread.sleep(10)
        }
        throw AssertionError("Expected $phase, got ${viewModel.snapshot()}")
    }

    private class FakeBackend : FriendGatewayBindingBackend {
        val started = CountDownLatch(1)
        val release = CountDownLatch(1)
        val finished = CountDownLatch(1)

        @Volatile var saved = 0
        @Volatile var friendEnabled = 0
        @Volatile var cleared = 0

        override fun bind(inviteCode: String, appVersion: String): FriendGatewayBinding {
            started.countDown()
            check(release.await(2, TimeUnit.SECONDS))
            finished.countDown()
            return FriendGatewayBinding("token", 4_000_000_000L, "friend")
        }

        override fun saveBinding(binding: FriendGatewayBinding): Boolean {
            saved += 1
            return true
        }

        override fun useFriend(): Boolean {
            friendEnabled += 1
            return true
        }

        override fun clearBinding() {
            cleared += 1
        }
    }
}
