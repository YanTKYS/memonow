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
