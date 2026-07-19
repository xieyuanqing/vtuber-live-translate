package com.xyq.livetranslate

import android.os.Handler
import android.os.Looper
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

@RunWith(RobolectricTestRunner::class)
class SubtitleStabilizerTest {
    @Test
    fun keepsEverySentenceFromOneServerFragment() {
        val renders = mutableListOf<Pair<String, String>>()
        val stabilizer = SubtitleStabilizer(
            handler = Handler(Looper.getMainLooper()),
            onRender = { confirmed, current -> renders += confirmed to current },
        )

        stabilizer.onFragment("第一句。第二句！第三句？")

        assertEquals("第一句。第二句！第三句？", renders.last().first)
        assertEquals("", renders.last().second)
    }
}
