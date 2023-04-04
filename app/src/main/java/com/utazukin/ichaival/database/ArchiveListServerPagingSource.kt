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

package com.utazukin.ichaival.database

import androidx.paging.PagingSource
import androidx.paging.PagingState
import com.utazukin.ichaival.Archive
import com.utazukin.ichaival.ServerManager
import com.utazukin.ichaival.SortMethod
import com.utazukin.ichaival.WebHandler
import kotlinx.coroutines.*
import kotlin.math.min

data class ServerSearchResult(val results: List<String>?,
                         val totalSize: Int = 0,
                         val filter: CharSequence = "",
                         val onlyNew: Boolean = false)

class EmptySource<Key : Any, Value : Any> : PagingSource<Key, Value>() {
    override fun getRefreshKey(state: PagingState<Key, Value>) = null
    override suspend fun load(params: LoadParams<Key>): LoadResult<Key, Value> = LoadResult.Page(emptyList(), null, null)
}

open class ArchiveListServerPagingSource(
    private val isSearch: Boolean,
    private val onlyNew: Boolean,
    private val sortMethod: SortMethod,
    private val descending: Boolean,
    protected val filter: CharSequence,
    protected val database: ArchiveDatabase
) : PagingSource<Int, Archive>() {
    protected val totalResults = mutableListOf<String>()
    var totalSize = -1
        protected set
    private val coroutineContext = Dispatchers.IO + SupervisorJob()

    init {
        registerInvalidatedCallback { coroutineContext.cancel() }
    }

    protected suspend fun getArchives(ids: List<String>?, offset: Int = 0, limit: Int = Int.MAX_VALUE): List<Archive> {
        if (isSearch && ids == null)
            return emptyList()

        return database.getArchives(ids, sortMethod, descending, offset, limit, onlyNew)
    }

    override fun getRefreshKey(state: PagingState<Int, Archive>) = state.anchorPosition

    private suspend fun loadResults(endIndex: Int): Unit = coroutineScope {
        if (totalSize < 0) {
            val results = WebHandler.searchServer(filter, onlyNew, sortMethod, descending, 0, false)
            totalSize = results.totalSize
            results.results?.let { totalResults.addAll(it) }
        }

        val remaining = endIndex - totalResults.size
        val currentSize = totalResults.size
        val pages = remaining.floorDiv(ServerManager.pageSize)
        val jobs = buildList(pages + 1) {
            for (i in 0 until pages) {
                val job = async(coroutineContext) { WebHandler.searchServer(filter, onlyNew, sortMethod, descending, currentSize + i * ServerManager.pageSize, false) }
                add(job)
            }

            val job = async(coroutineContext) { WebHandler.searchServer(filter, onlyNew, sortMethod, descending, currentSize + pages * ServerManager.pageSize, false) }
            add(job)
        }

        totalResults.addAll(jobs.awaitAll().mapNotNull { it.results }.flatten())
    }

    private fun computeNextKey(position: Int, loadSize: Int, total: Int) : Int? {
        val next = position + loadSize
        return if (next >= total) null else next
    }

    override suspend fun load(params: LoadParams<Int>): LoadResult<Int, Archive> {
        val position = if (params is LoadParams.Refresh) 0 else params.key ?: 0
        val prev = if (position > 0) position - 1 else null
        return if (isSearch && filter.isBlank()) //Search mode with no search.  Display none.
            LoadResult.Page(emptyList(), null, null)
        else if (filter.isBlank() && !onlyNew) { //This isn't search mode.  Display all.
            val archiveCount = database.archiveDao().getArchiveCount()
            val archives = getArchives(null, position, params.loadSize)
            val next = computeNextKey(position, params.loadSize, archiveCount)
            LoadResult.Page(archives, prev, next, position, next?.let { archiveCount - it } ?: 0)
        } else {
            val endIndex = if (totalSize >= 0) min(position + params.loadSize, totalSize) else position + params.loadSize
            var next = if (totalSize in 0..endIndex) null else endIndex
            if (endIndex <= totalResults.size) {
                val archives = getArchives(totalResults, position, params.loadSize)
                LoadResult.Page(archives, prev, next, prev?.plus(1) ?: 0, next?.let { totalSize - it } ?: 0)
            } else {
                WebHandler.updateRefreshing(true)

                loadResults(endIndex)
                next = if (totalSize in 0..endIndex) null else endIndex
                val archives = getArchives(totalResults, position, params.loadSize)
                WebHandler.updateRefreshing(false)
                LoadResult.Page(archives, prev, next, prev?.plus(1) ?: 0, next?.let { totalSize - it } ?: 0)
            }
        }
    }
}

class ArchiveListRandomPagingSource(filter: CharSequence, private val count: UInt, private val categoryId: String?, database: ArchiveDatabase)
    : ArchiveListServerPagingSource(false, false, SortMethod.Alpha, false, filter, database) {
    private suspend fun loadResults() {
        val result = WebHandler.getRandomArchives(count, filter, categoryId)
        totalSize = result.totalSize
        result.results?.let { totalResults.addAll(it) }
    }

    override suspend fun load(params: LoadParams<Int>): LoadResult<Int, Archive> {
        val position = if (params is LoadParams.Refresh) 0 else params.key ?: 0
        val prev = if (position > 0) position - 1 else null
        if (totalResults.isEmpty())
            loadResults()

        val endIndex = min(position + params.loadSize, totalSize)
        val next = if (endIndex >= totalSize) null else endIndex
        val archives = if (totalResults.isEmpty()) emptyList() else getArchives(totalResults, position, params.loadSize)
        return LoadResult.Page(archives, prev, next, prev?.plus(1) ?: 0, next?.let { totalSize - it } ?: 0)
    }
}