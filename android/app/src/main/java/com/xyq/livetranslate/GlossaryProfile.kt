package com.xyq.livetranslate

import org.json.JSONArray
import org.json.JSONObject

/** 可复用于同传和视频的专名、固定译法及误识别修正资料。 */
data class GlossaryProfile(
    val id: String,
    val name: String,
    val category: String = "通用",
    val description: String = "",
    val aliases: List<String> = emptyList(),
    val terms: List<String> = emptyList(),
    val corrections: List<String> = emptyList(),
    val style: String = "",
) {
    fun normalized(): GlossaryProfile = copy(
        id = id.trim(),
        name = name.trim(),
        category = category.trim().ifEmpty { "通用" },
        description = description.trim(),
        aliases = aliases.normalizeItems(),
        terms = terms.normalizeItems(),
        corrections = corrections.normalizeItems(),
        style = style.trim(),
    )

    val isUsable: Boolean
        get() = id.isNotBlank() && name.isNotBlank()

    private fun List<String>.normalizeItems(): List<String> =
        map { it.trim() }.filter { it.isNotEmpty() }.distinct()
}

object GlossaryProfileJson {
    fun encode(profile: GlossaryProfile): JSONObject = profile.normalized().let { normalized ->
        JSONObject()
            .put("id", normalized.id)
            .put("name", normalized.name)
            .put("category", normalized.category)
            .put("description", normalized.description)
            .put("aliases", JSONArray(normalized.aliases))
            .put("terms", JSONArray(normalized.terms))
            .put("corrections", JSONArray(normalized.corrections))
            .put("style", normalized.style)
    }

    fun decode(json: JSONObject): GlossaryProfile = GlossaryProfile(
        id = json.optString("id"),
        name = json.optString("name"),
        category = json.optString("category", "通用"),
        description = json.optString("description"),
        aliases = json.optJSONArray("aliases").toStrings(),
        terms = json.optJSONArray("terms").toStrings(),
        corrections = json.optJSONArray("corrections").toStrings(),
        style = json.optString("style"),
    ).normalized()

    private fun JSONArray?.toStrings(): List<String> {
        if (this == null) return emptyList()
        return buildList {
            for (index in 0 until length()) {
                optString(index).trim().takeIf { it.isNotEmpty() }?.let(::add)
            }
        }
    }
}
