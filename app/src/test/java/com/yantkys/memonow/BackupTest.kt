package com.yantkys.memonow

import android.app.Application
import androidx.room.Room
import androidx.room.withTransaction
import androidx.test.core.app.ApplicationProvider
import com.yantkys.memonow.backup.*
import com.yantkys.memonow.data.*
import java.io.ByteArrayInputStream
import java.io.IOException
import java.io.InputStream
import java.time.Clock
import java.time.Instant
import java.time.ZoneOffset
import java.util.UUID
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import org.json.JSONObject
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28], application = Application::class)
class BackupTest {
    private lateinit var db: MemonowDatabase
    private lateinit var repository: PostRepository
    private lateinit var backup: BackupStore
    private val now = Instant.parse("2026-10-26T01:00:00Z").toEpochMilli()
    private val clock = Clock.fixed(Instant.ofEpochMilli(now), ZoneOffset.UTC)

    @Before fun setup() {
        db = Room.inMemoryDatabaseBuilder(ApplicationProvider.getApplicationContext<Application>(), MemonowDatabase::class.java).build()
        repository = PostRepository(db.posts(), clock)
        backup = BackupStore(db, repository, clock)
    }
    @After fun teardown() { db.close() }
    private fun post(sequence: Long = 1, body: String = "日本語\n改行と絵文字🌤️") = Post(sequence, UUID.randomUUID().toString(), body, now)
    private fun document(posts: List<Post>) = BackupDocument(now, posts)
    private fun decode(bytes: ByteArray) = BackupJson.read(ByteArrayInputStream(bytes))

    @Test fun roundTripPreservesAllFieldsNullsEmojiAndEqualTimeOrder() = runBlocking {
        val first = post().copy(placeName = "沖縄県 糸満市", weatherCode = 3, temperatureC = 25.5, weatherAt = now - 60_000)
        val second = post(7).copy(weatherCode = 61, weatherAt = now)
        val third = post(20)
        db.posts().insertAll(listOf(first, second, third))
        val (count, bytes) = backup.export()
        assertEquals(3, count)
        val json = JSONObject(bytes.toString(Charsets.UTF_8))
        assertEquals("memonow", json.getString("app"))
        assertTrue(json.getJSONArray("posts").getJSONObject(2).isNull("temperatureC"))
        val parsed = decode(bytes)
        assertEquals(now, parsed.exportedAt)
        assertEquals(listOf(first, second, third), parsed.posts)
        val plan = backup.prepare(parsed)
        backup.restore(plan)
        assertEquals(parsed.posts, db.posts().snapshot())
        val id = repository.create("復元後の投稿")
        assertTrue(repository.find(id)!!.sequence > 20)
    }

    @Test fun longSequenceValuesAreNotRoundedThroughFloatingPoint() = runBlocking {
        val original = post(9_007_199_254_740_993L)
        val parsed = decode(BackupJson.encode(document(listOf(original))))
        assertEquals(original.sequence, parsed.posts.single().sequence)
        backup.restore(backup.prepare(parsed))
        val id = repository.create("次の投稿")
        assertEquals(original.sequence + 1, repository.find(id)!!.sequence)
    }

    @Test fun emptyBackupRoundTripIsAllowedOnlyIntoEmptyDatabase() = runBlocking {
        val (count, bytes) = backup.export()
        assertEquals(0, count)
        val empty = decode(bytes)
        backup.restore(backup.prepare(empty))
        val id = repository.create("消してはいけない")
        try { backup.prepare(empty); fail("empty overwrite accepted") } catch (_: InvalidBackup) { }
        assertEquals(id, db.posts().snapshot().single().id)
    }

    @Test fun manyPostsRestoreOrderAndResetAnOlderHighSequenceCounter() = runBlocking {
        db.posts().insert(post(900_000))
        val posts = (1..1000).map { post(it.toLong(), "同時刻の記録 $it") }
        val parsed = decode(BackupJson.encode(document(posts.reversed())))
        backup.restore(backup.prepare(parsed))
        assertEquals(posts, db.posts().snapshot())
        val id = repository.create("続き")
        assertEquals(1001L, repository.find(id)!!.sequence)
    }

    @Test fun invalidHeadersTypesDatesDuplicatesAndWeatherAreRejectedWithoutDbChanges() = runBlocking {
        val id = repository.create("既存の記録")
        val before = db.posts().snapshot()
        val original = BackupJson.encode(document(listOf(post(), post(2)))).toString(Charsets.UTF_8)
        val invalid = mutableListOf<String>()
        fun mutate(change: (JSONObject) -> Unit) { val json = JSONObject(original); change(json); invalid += json.toString() }
        mutate { it.put("app", "other") }
        mutate { it.put("schemaVersion", 2) }
        mutate { it.put("schemaVersion", "1") }
        mutate { it.remove("exportedAt") }
        mutate { it.put("exportedAt", -1) }
        mutate { it.put("coordinates", "not supported") }
        mutate { it.getJSONArray("posts").getJSONObject(0).remove("placeName") }
        mutate { val list = it.getJSONArray("posts"); list.getJSONObject(1).put("id", list.getJSONObject(0).getString("id").uppercase()) }
        mutate { it.getJSONArray("posts").getJSONObject(1).put("sequence", 1) }
        for ((field, value) in listOf<Pair<String, Any>>(
            "id" to "not-a-uuid", "sequence" to 0, "sequence" to Long.MAX_VALUE,
            "sequence" to 1.5, "createdAt" to -1, "createdAt" to 253402300800000L,
            "body" to "　 \n", "body" to true, "body" to "a".repeat(BackupJson.MAX_BODY + 1),
            "temperatureC" to 1000, "temperatureC" to "NaN", "weatherCode" to -1,
            "weatherCode" to 1.2, "weatherAt" to now, "placeName" to "",
        )) mutate { it.getJSONArray("posts").getJSONObject(0).put(field, value) }
        mutate { it.getJSONArray("posts").getJSONObject(0).put("weatherCode", 3).put("weatherAt", now - 21 * 60_000) }
        invalid += original.replace("\"schemaVersion\": 1", "\"schemaVersion\": 1, \"schemaVersion\": 1")
        invalid += original + " {}"
        invalid += "{broken JSON"
        invalid += original.replaceFirst("\"sequence\": 1", "\"sequence\": 1e9999")
        for (json in invalid) {
            try { decode(json.toByteArray()); fail("invalid data accepted") } catch (_: Exception) { }
            assertEquals(before, db.posts().snapshot())
        }
        assertEquals(id, repository.find(id)!!.id)
    }

    @Test fun inputLimitsInvalidUtf8AndIoFailureAreRejected() {
        val valid = BackupJson.encode(document(emptyList()))
        val oversized = valid + ByteArray(BackupJson.MAX_BYTES) { ' '.code.toByte() }
        try { decode(oversized); fail("oversized input accepted") } catch (_: InvalidBackup) { }
        try { decode(byteArrayOf(0xc3.toByte(), 0x28)); fail("invalid UTF8 accepted") } catch (_: IOException) { }
        val broken = object : InputStream() { override fun read(): Int = throw IOException("provider failed") }
        try { BackupJson.read(broken); fail("read error accepted") } catch (_: IOException) { }
        try { BackupJson.encode(document((1..10_001).map { post(it.toLong()) })); fail("count limit ignored") } catch (_: InvalidBackup) { }
        try { BackupJson.encode(document((1..120).map { post(it.toLong(), "a".repeat(100_000)) })); fail("output limit ignored") } catch (_: InvalidBackup) { }
    }

    @Test fun transactionFailureAfterDeletionAndPartialInsertRollsBackEverything() = runBlocking {
        repository.create("元の記録")
        val before = db.posts().snapshot()
        val first = post()
        val second = post(2)
        val plan = backup.prepare(decode(BackupJson.encode(document(listOf(first, second)))))
        db.openHelper.writableDatabase.execSQL("CREATE TRIGGER fail_restore BEFORE INSERT ON posts WHEN NEW.id = '${second.id}' BEGIN SELECT RAISE(ABORT, 'simulated disk failure'); END")
        try { backup.restore(plan); fail("failure was not reported") } catch (_: android.database.sqlite.SQLiteException) { }
        assertEquals(before, db.posts().snapshot())
        db.openHelper.writableDatabase.execSQL("DROP TRIGGER fail_restore")
        backup.restore(backup.prepare(plan.document))
        assertEquals(listOf(first, second), db.posts().snapshot())
    }

    @Test fun changedRecordsInvalidatePreviewInsteadOfOverwritingNewerWork() = runBlocking {
        val id = repository.create("元")
        val plan = backup.prepare(document(listOf(post())))
        repository.edit(id, "確認中に編集")
        try { backup.restore(plan); fail("stale confirmation accepted") } catch (_: InvalidBackup) { }
        assertEquals("確認中に編集", repository.find(id)!!.body)
    }

    @Test fun preRestoreMetadataCannotContaminateSameRestoredId() = runBlocking {
        val id = repository.create("元")
        val token = repository.metadataToken()
        val restored = repository.find(id)!!.copy(body = "復元した本文", placeName = "復元した地名")
        val plan = backup.prepare(document(listOf(restored)))
        assertEquals(0, repository.attachPlace(id, "古い地名", token))
        backup.restore(plan)
        assertEquals(0, repository.attachWeather(id, 3, 20.0, now, token))
        assertEquals(restored, repository.find(id))
        val newToken = repository.metadataToken()
        assertEquals(1, repository.attachWeather(id, 3, 20.0, now, newToken))
    }

    @Test fun cancellationInsideReplacementTransactionRestoresOriginalRows() = runBlocking {
        repository.create("元の記録")
        val before = db.posts().snapshot()
        val entered = CompletableDeferred<Unit>()
        val job = launch(Dispatchers.Default) {
            repository.replaceExclusively(1) {
                db.withTransaction {
                    db.posts().deleteAll()
                    db.posts().insert(post())
                    entered.complete(Unit)
                    awaitCancellation()
                }
            }
        }
        entered.await()
        job.cancel()
        job.join()
        assertEquals(before, db.posts().snapshot())
    }
}
