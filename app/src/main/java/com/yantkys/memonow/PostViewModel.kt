package com.yantkys.memonow

import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider.AndroidViewModelFactory.Companion.APPLICATION_KEY
import androidx.lifecycle.createSavedStateHandle
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import com.yantkys.memonow.data.Post
import com.yantkys.memonow.data.PostRepository
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

sealed interface FeedState {
    data object Loading : FeedState
    data class Ready(val posts: List<Post>) : FeedState
    data object Failed : FeedState
}

class PostViewModel(
    private val repository: PostRepository,
    private val savedState: SavedStateHandle,
) : ViewModel() {
    val draft = savedState.getStateFlow("draft", "")
    val editingId = savedState.getStateFlow<String?>("editingId", null)
    val editDraft = savedState.getStateFlow("editDraft", "")
    val deletingId = savedState.getStateFlow<String?>("deletingId", null)
    private val _feed = MutableStateFlow<FeedState>(FeedState.Loading)
    val feed = _feed.asStateFlow()
    private val _busy = MutableStateFlow(false)
    val busy = _busy.asStateFlow()
    private val _error = MutableStateFlow<String?>(null)
    val error = _error.asStateFlow()
    private val _sentId = MutableStateFlow<String?>(null)
    val sentId = _sentId.asStateFlow()
    private var loadJob: Job? = null

    init { retryLoading() }

    fun updateDraft(body: String) { savedState["draft"] = body }
    fun startEditing(post: Post) {
        savedState["editDraft"] = post.body
        savedState["editingId"] = post.id
    }
    fun updateEditDraft(body: String) { savedState["editDraft"] = body }
    fun cancelEditing() { if (!_busy.value) savedState["editingId"] = null }
    fun requestDelete(id: String) { savedState["deletingId"] = id }
    fun cancelDeleting() { if (!_busy.value) savedState["deletingId"] = null }
    fun clearError() { _error.value = null }
    fun consumeSentId(id: String) { if (_sentId.value == id) _sentId.value = null }

    fun retryLoading() {
        loadJob?.cancel()
        loadJob = viewModelScope.launch {
            _feed.value = FeedState.Loading
            try {
                repository.posts.collect { _feed.value = FeedState.Ready(it) }
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (_: Exception) {
                _feed.value = FeedState.Failed
            }
        }
    }

    fun send() {
        val body = draft.value
        if (body.isBlank() || _busy.value) return
        write("保存できませんでした。本文は残っています。もう一度お試しください。") {
            val id = repository.create(body)
            if (draft.value == body) updateDraft("")
            _sentId.value = id
        }
    }

    fun edit() {
        val id = editingId.value ?: return
        val body = editDraft.value
        if (body.isBlank() || _busy.value) return
        write("編集を保存できませんでした。本文を確認して、もう一度お試しください。") {
            repository.edit(id, body)
            savedState["editingId"] = null
        }
    }

    fun delete() {
        val id = deletingId.value ?: return
        if (_busy.value) return
        write("削除できませんでした。もう一度お試しください。") {
            repository.delete(id)
            savedState["deletingId"] = null
        }
    }

    private fun write(message: String, action: suspend () -> Unit) {
        _busy.value = true
        _error.value = null
        viewModelScope.launch {
            try {
                action()
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (_: Exception) {
                _error.value = message
            } finally {
                _busy.value = false
            }
        }
    }

    companion object {
        val Factory = viewModelFactory {
            initializer {
                PostViewModel((this[APPLICATION_KEY] as MemonowApplication).repository, createSavedStateHandle())
            }
        }
    }
}
