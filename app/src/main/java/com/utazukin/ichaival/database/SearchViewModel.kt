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

import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import androidx.paging.*
import com.utazukin.ichaival.*
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch
import kotlin.reflect.KProperty

class ReaderTabViewModel : ViewModel() {
    private val bookmarks = Pager(PagingConfig(5)) { DatabaseReader.database.archiveDao().getDataBookmarks() }.flow.cachedIn(viewModelScope)
    fun monitor(scope: CoroutineScope, action: suspend (PagingData<ReaderTab>) -> Unit) {
        scope.launch { bookmarks.collectLatest(action) }
    }
}

private class StateDelegate<T>(private val key: String,
                               private val state: SavedStateHandle,
                               private val default: T,
                               private val setter: ((T, T) -> Unit)? = null) {
    operator fun getValue(thisRef: Any?, property: KProperty<*>) : T {
        return state.get<T>(key) ?: default
    }

    operator fun setValue(thisRef: Any?, property: KProperty<*>, value: T) {
        val old = state.get<T>(key) ?: default
        state[key] = value
        setter?.invoke(value, old)
    }
}

class SearchViewModel(state: SavedStateHandle) : ViewModel(), CategoryListener {
    var onlyNew by StateDelegate("new", state, false) { new, old ->
        if (old != new)
            reset()
    }
    var isLocal by StateDelegate("local", state, false) { new, old ->
        if (old != new)
            reset()
    }
    var randomCount by StateDelegate("randCount", state, 0) { new, old ->
        if (old != new)
            reset()
    }
    private var initiated by StateDelegate("init", state, false)
    private var resetDisabled = !initiated
        set(value) {
            if (field != value) {
                field = value
                if (!field)
                    reset()
            }
        }

    private var sortMethod by StateDelegate("sort", state, SortMethod.Alpha)
    private var descending by StateDelegate("desc", state, false)
    private var isSearch by StateDelegate("search", state, false)
    private var filter by StateDelegate("filter", state, "")
    private var archivePagingSource: PagingSource<Int, Archive> = EmptySource()
    private val database = DatabaseReader.database
    private val archiveList = Pager(PagingConfig(ServerManager.pageSize, jumpThreshold = ServerManager.pageSize * 3), 0) { getPagingSource() }.flow.cachedIn(viewModelScope)
    private var categoryId by StateDelegate("category", state, "")

    init {
        CategoryManager.addUpdateListener(this)
    }

    private fun getPagingSource() : PagingSource<Int, Archive> {
        archivePagingSource = when {
            !initiated -> EmptySource()
            randomCount > 0 -> ArchiveListRandomPagingSource(filter, randomCount, categoryId, database)
            categoryId.isNotEmpty() -> database.getStaticCategorySource(categoryId, sortMethod, descending, onlyNew)
            isLocal && filter.isNotEmpty() -> ArchiveListLocalPagingSource(filter, sortMethod, descending, onlyNew, database)
            filter.isNotEmpty() -> ArchiveListServerPagingSource(onlyNew, sortMethod, descending, filter, database)
            isSearch -> EmptySource()
            else -> database.getArchiveSource(sortMethod, descending, onlyNew)
        }
        return archivePagingSource
    }

    suspend fun getRandom(excludeBookmarked: Boolean = true): Archive? {
        return if (excludeBookmarked)
            database.archiveDao().getRandomExcludeBookmarked()
        else
            database.archiveDao().getRandom()
    }

    fun deferReset(block: SearchViewModel.() -> Unit) {
        resetDisabled = true
        block()
        resetDisabled = false
    }

    fun updateSort(method: SortMethod, desc: Boolean, force: Boolean = false) {
        if (force || method != sortMethod || desc != descending) {
            sortMethod = method
            descending = desc
            reset()
        }
    }

    fun updateResults(categoryId: String?){
        this.categoryId = categoryId ?: ""
        reset()
    }

    fun filter(search: CharSequence?) {
        filter = search?.toString() ?: ""
        categoryId = ""
        reset()
    }

    fun init(method: SortMethod, desc: Boolean, filter: CharSequence?, onlyNew: Boolean, force: Boolean = false, isSearch: Boolean = false) {
        if (!initiated || force) {
            sortMethod = method
            descending = desc
            this.isSearch = isSearch
            this.onlyNew = onlyNew
            initiated = true
            filter(filter)
            resetDisabled = false
        }
    }

    override fun onCleared() {
        super.onCleared()
        CategoryManager.removeUpdateListener(this)
    }

    fun reset() {
        if (resetDisabled)
            return

        archivePagingSource.let {
            when (it) {
                is ArchiveListPagingSourceBase -> it.reset()
                else -> it.invalidate()
            }
        }
    }

    fun monitor(scope: CoroutineScope, action: suspend (PagingData<Archive>) -> Unit) {
        scope.launch { archiveList.collectLatest(action) }
    }

    override fun onCategoriesUpdated(categories: List<ArchiveCategory>?, firstUpdate: Boolean) {
        if (!firstUpdate && categoryId.isNotEmpty() && categories?.any { it.id  == categoryId } != true) {
            categoryId = ""
            reset()
        }
    }
}