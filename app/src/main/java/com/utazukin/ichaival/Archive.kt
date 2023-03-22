/*
 * Ichaival - Android client for LANraragi https://github.com/Utazukin/Ichaival/
 * Copyright (C) 2023 Utazukin
 *
 *  This program is free software: you can redistribute it and/or modify
 *  it under the terms of the GNU General Public License as published by
 *  the Free Software Foundation, either version 3 of the License, or
 *  (at your option) any later version.
 *
 * This program is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * GNU General Public License for more details.
 *
 * You should have received a copy of the GNU General Public License
 *  along with this program.  If not, see <https://www.gnu.org/licenses/>.
 */

package com.utazukin.ichaival

import android.content.Context
import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.Ignore
import androidx.room.PrimaryKey
import com.google.gson.JsonObject
import com.utazukin.ichaival.database.DatabaseReader
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

@Entity
data class Archive (
    @PrimaryKey val id: String,
    @ColumnInfo val title: String,
    @ColumnInfo val dateAdded: Long,
    @ColumnInfo var isNew: Boolean,
    @ColumnInfo val tags: Map<String, List<String>>,
    @ColumnInfo var currentPage: Int,
    @ColumnInfo var pageCount: Int,
    @ColumnInfo val updatedAt: Long
) {

    val numPages: Int
        get() = if (ServerManager.checkVersionAtLeast(0, 7, 7) && pageCount > 0) pageCount else DatabaseReader.getPageCount(id)

    @delegate:Ignore
    val isWebtoon by lazy { containsTag("webtoon", false) }

    suspend fun extract(context: Context, forceFull: Boolean = false) {
        val pages = DatabaseReader.getPageList(context, id, forceFull)
        if (pageCount <= 0)
            pageCount = pages.size
    }

    fun invalidateCache() {
        DatabaseReader.invalidateImageCache(id)
    }

    fun hasPage(page: Int) : Boolean {
        return numPages <= 0 || (page in 0 until numPages)
    }

    suspend fun clearNewFlag() = withContext(Dispatchers.IO) {
        DatabaseReader.setArchiveNewFlag(id)
        isNew = false
    }

    suspend fun getPageImage(context: Context, page: Int) : String? {
        return downloadPage(context, page)
    }

    suspend fun getThumb(context: Context, page: Int) = WebHandler.downloadThumb(id, page) ?: downloadPage(context, page)

    private suspend fun downloadPage(context: Context, page: Int) : String? {
        val pages = DatabaseReader.getPageList(context.applicationContext, id)
        return if (page < pages.size) WebHandler.getRawImageUrl(pages[page]) else null
    }

    fun containsTag(tag: String, exact: Boolean) : Boolean {
        if (':' in tag) {
            val split = tag.split(":")
            val namespace = split[0]
            val normalized = split[1]
            val nTags = tags[namespace]
            return nTags?.any { if (exact) it.equals(normalized, ignoreCase = true) else it.contains(normalized, ignoreCase = true) } == true
        }
        else {
            for (t in tags.values) {
                if (t.any { it.contains(tag, ignoreCase = true)})
                    return true
            }
        }
        return false
    }
}

data class TitleSortArchive(val id: String, val title: String)

class ArchiveJson(json: JsonObject, val updatedAt: Long) {
    val title: String = json.get("title").asString
    val id: String = json.get("arcid").asString
    val tags: String = json.get("tags").asString
    val pageCount = if (json.has("pagecount")) json.get("pagecount").asInt else 0
    val currentPage = if (json.has("progress")) json.get("progress").asInt - 1 else 0
    val isNew = json.get("isnew").asString.let { it == "block" || it == "true" }
    val dateAdded: Long

    init {
        val timeStampIndex = tags.indexOf("date_added:")
        dateAdded = if (timeStampIndex < 0) 0L
        else {
            val tagStart = tags.indexOf(':', timeStampIndex) + 1
            var tagEnd = tags.indexOf(',', tagStart)
            if (tagEnd < 0)
                tagEnd = tags.length

            val dateTag = tags.substring(tagStart, tagEnd)
            dateTag.toLong()
        }
    }

}