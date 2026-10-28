package com.yantkys.memonow

import android.app.Application
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.yantkys.memonow.data.MemonowDatabase
import com.yantkys.memonow.data.Post
import com.yantkys.memonow.data.PostRepository
import java.time.Clock
import java.time.Instant
import java.time.ZoneOffset
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.launch
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.runTest
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
class PostRepositoryTest {
    private val context get() = ApplicationProvider.getApplicationContext<Application>()
    private lateinit var database: MemonowDatabase
    private lateinit var repository: PostRepository
    private val instant = Instant.parse("2026-10-25T12:34:56Z")

    @Before fun setUp() {
        database = Room.inMemoryDatabaseBuilder(context, MemonowDatabase::class.java).build()
        repository = PostRepository(database.posts(), Clock.fixed(instant, ZoneOffset.UTC))
    }
    @After fun tearDown() { database.close() }

    @Test fun createReadEditAndDeleteKeepIdentityAndCreationTime() = runTest {
        val id = repository.create("  今日の記録\n日本語の二行目  ")
        val created = repository.posts.first().single()
        assertEquals(id, created.id)
        assertEquals("今日の記録\n日本語の二行目", created.body)
        assertEquals(instant.toEpochMilli(), created.createdAt)
        repository.edit(id, "編集した記録")
        assertEquals(created.copy(body = "編集した記録"), repository.posts.first().single())
        repository.delete(id)
        assertTrue(repository.posts.first().isEmpty())
    }

    @Test fun moodEditsAndConcurrentMetadataPreserveEachOthersFieldsAndDeletedRowsStayDeleted() = runTest {
        val id = repository.create("本文", "happy")
        val original = repository.find(id)!!
        val token = repository.metadataToken()
        kotlinx.coroutines.coroutineScope {
            launch { repository.edit(id, "編集後", "sad") }
            launch { repository.attachPlace(id, "沖縄県 糸満市", token) }
            launch { repository.attachWeather(id, 3, 25.5, original.createdAt, token) }
        }
        val expected = original.copy(body = "編集後", moodId = "sad", placeName = "沖縄県 糸満市", weatherCode = 3, temperatureC = 25.5, weatherAt = original.createdAt)
        assertEquals(expected, repository.find(id))
        repository.edit(id, "本文のみ")
        assertEquals(expected.copy(body = "本文のみ"), repository.find(id))
        repository.edit(id, "本文のみ", null)
        assertEquals(expected.copy(body = "本文のみ", moodId = null), repository.find(id))
        repository.delete(id)
        assertEquals(0, repository.attachPlace(id, "遅延応答", token))
        assertEquals(0, repository.attachWeather(id, 0, 20.0, original.createdAt, token))
        try { repository.edit(id, "復活させない", "fun"); fail("deleted post edited") } catch (_: IllegalStateException) { }
        assertNull(repository.find(id))
    }

    @Test fun unknownMoodIsNotInventedOrReinterpreted() = runTest {
        assertNull(com.yantkys.memonow.data.Mood.find("HAPPY"))
        assertNull(com.yantkys.memonow.data.Mood.find("unknown"))
        try { repository.create("本文", "unknown"); fail("unknown create") } catch (_: IllegalArgumentException) { }
        val id = repository.create("本文", "normal")
        try { repository.edit(id, "変更", "unknown"); fail("unknown edit") } catch (_: IllegalArgumentException) { }
        assertEquals("normal", repository.find(id)!!.moodId)
        // An already stored unknown value stays distinct until explicitly replaced/cleared.
        database.posts().updateContent(id, "未知の既存記録", "future-id")
        repository.edit(id, "本文だけ編集", "future-id")
        assertEquals("future-id", repository.find(id)!!.moodId)
        repository.edit(id, "本文だけ編集", null)
        assertNull(repository.find(id)!!.moodId)
    }

    @Test fun moodOnlyEditAndRemovalKeepImportedBodyByteForByte() = runTest {
        val post = Post(id = java.util.UUID.randomUUID().toString(), body = "  JSONの原文\n末尾も保持　", createdAt = 1000,
            placeName = "沖縄県 糸満市", weatherCode = 0, temperatureC = 0.0, weatherAt = 1000)
        database.posts().insert(post)
        val original = repository.find(post.id)!!
        repository.edit(post.id, post.body, "tired")
        assertEquals(original.copy(moodId = "tired"), repository.find(post.id))
        repository.edit(post.id, post.body, null)
        assertEquals(original, repository.find(post.id))
    }

    @Test fun hundredsOfSameTimestampPostsKeepInsertionOrderAndUniqueIds() = runTest {
        val ids = (1..400).map { repository.create("記録 $it") }
        assertEquals(400, ids.toSet().size)
        assertEquals(ids, repository.posts.first().map { it.id })
        repository.edit(ids[100], "本文だけ変更")
        repository.delete(ids[200])
        assertEquals(ids.filterNot { it == ids[200] }, repository.posts.first().map { it.id })
    }

    @Test fun activeObserverReceivesCreateEditAndDelete() = runTest {
        val updates = Channel<List<Post>>(Channel.UNLIMITED)
        backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) {
            repository.posts.collect { updates.send(it) }
        }
        assertTrue(updates.receive().isEmpty())
        val id = repository.create("新着")
        assertEquals(id, updates.receive().single().id)
        repository.edit(id, "更新")
        assertEquals("更新", updates.receive().single().body)
        repository.delete(id)
        assertTrue(updates.receive().isEmpty())
    }

    @Test fun bodyLimitAppliesToCreationAndEditingWithoutTruncation() = runTest {
        val body = "🌤".repeat(50_000)
        val id = repository.create(body)
        assertEquals(body, repository.find(id)!!.body)
        try { repository.create(body + "x"); fail("oversized create accepted") } catch (_: IllegalArgumentException) { }
        try { repository.edit(id, body + "x"); fail("oversized edit accepted") } catch (_: IllegalArgumentException) { }
        assertEquals(body, repository.find(id)!!.body)
        assertEquals(1, database.posts().count())
    }

    @Test fun invalidUnicodeCannotCreateARecordWhichJsonCannotEncode() = runTest {
        try { repository.create("bad\uD800"); fail("unpaired surrogate accepted") } catch (_: IllegalArgumentException) { }
        assertTrue(repository.posts.first().isEmpty())
    }

    @Test fun chronologyUsesEpochTimeBeforeLocalSequence() = runTest {
        database.posts().insert(Post(id = "newer", body = "翌日", createdAt = 2000))
        database.posts().insert(Post(id = "older", body = "前日", createdAt = 1000))
        assertEquals(listOf("older", "newer"), repository.posts.first().map { it.id })
    }

    @Test fun blankCreateAndEditAreRejectedWithoutChangingDatabase() = runTest {
        val id = repository.create("残す記録")
        for (blank in listOf("", " \n\t", "　")) {
            try { repository.create(blank); fail("blank accepted") } catch (_: IllegalArgumentException) { }
            try { repository.edit(id, blank); fail("blank edit accepted") } catch (_: IllegalArgumentException) { }
        }
        assertEquals("残す記録", repository.posts.first().single().body)
    }

    @Test fun editingMissingIdFailsInsteadOfSilentlyLosingDraft() = runTest {
        try { repository.edit("missing", "本文"); fail("missing record accepted") } catch (_: IllegalStateException) { }
    }

    @Test fun recordsSurviveClosingAndReopeningFileDatabase() = runTest {
        val name = "persistence-test.db"
        context.deleteDatabase(name)
        fun open() = Room.databaseBuilder(context, MemonowDatabase::class.java, name).build()
        var persistent = open()
        try {
            val first = PostRepository(persistent.posts(), Clock.fixed(instant, ZoneOffset.UTC))
            val id = first.create("再起動後も残る")
            first.edit(id, "保存された編集")
            val deleted = first.create("削除される")
            first.delete(deleted)
            persistent.close()
            persistent = open()
            val restored = PostRepository(persistent.posts()).posts.first().single()
            assertEquals(id, restored.id)
            assertEquals("保存された編集", restored.body)
            assertEquals(instant.toEpochMilli(), restored.createdAt)
        } finally {
            persistent.close()
            context.deleteDatabase(name)
        }
    }
}
