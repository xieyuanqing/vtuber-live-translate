package com.xyq.livetranslate

import android.content.Context
import org.json.JSONObject

/** 本地主播资料库。先用 SharedPreferences 存 JSON，后续资料复杂了再迁移 Room/文件。 */
object StreamerProfileStore {
    private const val PREF = "streamer_profiles"
    private const val KEY_PROFILES = "profiles"
    const val DEFAULT_KEY = "通用VTuber"

    val DEFAULT_PROFILE = StreamerProfile(
        key = DEFAULT_KEY,
        nameJp = "",
        nameZh = "通用VTuber",
        affiliation = "VTuber / 直播主",
        category = "通用",
        aliases = listOf("VTuber", "主播"),
        terms = listOf("YouTube = YouTube", "SC = SC", "メンバーシップ = 会员"),
        misheard = emptyList(),
        style = "自然中文，适合 VTuber 粉丝实时观看；专名尽量保留圈内常见说法，不确定时保留原文。",
    )

    fun all(c: Context): List<StreamerProfile> {
        val json = profilesJson(c)
        val out = ArrayList<StreamerProfile>()
        json.keys().forEach { key ->
            val p = runCatching { StreamerProfileJson.decode(json.getJSONObject(key)) }.getOrNull()
            if (p != null && p.key.isNotBlank()) out += p
        }
        return out.sortedBy { it.key }
    }

    fun names(c: Context): List<String> = all(c).map { it.key }

    fun get(c: Context, key: String): StreamerProfile =
        all(c).firstOrNull { it.key == key } ?: DEFAULT_PROFILE

    fun selectedKey(c: Context): String =
        prefs(c).getString("selected", DEFAULT_KEY) ?: DEFAULT_KEY

    fun setSelected(c: Context, key: String) {
        prefs(c).edit().putString("selected", key).apply()
    }

    fun save(c: Context, p: StreamerProfile) {
        val key = p.key.ifBlank { DEFAULT_KEY }
        val normalized = p.copy(key = key)
        val json = profilesJson(c)
        json.put(key, StreamerProfileJson.encode(normalized))
        prefs(c).edit()
            .putString(KEY_PROFILES, json.toString())
            .putString("selected", key)
            .apply()
    }

    private fun profilesJson(c: Context): JSONObject {
        val raw = prefs(c).getString(KEY_PROFILES, "") ?: ""
        val json = runCatching { JSONObject(raw) }.getOrDefault(JSONObject())
        if (json.length() == 0) {
            json.put(DEFAULT_KEY, StreamerProfileJson.encode(DEFAULT_PROFILE))
            prefs(c).edit().putString(KEY_PROFILES, json.toString()).apply()
        }
        return json
    }

    private fun prefs(c: Context) = c.getSharedPreferences(PREF, Context.MODE_PRIVATE)
}
