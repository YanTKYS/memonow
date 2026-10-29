package com.yantkys.memonow

import android.net.Uri
import com.yantkys.memonow.backup.*
import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider.AndroidViewModelFactory.Companion.APPLICATION_KEY
import androidx.lifecycle.createSavedStateHandle
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import com.yantkys.memonow.data.Post
import com.yantkys.memonow.data.Mood
import com.yantkys.memonow.data.PostRepository
import java.time.LocalDate
import java.time.ZoneId
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

sealed interface FeedState {
    data object Loading : FeedState
    data class Ready(
        val posts: List<Post>,
        val selectedDate: LocalDate? = null,
        val dateCounts: Map<LocalDate, Int> = emptyMap(),
        val visiblePosts: List<Post> = posts,
        val zone: ZoneId = ZoneId.systemDefault(),
    ) : FeedState
    data object Failed : FeedState
}

class PostViewModel(
    private val repository: PostRepository,
    private val savedState: SavedStateHandle,
    private val canEnrich: () -> Boolean = { true },
    private val backupStore: BackupStore? = null,
    private val backupFiles: BackupFiles? = null,
    private val pauseMetadata: (Boolean) -> Unit = {},
    private val onMetadataCancelled: () -> Unit = {},
    private val enrich: suspend (String) -> Unit = {},
) : ViewModel() {
    val draft = savedState.getStateFlow("draft", "")
    val moodId = savedState.getStateFlow<String?>("moodId", null)
    val editingId = savedState.getStateFlow<String?>("editingId", null)
    val editDraft = savedState.getStateFlow("editDraft", "")
    val editMoodId = savedState.getStateFlow<String?>("editMoodId", null)
    val deletingId = savedState.getStateFlow<String?>("deletingId", null)
    private var zone = ZoneId.systemDefault()
    private var days: PostDays? = null
    private val _feed = MutableStateFlow<FeedState>(FeedState.Loading)
    val feed = _feed.asStateFlow()
    private val _busy = MutableStateFlow(false)
    val busy = _busy.asStateFlow()
    private val _backupState = MutableStateFlow<BackupState>(when (savedState.get<String>("backupStage")) {
        "export-picker" -> BackupState.Picking(true)
        "import-picker" -> BackupState.Picking(false)
        null -> BackupState.Idle
        else -> BackupState.Finished("前回の処理は中断されました。ファイルを選び直して確認してください。", false)
    })
    val backupState = _backupState.asStateFlow()
    private fun blocked() = _busy.value || _backupState.value != BackupState.Idle
    private val _error = MutableStateFlow<String?>(null)
    val error = _error.asStateFlow()
    private val _sentId = MutableStateFlow<String?>(null)
    val sentId = _sentId.asStateFlow()
    private var loadJob: Job? = null
    private val metadataJobs = mutableSetOf<Job>()

    fun cancelMetadata() {
        onMetadataCancelled()
        metadataJobs.toList().forEach { it.cancel() }
        metadataJobs.clear()
    }

    init {
        if (_backupState.value == BackupState.Picking(false)) pauseMetadata(true)
        retryLoading()
    }

    fun updateDraft(body: String) { savedState["draft"] = body }
    fun toggleMood(id: String) {
        if (!blocked() && Mood.selectable.any { it.id == id }) savedState["moodId"] = if (moodId.value == id) null else id
    }
    fun clearMood() { if (!blocked()) savedState["moodId"] = null }
    fun startEditing(post: Post) {
        if (blocked()) return
        savedState["editDraft"] = post.body
        savedState["editMoodId"] = post.moodId
        savedState["editingId"] = post.id
    }
    fun updateEditDraft(body: String) { savedState["editDraft"] = body }
    fun toggleEditMood(id: String) {
        if (!blocked() && Mood.selectable.any { it.id == id }) savedState["editMoodId"] = if (editMoodId.value == id) null else id
    }
    fun clearEditMood() { if (!blocked()) savedState["editMoodId"] = null }
    fun cancelEditing() { if (!_busy.value) savedState["editingId"] = null }
    fun requestDelete(id: String) { if (!blocked()) savedState["deletingId"] = id }
    fun cancelDeleting() { if (!_busy.value) savedState["deletingId"] = null }
    fun clearError() { _error.value = null }
    fun consumeSentId(id: String) { if (_sentId.value == id) _sentId.value = null }

    fun selectDate(date: LocalDate): Boolean {
        val ready = _feed.value as? FeedState.Ready ?: return false
        if (blocked() || ready.dateCounts.getOrDefault(date, 0) == 0) return false
        _sentId.value = null // Explicit browsing cancels an unfinished follow-new-post request.
        savedState["selectedDate"] = date.toString()
        publish(ready.posts)
        return true
    }

    fun showAllDates() {
        if (blocked()) return
        clearDateFilter()
    }

    fun refreshTimeZone(newZone: ZoneId = ZoneId.systemDefault()) {
        if (zone == newZone) return
        zone = newZone
        (_feed.value as? FeedState.Ready)?.let { publish(it.posts) }
    }

    private fun clearDateFilter() {
        savedState["selectedDate"] = null
        (_feed.value as? FeedState.Ready)?.let { publish(it.posts) }
    }

    private fun publish(posts: List<Post>) {
        val index = days?.takeIf { it.matches(posts, zone) } ?: PostDays(posts, zone).also { days = it }
        val selected = savedState.get<String>("selectedDate")?.let { runCatching { LocalDate.parse(it) }.getOrNull() }
            ?.takeIf { index.counts.getOrDefault(it, 0) > 0 }
        savedState["selectedDate"] = selected?.toString()
        _feed.value = FeedState.Ready(posts, selected, index.counts, index.visible(posts, selected), zone)
    }

    fun retryLoading() {
        loadJob?.cancel()
        loadJob = viewModelScope.launch {
            _feed.value = FeedState.Loading
            try {
                repository.posts.collect { publish(it) }
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (_: Exception) {
                _feed.value = FeedState.Failed
            }
        }
    }

    fun send() {
        val body = draft.value
        val mood = moodId.value
        if (body.isBlank() || blocked()) return
        val includeMetadata = canEnrich()
        write("保存できませんでした。本文と気分は残っています。もう一度お試しください。") {
            val id = repository.create(body, mood)
            clearDateFilter() // Always save at the repository's current time, then reveal the new post.
            if (draft.value == body && moodId.value == mood) {
                updateDraft("")
                savedState["moodId"] = null
            }
            _sentId.value = id
            if (!includeMetadata) return@write
            // Launch a sibling job; successful body persistence never waits for metadata.
            val job = viewModelScope.launch {
                try { enrich(id) } catch (cancelled: CancellationException) {
                    throw cancelled
                } catch (_: Exception) { /* Missing metadata is a valid post. */ }
            }
            metadataJobs.add(job)
            job.invokeOnCompletion { metadataJobs.remove(job) }
        }
    }

    fun edit() {
        val id = editingId.value ?: return
        val body = editDraft.value
        val mood = editMoodId.value
        if (body.isBlank() || blocked()) return
        write("編集を保存できませんでした。本文を確認して、もう一度お試しください。") {
            repository.edit(id, body, mood)
            savedState["editingId"] = null
        }
    }

    fun delete() {
        val id = deletingId.value ?: return
        if (blocked()) return
        write("削除できませんでした。もう一度お試しください。") {
            repository.delete(id)
            savedState["deletingId"] = null
        }
    }

    fun beginBackup(export: Boolean): Boolean {
        if (blocked()) return false
        _backupState.value = BackupState.Picking(export)
        savedState["backupStage"] = if (export) "export-picker" else "import-picker"
        if (!export) { pauseMetadata(true); cancelMetadata() }
        return true
    }

    fun exportTo(uri: Uri?) {
        if (_backupState.value != BackupState.Picking(true)) return
        if (uri == null) { finishBackup("保存をキャンセルしました。", false); return }
        backupOperation("JSONを保存しています…", "export-write") {
            val (count, bytes) = checkNotNull(backupStore).export()
            checkNotNull(backupFiles).write(uri, bytes)
            finishBackup("${count}件をJSONに保存しました。", true)
        }
    }

    fun importFrom(uri: Uri?) {
        if (_backupState.value != BackupState.Picking(false)) return
        if (uri == null) { finishBackup("復元をキャンセルしました。記録は変更していません。", false); return }
        backupOperation("JSON全体を読み取り・検証しています…", "import-read") {
            val document = checkNotNull(backupFiles).read(uri)
            val plan = checkNotNull(backupStore).prepare(document)
            savedState["backupStage"] = "confirm"
            _backupState.value = BackupState.Confirm(plan)
        }
    }

    fun confirmRestore() {
        val plan = (_backupState.value as? BackupState.Confirm)?.plan ?: return
        cancelMetadata()
        backupOperation("記録を置き換えています…", "replace") {
            checkNotNull(backupStore).restore(plan)
            clearDateFilter() // A full replacement returns to the complete restored timeline.
            savedState["editingId"] = null
            savedState["deletingId"] = null
            retryLoading()
            _sentId.value = plan.document.posts.maxWithOrNull(compareBy<com.yantkys.memonow.data.Post> { it.createdAt }.thenBy { it.sequence })?.id
            finishBackup("${plan.document.posts.size}件を復元しました。", true)
        }
    }

    fun dismissBackup() {
        if (_backupState.value is BackupState.Processing) return
        savedState["backupStage"] = null
        _backupState.value = BackupState.Idle
        pauseMetadata(false)
    }

    private fun finishBackup(message: String, success: Boolean) {
        savedState["backupStage"] = null
        pauseMetadata(false)
        _backupState.value = BackupState.Finished(message, success)
    }

    private fun backupOperation(label: String, stage: String, action: suspend () -> Unit) {
        _backupState.value = BackupState.Processing(label)
        savedState["backupStage"] = stage
        viewModelScope.launch {
            try {
                action()
            } catch (cancelled: CancellationException) {
                pauseMetadata(false)
                throw cancelled
            } catch (invalid: InvalidBackup) {
                finishBackup(invalid.message ?: "バックアップが正しくありません。", false)
            } catch (_: Exception) {
                finishBackup("処理に失敗しました。保存先・空き容量・ファイル内容を確認してください。失敗した保存ファイルは使用しないでください。", false)
            }
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

    override fun onCleared() {
        pauseMetadata(false)
        super.onCleared()
    }

    companion object {
        val Factory = viewModelFactory {
            initializer {
                val app = this[APPLICATION_KEY] as MemonowApplication
                PostViewModel(app.repository, createSavedStateHandle(), canEnrich = app::metadataAllowed,
                    backupStore = app.backupStore, backupFiles = AndroidBackupFiles(app.contentResolver),
                    pauseMetadata = { app.restoring = it }, enrich = app.postMetadata::enrich,
                    onMetadataCancelled = { app.postMetadata.interrupt(app.metadataBlockedReason()) })
            }
        }
    }
}
