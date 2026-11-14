package com.fastkiwi.scan.data

import com.fastkiwi.scan.core.Filter
import com.fastkiwi.scan.core.Quad
import org.json.JSONArray
import org.json.JSONObject

/**
 * One scanned page. [corners] are normalised (0..1) in the upright source
 * image; [rotation] is applied after the perspective crop.
 */
data class ScanPage(
    val id: String,
    val corners: Quad,
    val rotation: Int = 0,
    val filter: Filter = Filter.ENHANCED,
    /** Bumped whenever the rendered output changes, to bust image caches. */
    val revision: Int = 0,
) {
    fun toJson(): JSONObject = JSONObject()
        .put("id", id)
        .put("corners", JSONArray().apply { corners.toArray().forEach { put(it.toDouble()) } })
        .put("rotation", rotation)
        .put("filter", filter.name)
        .put("revision", revision)

    companion object {
        fun fromJson(o: JSONObject): ScanPage {
            val a = o.getJSONArray("corners")
            val c = FloatArray(8) { a.getDouble(it).toFloat() }
            return ScanPage(
                id = o.getString("id"),
                corners = Quad.fromArray(c),
                rotation = o.optInt("rotation", 0),
                filter = runCatching { Filter.valueOf(o.optString("filter")) }.getOrDefault(Filter.ORIGINAL),
                revision = o.optInt("revision", 0),
            )
        }
    }
}

data class ScanDocument(
    val id: String,
    val name: String,
    val created: Long,
    val modified: Long,
    val pages: List<ScanPage>,
) {
    fun toJson(): JSONObject = JSONObject()
        .put("version", 1)
        .put("id", id)
        .put("name", name)
        .put("created", created)
        .put("modified", modified)
        .put("pages", JSONArray().apply { pages.forEach { put(it.toJson()) } })

    companion object {
        fun fromJson(o: JSONObject): ScanDocument {
            val arr = o.optJSONArray("pages") ?: JSONArray()
            return ScanDocument(
                id = o.getString("id"),
                name = o.optString("name"),
                created = o.optLong("created"),
                modified = o.optLong("modified"),
                pages = List(arr.length()) { ScanPage.fromJson(arr.getJSONObject(it)) },
            )
        }
    }
}
