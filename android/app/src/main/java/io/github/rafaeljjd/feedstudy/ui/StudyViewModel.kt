package io.github.rafaeljjd.feedstudy.ui

import android.app.Application
import android.net.Uri
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import io.github.rafaeljjd.feedstudy.FeedStudyApp
import io.github.rafaeljjd.feedstudy.data.StudyState
import io.github.rafaeljjd.feedstudy.diagnostics.DiagnosticsState
import io.github.rafaeljjd.feedstudy.study.UserFacingException
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import java.io.IOException

class StudyViewModel(application: Application) : AndroidViewModel(application) {
    val repository = (application as FeedStudyApp).repository
    val diagnostics = DiagnosticsState()

    var busy by mutableStateOf(false)
        private set
    var error by mutableStateOf<String?>(null)
        private set
    var showDiagnostics by mutableStateOf(false)

    val state: StateFlow<StudyState?> =
        repository.stateFlow.stateIn(viewModelScope, SharingStarted.Eagerly, null)

    init {
        viewModelScope.launch { repository.ensureState() }
    }

    fun ratings(round: Int) = repository.ratingsFlow(round)
    fun valuations(round: Int) = repository.valuationsFlow(round)
    fun taskItems() = repository.taskItemsFlow()

    fun clearError() {
        error = null
    }

    fun showError(message: String) {
        error = message
    }

    /** Runs a step, and shows a plain message if it fails so the participant can retry. */
    fun run(block: suspend () -> Unit) {
        viewModelScope.launch {
            busy = true
            error = null
            try {
                block()
            } catch (e: UserFacingException) {
                error = e.message
            } catch (e: IOException) {
                error = "Could not reach the study server. Check your connection and try again."
            } finally {
                busy = false
            }
        }
    }

    fun importFile(round: Int, uri: Uri) = run {
        val input = getApplication<Application>().contentResolver.openInputStream(uri)
            ?: throw UserFacingException("That file could not be opened.")
        input.use { repository.importExport(round, it) }
    }
}
