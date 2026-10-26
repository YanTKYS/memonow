package com.yantkys.memonow

import android.app.Application
import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModelStore
import com.yantkys.memonow.data.Post
import com.yantkys.memonow.data.PostDao
import com.yantkys.memonow.data.PostRepository
import java.io.IOException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28], application = Application::class)
class PostViewModelTest {
    private val store = ViewModelStore()
    private lateinit var dao: TestDao
    private lateinit var handle: SavedStateHandle
    private lateinit var model: PostViewModel

    @Before fun setUp() {
        Dispatchers.setMain(StandardTestDispatcher())
        dao = TestDao()
        handle = SavedStateHandle()
        model = PostViewModel(PostRepository(dao), handle)
        store.put("posts", model)
    }
    @After fun tearDown() {
        store.clear()
        Dispatchers.resetMain()
    }

    @Test fun sendClearsDraftOnlyAfterSuccessfulWriteAndPreventsDoubleSend() = runTest {
        model.updateDraft("いまの記録")
        model.send()
        model.send()
        assertEquals("いまの記録", model.draft.value)
        assertTrue(model.busy.value)
        runCurrent()
        assertEquals(1, dao.rows.value.size)
        assertEquals("", model.draft.value)
        assertEquals(dao.rows.value.single().id, model.sentId.value)
        assertFalse(model.busy.value)
        assertTrue(model.feed.value is FeedState.Ready)
    }

    @Test fun failedSaveRetainsDraftAndCanBeRetried() = runTest {
        dao.failWrites = true
        model.updateDraft("失いたくない本文")
        model.send()
        runCurrent()
        assertEquals("失いたくない本文", model.draft.value)
        assertEquals("失いたくない本文", handle.get<String>("draft"))
        assertNotNull(model.error.value)
        assertNull(model.sentId.value)
        assertFalse(model.busy.value)
        dao.failWrites = false
        model.send()
        runCurrent()
        assertEquals("", model.draft.value)
        assertNull(model.error.value)
        assertEquals(1, dao.rows.value.size)
    }

    @Test fun whitespaceOnlyCannotBeSent() = runTest {
        model.updateDraft("　 \n\t")
        model.send()
        runCurrent()
        assertTrue(dao.rows.value.isEmpty())
    }

    @Test fun editFailureKeepsDialogDraftAndSuccessfulRetryKeepsCreatedTime() = runTest {
        model.updateDraft("初稿")
        model.send()
        runCurrent()
        val post = dao.rows.value.single()
        model.startEditing(post)
        model.updateEditDraft("編集した本文")
        dao.failWrites = true
        model.edit()
        runCurrent()
        assertEquals(post.id, model.editingId.value)
        assertEquals("編集した本文", model.editDraft.value)
        assertEquals(post, dao.rows.value.single())
        dao.failWrites = false
        model.edit()
        runCurrent()
        assertNull(model.editingId.value)
        assertEquals(post.copy(body = "編集した本文"), dao.rows.value.single())
    }

    @Test fun deletionRequiresConfirmationAndFailedDeleteCanBeRetried() = runTest {
        model.updateDraft("残す")
        model.send()
        runCurrent()
        val post = dao.rows.value.single()
        model.requestDelete(post.id)
        runCurrent()
        assertEquals(1, dao.rows.value.size)
        model.cancelDeleting()
        assertNull(model.deletingId.value)
        assertEquals(1, dao.rows.value.size)
        model.requestDelete(post.id)
        dao.failWrites = true
        model.delete()
        runCurrent()
        assertEquals(post.id, model.deletingId.value)
        assertEquals(1, dao.rows.value.size)
        dao.failWrites = false
        model.delete()
        runCurrent()
        assertNull(model.deletingId.value)
        assertTrue(dao.rows.value.isEmpty())
    }

    @Test fun savedStateRestoresComposeAndEditDrafts() = runTest {
        val restored = PostViewModel(PostRepository(dao), SavedStateHandle(mapOf(
            "draft" to "書きかけ", "editingId" to "uuid", "editDraft" to "編集中",
        )))
        store.put("restored", restored)
        assertEquals("書きかけ", restored.draft.value)
        assertEquals("uuid", restored.editingId.value)
        assertEquals("編集中", restored.editDraft.value)
    }

    @Test fun loadingErrorIsVisibleAndRetryCollectsAgain() = runTest {
        runCurrent()
        dao.failReads = true
        model.retryLoading()
        runCurrent()
        assertEquals(FeedState.Failed, model.feed.value)
        dao.failReads = false
        model.retryLoading()
        runCurrent()
        assertEquals(FeedState.Ready(emptyList()), model.feed.value)
    }

    @Test fun slowMetadataDoesNotBlockSavingOrTheNextPost() = runTest {
        val waiting = kotlinx.coroutines.CompletableDeferred<Unit>()
        val started = kotlinx.coroutines.CompletableDeferred<String>()
        val fast = PostViewModel(PostRepository(dao), SavedStateHandle()) { id ->
            started.complete(id)
            waiting.await()
        }
        store.put("metadata", fast)
        fast.updateDraft("即座に保存")
        fast.send()
        runCurrent()
        assertTrue(started.isCompleted)
        assertEquals("", fast.draft.value)
        assertFalse(fast.busy.value)
        fast.updateDraft("次の投稿も保存")
        fast.send()
        runCurrent()
        assertEquals(2, dao.rows.value.size)
        fast.cancelMetadata()
        runCurrent()
        assertNull(fast.error.value)
    }

    @Test fun failedBodySaveNeverStartsMetadata() = runTest {
        var calls = 0
        val fast = PostViewModel(PostRepository(dao), SavedStateHandle()) { calls++ }
        store.put("metadata", fast)
        dao.failWrites = true
        fast.updateDraft("本文")
        fast.send()
        runCurrent()
        assertEquals(0, calls)
        assertEquals("本文", fast.draft.value)
    }

    @Test fun offAtSubmissionDoesNotEnrichEvenIfEnabledBeforeSaveCompletes() = runTest {
        var enabled = false
        var calls = 0
        val fast = PostViewModel(PostRepository(dao), SavedStateHandle(), { enabled }) { calls++ }
        store.put("metadata", fast)
        fast.updateDraft("OFF時の記録")
        fast.send()
        enabled = true
        runCurrent()
        assertEquals(0, calls)
        assertEquals(1, dao.rows.value.size)
    }

    @Test fun oversizedInputStaysInDraftAndNeverBecomesAnUnbackuppablePost() = runTest {
        val body = "x".repeat(100_001)
        model.updateDraft(body)
        model.send()
        runCurrent()
        assertEquals(body, model.draft.value)
        assertTrue(dao.rows.value.isEmpty())
        assertNotNull(model.error.value)
    }

    private class TestDao : PostDao {
        val rows = MutableStateFlow<List<Post>>(emptyList())
        var failWrites = false
        var failReads = false
        override fun observeAll() = flow {
            if (failReads) throw IOException("read failure")
            rows.collect { emit(it) }
        }
        override suspend fun textLength() = rows.value.sumOf { it.body.length.toLong() + (it.placeName?.length ?: 0) }
        override suspend fun count() = rows.value.size
        override suspend fun snapshot() = rows.value
        override suspend fun deleteAll() { rows.value = emptyList() }
        override suspend fun insertAll(posts: List<Post>) { rows.value += posts }
        override suspend fun find(id: String) = rows.value.firstOrNull { it.id == id }
        override suspend fun attachPlace(id: String, name: String): Int = error("unused")
        override suspend fun attachWeather(id: String, code: Int?, temperature: Double?, at: Long): Int = error("unused")
        override suspend fun insert(post: Post) {
            if (failWrites) throw IOException("disk full")
            rows.value += post
        }
        override suspend fun updateBody(id: String, body: String): Int {
            if (failWrites) throw IOException("disk full")
            val count = rows.value.count { it.id == id }
            rows.value = rows.value.map { if (it.id == id) it.copy(body = body) else it }
            return count
        }
        override suspend fun delete(id: String): Int {
            if (failWrites) throw IOException("disk full")
            val count = rows.value.count { it.id == id }
            rows.value = rows.value.filterNot { it.id == id }
            return count
        }
    }
}
