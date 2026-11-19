package com.rescuemesh.ui

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.rescuemesh.RescueMeshApp
import com.rescuemesh.data.local.SavedGuideEntity
import com.rescuemesh.domain.Guide
import com.rescuemesh.domain.PrepareGuides
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

/**
 * Prepare hub state (Prompt 10). No login, no account, no network:
 * the guides are bundled content and Saved lives in Room, so the hub
 * works in airplane mode exactly like SOS does.
 */
class PrepareViewModel(application: Application) : AndroidViewModel(application) {

    private val app = application as RescueMeshApp
    private val savedDao = app.database.savedGuideDao()

    /** Ids the user marked Saved, from Room — survives app restart. */
    val savedIds: StateFlow<Set<String>> = savedDao.observeAll()
        .map { rows -> rows.map { it.guideId }.toSet() }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptySet())

    private val _query = MutableStateFlow("")
    val query: StateFlow<String> = _query.asStateFlow()

    /** Selected hazard chip ("all" = no narrowing), or null when none. */
    private val _hazard = MutableStateFlow<String?>(null)
    val hazard: StateFlow<String?> = _hazard.asStateFlow()

    private val _savedOnly = MutableStateFlow(false)
    val savedOnly: StateFlow<Boolean> = _savedOnly.asStateFlow()

    /** The list as the user currently sees it (pure filter, see PrepareGuides). */
    val guides: StateFlow<List<Guide>> = combine(_query, _hazard, _savedOnly, savedIds) { q, h, so, saved ->
        PrepareGuides.filter(query = q, hazard = h, savedIds = saved, savedOnly = so)
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), PrepareGuides.ALL)

    fun setQuery(value: String) {
        _query.value = value
    }

    /** Tapping the selected chip again clears it. */
    fun setHazard(value: String?) {
        _hazard.value = if (_hazard.value == value) null else value
    }

    fun setSavedOnly(value: Boolean) {
        _savedOnly.value = value
    }

    fun toggleSaved(guideId: String) {
        viewModelScope.launch {
            if (savedIds.value.contains(guideId)) {
                savedDao.deleteById(guideId)
            } else {
                savedDao.upsert(SavedGuideEntity(guideId, System.currentTimeMillis()))
            }
        }
    }
}
