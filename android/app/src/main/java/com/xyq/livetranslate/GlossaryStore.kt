package com.xyq.livetranslate

import android.content.Context
import org.json.JSONArray
import java.util.UUID

/** 通用术语库。 */
object GlossaryStore {
    private const val PREFS = "glossary_profiles_v2"
    private const val KEY_PROFILES = "profiles"

    fun list(context: Context): List<GlossaryProfile> {
        val raw = prefs(context).getString(KEY_PROFILES, null) ?: return emptyList()
        return runCatching {
            val array = JSONArray(raw)
            buildList {
                for (index in 0 until array.length()) {
                    val json = array.optJSONObject(index) ?: continue
                    val profile = GlossaryProfileJson.decode(json)
                    if (profile.isUsable) add(profile)
                }
            }
        }.getOrDefault(emptyList())
    }

    fun find(context: Context, id: String): GlossaryProfile? =
        list(context).firstOrNull { it.id == id }

    @Synchronized
    fun upsert(
        context: Context,
        profile: GlossaryProfile,
    ): GlossaryProfile {
        val normalized = profile.copy(
            id = profile.id.trim().ifEmpty { UUID.randomUUID().toString() },
            name = profile.name.trim().ifEmpty { "未命名术语库" },
        ).normalized()
        val updated = list(context).toMutableList().apply {
            val index = indexOfFirst { it.id == normalized.id }
            if (index >= 0) set(index, normalized) else add(normalized)
        }
        write(context, updated)
        return normalized
    }

    @Synchronized
    fun delete(context: Context, id: String) {
        write(context, list(context).filterNot { it.id == id })
    }

    private fun write(context: Context, profiles: List<GlossaryProfile>) {
        val array = JSONArray()
        profiles.forEach { array.put(GlossaryProfileJson.encode(it)) }
        prefs(context).edit().putString(KEY_PROFILES, array.toString()).apply()
    }

    private fun prefs(context: Context) =
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
}
