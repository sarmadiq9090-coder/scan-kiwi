// SPDX-FileCopyrightText: 2026 FastKiwi
// SPDX-License-Identifier: Apache-2.0
package com.fastkiwi.scan.data

import com.fastkiwi.scan.core.Filter
import com.fastkiwi.scan.core.Quad
import org.json.JSONArray
import org.json.JSONObject

/**
 * The user's signature placed on a finished page: centre ([cx], [cy]) and
 * [width], all as fractions of the page; height follows the signature's aspect.
 */
data class Stamp(val cx: Float, val cy: Float, val width: Float) {
    fun toJson(): JSONArray = JSONArray().put(cx.toDouble()).put(cy.toDouble()).put(width.toDouble())

    companion object {
        fun fromJson(a: JSONArray) = Stamp(a.getDouble(0).toFloat(), a.getDouble(1).toFloat(), a.getDouble(2).toFloat())
    }
}

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
    val stamps: List<Stamp> = emptyList(),
) {
    fun toJson(): JSONObject = JSONObject()
        .put("id", id)
        .put("corners", JSONArray().apply { corners.toArray().forEach { put(it.toDouble()) } })
        .put("rotation", rotation)
        .put("filter", filter.name)
        .put("revision", revision)
        .put("stamps", JSONArray().apply { stamps.forEach { put(it.toJson()) } })

    companion object {
        fun fromJson(o: JSONObject): ScanPage {
            val a = o.getJSONArray("corners")
            val c = FloatArray(8) { a.getDouble(it).toFloat() }
            val st = o.optJSONArray("stamps")
            return ScanPage(
                id = o.getString("id"),
                corners = Quad.fromArray(c),
                rotation = o.optInt("rotation", 0),
                filter = runCatching { Filter.valueOf(o.optString("filter")) }.getOrDefault(Filter.ORIGINAL),
                revision = o.optInt("revision", 0),
                stamps = if (st == null) emptyList() else List(st.length()) { Stamp.fromJson(st.getJSONArray(it)) },
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
