package com.yantkys.memonow

import android.app.Application
import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModelStore
import com.yantkys.memonow.data.Post
import com.yantkys.memonow.data.PostDao
import com.yantkys.memonow.data.PostRepository
import java.io.IOException
import java.time.*
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

    @Test fun fourSelectableMoodsAreOptionalToggleableAndClearedOnlyAfterSuccess() = runTest {
        assertNull(model.moodId.value)
        for (mood in com.yantkys.memonow.data.Mood.selectable) {
            model.toggleMood(mood.id)
            assertEquals(mood.id, model.moodId.value)
            model.toggleMood(mood.id)
            assertNull(model.moodId.value)
        }
        for (id in listOf("unknown", "fun", "normal", "None", "none")) {
            model.toggleMood(id)
            assertNull(model.moodId.value)
        }
        model.toggleMood("happy")
        model.toggleMood("tired")
        model.updateDraft("本文と気分")
        dao.failWrites = true
        model.send()
        runCurrent()
        assertEquals("本文と気分", model.draft.value)
        assertEquals("tired", model.moodId.value)
        assertEquals("tired", handle.get<String>("moodId"))
        assertTrue(dao.rows.value.isEmpty())
        dao.failWrites = false
        model.send()
        // A busy write cannot change the selection used for this post.
        model.toggleMood("sad")
        runCurrent()
        assertEquals("tired", dao.rows.value.single().moodId)
        assertEquals("", model.draft.value)
        assertNull(model.moodId.value)
        model.updateDraft("気分なしでも送信")
        model.send()
        runCurrent()
        assertNull(dao.rows.value.last().moodId)
    }

    @Test fun composeAndEditMoodSurviveSavedStateRecreation() = runTest {
        model.updateDraft("未送信")
        model.toggleMood("happy")
        val post = Post(id = "id", body = "過去の投稿", createdAt = 10, moodId = "normal")
        model.startEditing(post)
        model.updateEditDraft("編集途中")
        val recreated = PostViewModel(PostRepository(dao), SavedStateHandle(handle.keys().associateWith { handle.get<Any?>(it) }))
        store.put("recreated", recreated)
        assertEquals("未送信", recreated.draft.value)
        assertEquals("happy", recreated.moodId.value)
        assertEquals("id", recreated.editingId.value)
        assertEquals("編集途中", recreated.editDraft.value)
        assertEquals("normal", recreated.editMoodId.value)
    }

    @Test fun moodOnlyEditingFailureRetryAndRemovalKeepIdentityAndDoNotEnrich() = runTest {
        var enrichCalls = 0
        val editor = PostViewModel(PostRepository(dao), SavedStateHandle(), enrich = { enrichCalls++ })
        store.put("editor", editor)
        editor.updateDraft("元の本文")
        editor.toggleMood("angry")
        editor.send()
        runCurrent()
        val original = dao.rows.value.single()
        assertEquals(1, enrichCalls)
        editor.startEditing(original)
        assertEquals("angry", editor.editMoodId.value)
        editor.toggleEditMood("happy")
        dao.failWrites = true
        editor.edit()
        runCurrent()
        assertEquals(original, dao.rows.value.single())
        assertEquals("happy", editor.editMoodId.value)
        assertEquals(original.id, editor.editingId.value)
        dao.failWrites = false
        editor.edit()
        runCurrent()
        assertEquals(original.copy(moodId = "happy"), dao.rows.value.single())
        editor.startEditing(dao.rows.value.single())
        editor.toggleEditMood("happy")
        editor.edit()
        runCurrent()
        assertEquals(original.copy(moodId = null), dao.rows.value.single())
        assertEquals(1, enrichCalls)
        assertEquals("", editor.draft.value)
        assertNull(editor.moodId.value)
    }

    @Test fun legacyMoodBodyEditsKeepEveryAttributeUntilExplicitReplacementOrRemoval() = runTest {
        for (legacy in listOf("fun", "normal")) {
            val original = Post(sequence = 7, id = "legacy-$legacy", body = "旧投稿", createdAt = 1000,
                placeName = "沖縄県 糸満市", weatherCode = 0, temperatureC = 25.0, weatherAt = 1000, moodId = legacy)
            dao.rows.value = listOf(original)
            model.startEditing(original)
            assertEquals(legacy, model.editMoodId.value)
            // A removed option can't be newly selected, including while editing.
            model.toggleEditMood(if (legacy == "fun") "normal" else "fun")
            assertEquals(legacy, model.editMoodId.value)
            model.updateEditDraft("本文だけ編集")
            dao.failWrites = true
            model.edit()
            runCurrent()
            assertEquals(original, dao.rows.value.single())
            assertEquals(legacy, model.editMoodId.value)
            dao.failWrites = false
            model.edit()
            runCurrent()
            val edited = original.copy(body = "本文だけ編集")
            assertEquals(edited, dao.rows.value.single())
            model.startEditing(edited)
            model.clearEditMood()
            model.edit()
            runCurrent()
            assertEquals(edited.copy(moodId = null), dao.rows.value.single())
            dao.rows.value = listOf(original)
            model.startEditing(original)
            model.toggleEditMood("sad")
            model.edit()
            runCurrent()
            assertEquals(original.copy(moodId = "sad"), dao.rows.value.single())
        }
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

    @Test fun datesRecomputeOnTimeZoneChangeAndSelectionSurvivesStateRecreation() = runTest {
        val a = Post(id="a",body="first",createdAt=Instant.parse("2026-10-29T00:00:00Z").toEpochMilli())
        val b = a.copy(id="b",body="second",createdAt=Instant.parse("2026-10-30T00:00:00Z").toEpochMilli())
        dao.rows.value = listOf(a,b)
        val day = LocalDate.of(2026,10,29)
        val restored = PostViewModel(PostRepository(dao),SavedStateHandle(mapOf("selectedDate" to day.toString())))
        store.put("calendar-restored",restored)
        restored.refreshTimeZone(ZoneOffset.UTC)
        runCurrent()
        assertEquals(day,(restored.feed.value as FeedState.Ready).selectedDate)
        assertEquals(listOf(a),(restored.feed.value as FeedState.Ready).visiblePosts)
        restored.refreshTimeZone(ZoneId.of("Pacific/Honolulu"))
        val changed = restored.feed.value as FeedState.Ready
        assertEquals(day,changed.selectedDate)
        assertEquals(listOf(b),changed.visiblePosts)
        assertEquals(mapOf(day.minusDays(1) to 1,day to 1),changed.dateCounts)
        restored.refreshTimeZone(ZoneOffset.UTC)
        assertTrue(restored.selectDate(day.plusDays(1)))
        restored.refreshTimeZone(ZoneId.of("Pacific/Honolulu"))
        assertNull((restored.feed.value as FeedState.Ready).selectedDate)
        assertEquals(listOf(a,b),(restored.feed.value as FeedState.Ready).visiblePosts)
    }

    @Test fun dayFilteringEditsAndLastDeletionUpdateCountsWithoutReaggregatingMetadataEdits() = runTest {
        val first = Post(sequence=1,id="a",body="first",createdAt=0,moodId="fun",placeName="沖縄")
        val second = first.copy(sequence=2,id="b",body="second",moodId="normal")
        val next = first.copy(sequence=3,id="c",createdAt=86_400_000L)
        dao.rows.value = listOf(first,second,next)
        model.refreshTimeZone(ZoneOffset.UTC)
        runCurrent()
        val day = LocalDate.of(1970,1,1)
        assertTrue(model.selectDate(day))
        assertFalse(model.selectDate(day.minusDays(1)))
        val counts = (model.feed.value as FeedState.Ready).dateCounts
        model.startEditing(first)
        model.updateEditDraft("編集した本文")
        model.edit()
        runCurrent()
        val edited = model.feed.value as FeedState.Ready
        assertSame(counts,edited.dateCounts)
        assertEquals(listOf(first.copy(body="編集した本文"),second),edited.visiblePosts)
        assertEquals(day,edited.selectedDate)
        model.requestDelete("a"); model.delete(); runCurrent()
        assertEquals(1,(model.feed.value as FeedState.Ready).dateCounts[day])
        model.requestDelete("b"); model.delete(); runCurrent()
        val all = model.feed.value as FeedState.Ready
        assertNull(all.selectedDate)
        assertEquals(listOf(next),all.visiblePosts)
        assertNull(handle.get<String>("selectedDate"))
    }

    @Test fun filteredSendKeepsDateAndDraftOnFailureAndRevealsCurrentTimePostOnSuccess() = runTest {
        val now = Instant.parse("2026-10-29T10:00:00Z")
        val old = Post(id="old",body="過去の記録",createdAt=0)
        dao.rows.value = listOf(old)
        val saved = SavedStateHandle()
        val sender = PostViewModel(PostRepository(dao,Clock.fixed(now,ZoneOffset.UTC)),saved)
        store.put("calendar-sender",sender)
        sender.refreshTimeZone(ZoneOffset.UTC)
        runCurrent()
        assertTrue(sender.selectDate(LocalDate.of(1970,1,1)))
        sender.updateDraft("いまの記録"); sender.toggleMood("happy")
        dao.failWrites = true
        sender.send(); runCurrent()
        assertEquals(LocalDate.of(1970,1,1),(sender.feed.value as FeedState.Ready).selectedDate)
        assertEquals("いまの記録",sender.draft.value)
        assertEquals("happy",sender.moodId.value)
        dao.failWrites = false
        sender.send(); runCurrent()
        val ready = sender.feed.value as FeedState.Ready
        assertNull(ready.selectedDate)
        assertEquals(2,ready.visiblePosts.size)
        val created = ready.posts.last()
        assertEquals(now.toEpochMilli(),created.createdAt)
        assertEquals(created.id,sender.sentId.value)
        assertEquals("happy",created.moodId)
        assertNull(saved.get<String>("selectedDate"))
        assertTrue(sender.selectDate(LocalDate.of(1970,1,1)))
        assertNull(sender.sentId.value) // Browsing cancels any unfinished follow-new-post request.
        sender.showAllDates()
        assertNull((sender.feed.value as FeedState.Ready).selectedDate)
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
        override suspend fun updateContent(id: String, body: String, moodId: String?): Int {
            if (failWrites) throw IOException("disk full")
            val count = rows.value.count { it.id == id }
            rows.value = rows.value.map { if (it.id == id) it.copy(body = body, moodId = moodId) else it }
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
