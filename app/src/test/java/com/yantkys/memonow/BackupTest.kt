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
        val first = post().copy(placeName = "沖縄県 糸満市", weatherCode = 3, temperatureC = 25.5, weatherAt = now - 60_000, moodId = "happy")
        val second = post(7).copy(weatherCode = 61, weatherAt = now, moodId = "tired")
        val third = post(20)
        db.posts().insertAll(listOf(first, second, third))
        val (count, bytes) = backup.export()
        assertEquals(3, count)
        val json = JSONObject(bytes.toString(Charsets.UTF_8))
        assertEquals("memonow", json.getString("app"))
        assertEquals(2, json.getInt("schemaVersion"))
        assertTrue(json.getJSONArray("posts").getJSONObject(2).has("moodId"))
        assertTrue(json.getJSONArray("posts").getJSONObject(2).isNull("moodId"))
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

    @Test fun oldVersionOneRestoresWithNullMoodEvenWhenSchemaFieldComesLast() = runBlocking {
        val original = post().copy(placeName = "沖縄県 糸満市", weatherCode = 0, temperatureC = 0.0, weatherAt = now)
        val json = JSONObject(BackupJson.encode(document(listOf(original))).toString(Charsets.UTF_8))
        json.getJSONArray("posts").getJSONObject(0).remove("moodId")
        // JSON object order is not significant; don't assume schema precedes posts.
        val legacy = "{\"posts\":${json.getJSONArray("posts")},\"exportedAt\":$now,\"app\":\"memonow\",\"schemaVersion\":1}"
        repository.create("置換前", "sad")
        val parsed = decode(legacy.toByteArray())
        assertEquals(listOf(original), parsed.posts)
        backup.restore(backup.prepare(parsed))
        assertEquals(listOf(original), db.posts().snapshot())
        val newJson = JSONObject(backup.export().second.toString(Charsets.UTF_8))
        assertEquals(2, newJson.getInt("schemaVersion"))
        assertTrue(newJson.getJSONArray("posts").getJSONObject(0).isNull("moodId"))
    }

    @Test fun versionThreeReopenAndJsonVersionTwoRestoreKeepLegacyIdsAndNullUnchanged() = runBlocking {
        val context = ApplicationProvider.getApplicationContext<Application>()
        val name = "legacy-mood-v3.db"
        context.deleteDatabase(name)
        fun open() = Room.databaseBuilder(context, MemonowDatabase::class.java, name).build()
        var persistent = open()
        val original = (listOf("happy", "fun", "angry", "sad", "normal", "tired", null)).mapIndexed { i, mood ->
            post(i + 1L).copy(moodId = mood, placeName = "沖縄県 糸満市", weatherCode = 3, temperatureC = 25.5, weatherAt = now)
        }
        try {
            persistent.posts().insertAll(original)
            persistent.close()
            persistent = open() // Same v3 schema, with no migration or rewrite.
            assertEquals(3, persistent.openHelper.writableDatabase.version)
            assertEquals(original, persistent.posts().snapshot())
            val repo = PostRepository(persistent.posts(), clock)
            val store = BackupStore(persistent, repo, clock)
            for (row in original.filter { it.moodId in listOf("fun", "normal") })
                repo.edit(row.id, "本文だけ変更", row.moodId)
            val expected = original.map { if (it.moodId in listOf("fun", "normal")) it.copy(body = "本文だけ変更") else it }
            val bytes = store.export().second
            assertEquals(2, JSONObject(bytes.toString(Charsets.UTF_8)).getInt("schemaVersion"))
            val parsed = decode(bytes)
            assertEquals(expected, parsed.posts)
            repo.delete(original[1].id)
            store.restore(store.prepare(parsed))
            assertEquals(expected, persistent.posts().snapshot())
        } finally {
            persistent.close()
            context.deleteDatabase(name)
        }
    }

    @Test fun moodSchemaVersionsRequireTheirOwnExactFieldsAndRejectInvalidIds() = runBlocking {
        val id = repository.create("維持する記録", "sad")
        val before = repository.find(id)
        val valid = BackupJson.encode(document(com.yantkys.memonow.data.Mood.entries.mapIndexed { i, mood -> post(i + 1L).copy(moodId = mood.id) }))
        assertEquals(com.yantkys.memonow.data.Mood.entries.map { it.id }, decode(valid).posts.map { it.moodId })
        fun reject(change: (JSONObject) -> Unit) {
            val json = JSONObject(valid.toString(Charsets.UTF_8)).also(change)
            assertThrows(InvalidBackup::class.java) { decode(json.toString().toByteArray()) }
        }
        for (value in listOf<Any>("unknown", "HAPPY", "😊", "", " happy ", 1, true))
            reject { it.getJSONArray("posts").getJSONObject(0).put("moodId", value) }
        reject { it.getJSONArray("posts").getJSONObject(0).remove("moodId") }
        reject { it.getJSONArray("posts").getJSONObject(0).put("mood", "happy") }
        reject { it.put("schemaVersion", 1) } // v1 cannot silently ignore a mood field.
        val duplicate = valid.toString(Charsets.UTF_8).replaceFirst("\"moodId\": \"happy\"", "\"moodId\": \"happy\", \"moodId\": null")
        assertThrows(InvalidBackup::class.java) { decode(duplicate.toByteArray()) }
        val legacy = JSONObject(valid.toString(Charsets.UTF_8)).apply {
            put("schemaVersion", 1)
            val posts = getJSONArray("posts")
            for (i in 0 until posts.length()) posts.getJSONObject(i).remove("moodId")
            posts.getJSONObject(0).put("unexpected", "bad")
        }
        assertThrows(InvalidBackup::class.java) { decode(legacy.toString().toByteArray()) }
        assertEquals(before, repository.find(id))
    }

    @Test fun moodOnlyEditInvalidatesRestorePreviewAndOldMetadataCannotReplaceRestoredMood() = runBlocking {
        val id = repository.create("本文", "happy")
        val token = repository.metadataToken()
        val restored = repository.find(id)!!.copy(moodId = "fun")
        val plan = backup.prepare(document(listOf(restored)))
        repository.edit(id, "本文", "sad")
        try { backup.restore(plan); fail("stale mood preview accepted") } catch (_: InvalidBackup) { }
        assertEquals("sad", repository.find(id)!!.moodId)
        backup.restore(backup.prepare(document(listOf(restored))))
        assertEquals(0, repository.attachPlace(id, "古い属性", token))
        assertEquals(0, repository.attachWeather(id, 3, 20.0, now, token))
        assertEquals(restored, repository.find(id))
        val fresh = repository.metadataToken()
        repository.attachPlace(id, "新しい属性", fresh)
        assertEquals(restored.copy(placeName = "新しい属性"), repository.find(id))
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
        mutate { it.put("schemaVersion", 3) }
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
        invalid += original.replace("\"schemaVersion\": 2", "\"schemaVersion\": 2, \"schemaVersion\": 2")
        val marker = JSONObject(original).also { it.getJSONArray("posts").getJSONObject(0).put("body", "MARKER") }.toString()
        invalid += marker.replace("\"MARKER\"", "\"bad\\q\"")
        invalid += marker.replace("\"MARKER\"", "\"bad\nraw\"")
        val numberMarker = JSONObject(original).also {
            it.getJSONArray("posts").getJSONObject(0).put("temperatureC", "NUMBER").put("weatherAt", now)
        }.toString()
        invalid += numberMarker.replace("\"NUMBER\"", "1.")
        invalid += numberMarker.replace("\"NUMBER\"", "01")
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
        repository.create("元の記録", "sad")
        val before = db.posts().snapshot()
        val first = post().copy(moodId = "fun")
        val second = post(2).copy(moodId = "happy")
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
        repository.create("元の記録", "tired")
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
