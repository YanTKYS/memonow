package com.yantkys.memonow

import android.app.Application
import android.net.Uri
import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModelStore
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.yantkys.memonow.backup.*
import com.yantkys.memonow.data.*
import java.io.IOException
import java.util.UUID
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.resetMain
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
class BackupViewModelTest {
    private lateinit var db: MemonowDatabase
    private lateinit var repository: PostRepository
    private lateinit var model: PostViewModel
    private val viewModels = ViewModelStore()
    private lateinit var files: TestFiles
    private var paused = false
    private val uri = Uri.parse("content://documents/backup.json")
    private val imported = Post(10, UUID.randomUUID().toString(), "復元する記録", 1000)

    @Before fun setup() {
        Dispatchers.setMain(UnconfinedTestDispatcher())
        db = Room.inMemoryDatabaseBuilder(ApplicationProvider.getApplicationContext<Application>(), MemonowDatabase::class.java).build()
        repository = PostRepository(db.posts())
        files = TestFiles()
        model = PostViewModel(repository, SavedStateHandle(), backupStore = BackupStore(db, repository),
            backupFiles = files, pauseMetadata = { paused = it })
        viewModels.put("posts", model)
    }
    @After fun teardown() {
        viewModels.clear()
        db.close()
        Dispatchers.resetMain()
    }
    private suspend fun result() = withTimeout(10_000) { model.backupState.first { it is BackupState.Finished } as BackupState.Finished }
    private suspend fun confirmation() = withTimeout(10_000) { model.backupState.first { it is BackupState.Confirm } as BackupState.Confirm }

    @Test fun pickerCancellationNeverReportsSuccessOrChangesRecords() = runBlocking {
        val id = repository.create("元の記録")
        assertTrue(model.beginBackup(true))
        assertFalse(model.beginBackup(false))
        model.exportTo(null)
        assertFalse(result().success)
        model.dismissBackup()
        assertTrue(model.beginBackup(false))
        assertTrue(paused)
        model.importFrom(null)
        assertFalse(result().success)
        assertFalse(paused)
        assertEquals(id, db.posts().snapshot().single().id)
        assertEquals(0, files.reads)
        assertEquals(0, files.writes)
    }

    @Test fun exportSuccessWaitsForWriteAndWriteFailureIsNotSuccess() = runBlocking {
        repository.create("元の記録")
        files.failWrite = true
        model.beginBackup(true)
        model.exportTo(uri)
        assertFalse(result().success)
        assertEquals(1, db.posts().count())
        model.dismissBackup()
        files.failWrite = false
        model.beginBackup(true)
        model.exportTo(uri)
        assertTrue(result().success)
        assertEquals(1, BackupJson.read(files.bytes!!.inputStream()).posts.size)
    }

    @Test fun validatedImportRequiresExplicitConfirmationAndCanBeCancelled() = runBlocking {
        val id = repository.create("元の記録")
        files.content = BackupDocument(2000, listOf(imported))
        model.beginBackup(false)
        model.importFrom(uri)
        val preview = confirmation()
        assertEquals(1, preview.plan.existingCount)
        assertEquals(1, preview.plan.document.posts.size)
        assertEquals(id, db.posts().snapshot().single().id)
        model.updateDraft("復元中に投稿しない")
        model.send()
        assertEquals(1, db.posts().count())
        model.dismissBackup()
        assertFalse(paused)
        assertEquals(id, db.posts().snapshot().single().id)
        model.confirmRestore() // No pending confirmation -> no mutation.
        assertEquals(id, db.posts().snapshot().single().id)
    }

    @Test fun confirmedRestorePublishesCountAndLatestIdWithoutChangingDraftOrSettings() = runBlocking {
        repository.create("元の記録")
        model.updateDraft("書きかけは保持")
        files.content = BackupDocument(2000, listOf(imported))
        model.beginBackup(false)
        model.importFrom(uri)
        confirmation()
        model.confirmRestore()
        val success = result()
        assertTrue(success.success)
        assertTrue(success.message.contains("1件"))
        assertEquals(listOf(imported), db.posts().snapshot())
        assertEquals(imported.id, model.sentId.value)
        assertEquals("書きかけは保持", model.draft.value)
        assertFalse(paused)
    }

    @Test fun replacingRecordsWhileDateFilteredReturnsToAllAndRefreshesDayCounts() = runBlocking {
        model.refreshTimeZone(java.time.ZoneOffset.UTC)
        val id = repository.create("元の記録","normal")
        val initial = withTimeout(10_000) { model.feed.first { it is FeedState.Ready && it.posts.any { p -> p.id == id } } } as FeedState.Ready
        assertTrue(model.selectDate(initial.dateCounts.keys.single()))
        files.content = BackupDocument(2000,listOf(imported.copy(moodId="fun")))
        model.beginBackup(false); model.importFrom(uri); confirmation(); model.confirmRestore()
        assertTrue(result().success)
        val ready = withTimeout(10_000) { model.feed.first { it is FeedState.Ready && it.posts.singleOrNull()?.id == imported.id } } as FeedState.Ready
        assertNull(ready.selectedDate)
        assertEquals(mapOf(java.time.LocalDate.of(1970,1,1) to 1),ready.dateCounts)
        assertEquals(listOf(imported.copy(moodId="fun")),ready.visiblePosts)
        assertEquals(imported.id,model.sentId.value)
    }

    @Test fun readOrValidationFailureCannotReplaceRecords() = runBlocking {
        val id = repository.create("元の記録")
        files.failRead = true
        model.beginBackup(false)
        model.importFrom(uri)
        assertFalse(result().success)
        assertEquals(id, db.posts().snapshot().single().id)
        model.dismissBackup()
        files.failRead = false
        files.content = BackupDocument(2000, emptyList())
        model.beginBackup(false)
        model.importFrom(uri)
        assertFalse(result().success)
        assertFalse(paused)
        assertEquals(id, db.posts().snapshot().single().id)
    }

    @Test fun interruptedPreviewDoesNotRestoreOnProcessRecreation() {
        val restored = PostViewModel(repository, SavedStateHandle(mapOf("backupStage" to "confirm")))
        viewModels.put("restored", restored)
        assertTrue(restored.backupState.value is BackupState.Finished)
        restored.confirmRestore()
        assertEquals(null, restored.sentId.value)
    }

    private class TestFiles : BackupFiles {
        var content = BackupDocument(2000, emptyList())
        var failRead = false
        var failWrite = false
        var reads = 0
        var writes = 0
        var bytes: ByteArray? = null
        override suspend fun read(uri: Uri): BackupDocument {
            reads++
            if (failRead) throw IOException("provider read failure")
            return content
        }
        override suspend fun write(uri: Uri, bytes: ByteArray) {
            writes++
            if (failWrite) throw IOException("provider write/close failure")
            this.bytes = bytes
        }
    }
}
