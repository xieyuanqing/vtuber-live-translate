package com.xyq.livetranslate

import android.os.Build
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33], application = android.app.Application::class)
class MainActivityStartupTest {
    @Test
    fun launchesWithoutCrashing() {
        val controller = Robolectric.buildActivity(MainActivity::class.java).setup()
        val activity = controller.get()
        require(activity != null)
        // touch plan library page existence
        require(activity.findViewById<android.view.View>(R.id.pagePlanLibrary) != null)
        require(activity.findViewById<android.view.View>(R.id.fabNewPlan) != null)
        require(activity.findViewById<android.view.View>(R.id.btnInterpOpenPlanLibrary) != null)
    }
}
