package com.xyq.livetranslate

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.xmlpull.v1.XmlPullParser

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33])
class ApiKeyPersistenceTest {
    private val context = ApplicationProvider.getApplicationContext<Context>()

    @After
    fun clearPrefs() {
        context.getSharedPreferences("settings", Context.MODE_PRIVATE).edit().clear().commit()
    }

    @Test
    fun emptySaveKeepsCiphertextThatCannotBeDecrypted() {
        val prefs = context.getSharedPreferences("settings", Context.MODE_PRIVATE)
        prefs.edit()
            .putString("apiKeysEnc", "not-valid-ciphertext")
            .putString("secondAiGeminiApiKeyEnc", "gemini-blob")
            .putString("secondAiCustomApiKeyEnc", "custom-blob")
            .commit()

        SettingsStore.saveApiKeys(context, "")
        SettingsStore.saveApiKeys(context, "   ")
        SettingsStore.saveSecondAiGeminiApiKey(context, "")
        SettingsStore.saveSecondAiCustomApiKey(context, "")

        assertEquals("not-valid-ciphertext", prefs.getString("apiKeysEnc", null))
        assertEquals("gemini-blob", prefs.getString("secondAiGeminiApiKeyEnc", null))
        assertEquals("custom-blob", prefs.getString("secondAiCustomApiKeyEnc", null))
        assertEquals("", SettingsStore.apiKeysRaw(context))
        assertTrue(KeystoreCrypto.decryptResult("not-valid-ciphertext") is KeystoreCrypto.DecryptResult.Failed)
    }

    @Test
    fun backupRulesExcludeSettingsAndDropTheRemovedGateway() {
        val cloud = xmlText(R.xml.backup_rules)
        val legacy = xmlText(R.xml.backup_rules_legacy)
        assertTrue(cloud.contains("settings.xml"))
        assertTrue(legacy.contains("settings.xml"))
        assertFalse(cloud.contains("friend_gateway"))
        assertFalse(legacy.contains("friend_gateway"))
    }

    private fun xmlText(resId: Int): String = buildString {
        val parser = context.resources.getXml(resId)
        var event = parser.eventType
        while (event != XmlPullParser.END_DOCUMENT) {
            if (event == XmlPullParser.START_TAG) {
                append(parser.name)
                for (index in 0 until parser.attributeCount) {
                    append(' ').append(parser.getAttributeValue(index))
                }
            }
            event = parser.next()
        }
    }
}
