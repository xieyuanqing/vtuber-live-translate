package com.xyq.livetranslate

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Test
import java.io.File

class UpdateIntegrityTest {

    @Test
    fun sha256OfMatchesKnownDigest() {
        val file = File.createTempFile("update-integrity", ".bin").apply {
            deleteOnExit()
            writeBytes("hello".toByteArray())
        }
        assertEquals(
            "2cf24dba5fb0a30e26e83b2ac5b9e29e1b161e5c1fa7425e73043362938b9824",
            UpdateIntegrity.sha256Of(file),
        )
    }

    @Test
    fun sha256OfChangesWhenContentChanges() {
        val a = File.createTempFile("update-integrity-a", ".bin").apply {
            deleteOnExit()
            writeBytes(ByteArray(200_000) { it.toByte() })
        }
        val b = File.createTempFile("update-integrity-b", ".bin").apply {
            deleteOnExit()
            writeBytes(ByteArray(200_000) { (it + 1).toByte() })
        }
        assertNotEquals(UpdateIntegrity.sha256Of(a), UpdateIntegrity.sha256Of(b))
    }
}
