package com.xyq.livetranslate

import org.json.JSONArray
import org.json.JSONObject

/**
 * VTuber 主播资料。本地保存，后续 prompt 生成只依赖这个结构。
 */
data class StreamerProfile(
    val key: String,
    val nameJp: String,
    val nameZh: String,
    val affiliation: String,
    val aliases: List<String>,
    val terms: List<String>,
    val misheard: List<String>,
    val style: String,
) {
    fun displayName(): String = nameJp.ifBlank { nameZh.ifBlank { key } }
}

data class YouTubeVideoInfo(
    val url: String,
    val title: String,
    val authorName: String,
)

object StreamerProfileJson {
    fun encode(p: StreamerProfile): JSONObject = JSONObject()
        .put("key", p.key)
        .put("nameJp", p.nameJp)
        .put("nameZh", p.nameZh)
        .put("affiliation", p.affiliation)
        .put("aliases", JSONArray(p.aliases))
        .put("terms", JSONArray(p.terms))
        .put("misheard", JSONArray(p.misheard))
        .put("style", p.style)

    fun decode(o: JSONObject): StreamerProfile = StreamerProfile(
        key = o.optString("key"),
        nameJp = o.optString("nameJp"),
        nameZh = o.optString("nameZh"),
        affiliation = o.optString("affiliation"),
        aliases = o.optJSONArray("aliases").toStringList(),
        terms = o.optJSONArray("terms").toStringList(),
        misheard = o.optJSONArray("misheard").toStringList(),
        style = o.optString("style"),
    )

    private fun JSONArray?.toStringList(): List<String> {
        if (this == null) return emptyList()
        val out = ArrayList<String>()
        for (i in 0 until length()) {
            optString(i).trim().takeIf { it.isNotEmpty() }?.let(out::add)
        }
        return out
    }
}
