package com.tempobox.ui.library

import com.tempobox.model.LibrarySubview
import com.tempobox.model.SortSpec
import com.tempobox.model.defaultSort
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Session-scoped sort choices for the library detail views, one per
 * [LibrarySubview], mirroring how the main tabs keep a per-tab map in
 * [LibraryViewModel]. Tab sorts live in memory for the session (not in
 * DataStore); subview sorts follow the same rule, but need this process-wide
 * holder because detail ViewModels are destroyed on back navigation while the
 * library screen's ViewModel stays on the back stack.
 */
@Singleton
class SubviewSortState @Inject constructor() {

    private val sorts = MutableStateFlow<Map<LibrarySubview, SortSpec>>(emptyMap())

    /** The chosen sort for [view], or the view's default while untouched. */
    fun current(view: LibrarySubview): SortSpec =
        sorts.value[view] ?: view.defaultSort()

    fun sortFlow(view: LibrarySubview): Flow<SortSpec> =
        sorts.map { it[view] ?: view.defaultSort() }.distinctUntilChanged()

    fun set(view: LibrarySubview, spec: SortSpec) {
        sorts.value = sorts.value + (view to spec)
    }
}
