// SPDX-FileCopyrightText: 2026 FastKiwi
// SPDX-License-Identifier: Apache-2.0
package com.fastkiwi.scan.data

import com.fastkiwi.scan.core.Filter
import com.fastkiwi.scan.core.Quad
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Test

/** 2.0 fields in doc.json: favorite, folder and the page quality report, with 1.x files still readable. */
class ModelsV2Test {

    @Test
    fun newFieldsRoundTrip() {
        val q = PageQuality(sharpness = 0.4f, completeness = 0.9f, lighting = 0.7f, glare = 0.03f, skewDegrees = 1.5f, dismissed = true)
        val page = ScanPage("p1", Quad.full(1f, 1f), 90, Filter.BW, 3, emptyList(), q)
        val doc = ScanDocument("d1", "عقد", 1L, 2L, listOf(page), favorite = true, folderId = "f9")
        val back = ScanDocument.fromJson(JSONObject(doc.toJson().toString()))
        assertEquals(doc, back)
    }

    @Test
    fun oneXDocumentLoadsWithDefaults() {
        val json = """{"version":1,"id":"d","name":"n","created":1,"modified":2,"pages":[{"id":"p","corners":[0,0,1,0,1,1,0,1],"rotation":0,"filter":"ENHANCED","revision":0,"stamps":[]}]}"""
        val doc = ScanDocument.fromJson(JSONObject(json))
        assertFalse(doc.favorite)
        assertNull(doc.folderId)
        assertNull(doc.pages.single().quality)
    }
}
