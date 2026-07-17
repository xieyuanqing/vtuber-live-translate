package com.xyq.livetranslate

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33], application = android.app.Application::class)
class TranslationPlanStoreTest {
    private val context: Context
        get() = ApplicationProvider.getApplicationContext()

    @After
    fun clearStore() {
        context.getSharedPreferences("translation_plans_v2", Context.MODE_PRIVATE)
            .edit()
            .clear()
            .commit()
    }

    @Test
    fun editingSavedPlanUpdatesInPlaceWithoutChangingDraft() {
        clearStore()
        val mode = TranslationMode.VIDEO
        val originalDraft = TranslationPlan.default(mode)
        TranslationPlanStore.saveDraft(context, originalDraft)
        val saved = TranslationPlanStore.saveAs(
            context,
            mode,
            "直播方案",
            originalDraft.copy(scenePresetId = "livestream"),
        )

        val updated = TranslationPlanStore.updateSaved(
            context,
            mode,
            saved.id,
            "动漫方案",
            saved.plan.copy(scenePresetId = "anime"),
        )

        assertNotNull(updated)
        val all = TranslationPlanStore.listSaved(context, mode)
        assertEquals(1, all.size)
        assertEquals(saved.id, all.single().id)
        assertEquals("动漫方案", all.single().name)
        assertEquals("anime", all.single().plan.scenePresetId)
        assertEquals(originalDraft, TranslationPlanStore.loadDraft(context, mode))
    }

    @Test
    fun updatingMissingSavedPlanReturnsNullWithoutChangingStore() {
        clearStore()
        val mode = TranslationMode.INTERPRETATION
        val draft = TranslationPlan.default(mode)
        TranslationPlanStore.saveDraft(context, draft)

        val updated = TranslationPlanStore.updateSaved(
            context,
            mode,
            "missing-id",
            "不存在的方案",
            draft.copy(scenePresetId = "meeting"),
        )

        assertNull(updated)
        assertEquals(emptyList<SavedTranslationPlan>(), TranslationPlanStore.listSaved(context, mode))
        assertEquals(draft, TranslationPlanStore.loadDraft(context, mode))
    }
}
