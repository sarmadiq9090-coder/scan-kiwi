// SPDX-FileCopyrightText: 2026 FastKiwi
// SPDX-License-Identifier: Apache-2.0
package com.fastkiwi.scan.data

import org.json.JSONArray
import org.json.JSONObject
import java.io.File

/** A folder on Home (A3, 2.0). Folders are one level deep; documents point to them by [ScanDocument.folderId]. */
data class Folder(val id: String, val name: String, val created: Long) {
    fun toJson(): JSONObject = JSONObject().put("id", id).put("name", name).put("created", created)

    companion object {
        fun fromJson(o: JSONObject) = Folder(o.getString("id"), o.optString("name"), o.optLong("created"))
    }
}

/**
 * The folder list, in `filesDir/folders.json` (atomic writes, like doc.json). Data from 1.x has
 * no file and no `folderId` on any document: everything is at the top level, nothing to convert.
 * A document whose folder no longer exists (or never did) is shown at the top level too, so a
 * crash between steps can never hide a document.
 *
 * Deleting a folder first moves its documents to the top level and only then removes the folder:
 * documents are never deleted with their folder.
 */
class FolderStore(root: File) {
    private val file = File(root, "folders.json")

    @Synchronized
    fun list(): List<Folder> {
        if (!file.exists()) return emptyList()
        return runCatching {
            val a = JSONObject(file.readText()).optJSONArray("folders") ?: JSONArray()
            List(a.length()) { Folder.fromJson(a.getJSONObject(it)) }.filter { it.id.isNotEmpty() }
        }.getOrDefault(emptyList())
    }

    @Synchronized
    private fun write(folders: List<Folder>) {
        val o = JSONObject().put("version", 1).put("folders", JSONArray().apply { folders.forEach { put(it.toJson()) } })
        writeAtomically(file) { it.write(o.toString().toByteArray(Charsets.UTF_8)) }
    }

    @Synchronized
    fun create(name: String): Folder {
        val f = Folder(DocumentStore.newId(), name.trim(), System.currentTimeMillis())
        write(list() + f)
        return f
    }

    @Synchronized
    fun rename(id: String, name: String) {
        val n = name.trim()
        if (n.isEmpty()) return
        write(list().map { if (it.id == id) it.copy(name = n) else it })
    }

    /**
     * Deletes folder [id]: its documents move to the top level first (each doc.json saved
     * atomically, modified time kept), then the folder entry goes. Returns the documents moved.
     */
    @Synchronized
    fun delete(id: String, store: DocumentStore): Int {
        var moved = 0
        for (d in store.list()) if (d.folderId == id) { store.save(d.copy(folderId = null), touch = false); moved++ }
        write(list().filter { it.id != id })
        return moved
    }

    companion object {
        /** The folder a document is shown in: its own if that folder exists, else the top level (null). */
        fun effectiveFolder(doc: ScanDocument, folderIds: Set<String>): String? = doc.folderId?.takeIf { it in folderIds }
    }
}
