package com.yantkys.memonow

import android.app.Application
import android.database.sqlite.SQLiteDatabase
import android.location.Address
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.yantkys.memonow.data.MemonowDatabase
import com.yantkys.memonow.data.PostRepository
import com.yantkys.memonow.metadata.*
import java.io.IOException
import java.time.Clock
import java.time.Instant
import java.time.ZoneOffset
import java.util.Locale
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.runTest
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
@OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
class MetadataTest {
    private val context get() = ApplicationProvider.getApplicationContext<Application>()
    private lateinit var db: MemonowDatabase
    private lateinit var repository: PostRepository
    private val now = Instant.parse("2026-10-26T01:00:00Z").toEpochMilli()
    private val clock = Clock.fixed(Instant.ofEpochMilli(now), ZoneOffset.UTC)
    private var permitted = true
    private var locationCalls = 0
    private var weatherCalls = 0
    private val fix = LocationFix(26.123456, 127.678912, 1000, 500f)
    private var locate: suspend () -> LocationFix? = { fix }
    private var place: suspend (CoarsePoint) -> String? = { "沖縄県 糸満市" }
    private var weather: suspend (CoarsePoint) -> Weather? = { Weather(0, 25.5, now) }

    @Before fun setUp() {
        db = Room.inMemoryDatabaseBuilder(context, MemonowDatabase::class.java).build()
        repository = PostRepository(db.posts(), clock)
    }
    @After fun tearDown() { db.close() }
    private fun enricher(elapsed: () -> Long = { android.os.SystemClock.elapsedRealtime() }) = PostMetadata(repository,
        LocationSource { locationCalls++; locate() }, PlaceSource { place(it) },
        WeatherSource { weatherCalls++; weather(it) }, { permitted }, clock, elapsedMillis = elapsed)

    @Test fun metadataUsesRoundedCoordinatesAndNeverChangesIdentityOrBody() = runTest {
        val id = repository.create("本文")
        val before = repository.find(id)!!
        place = { assertEquals(26.12, it.latitude, 0.0); "沖縄県 糸満市" }
        weather = {
            assertEquals(127.68, it.longitude, 0.0)
            Weather(0, 25.5, now)
        }
        enricher().enrich(id)
        val after = repository.find(id)!!
        assertEquals(before.copy(placeName = "沖縄県 糸満市", weatherCode = 0, temperatureC = 25.5, weatherAt = now), after)
    }

    @Test fun offOrPermissionDeniedMakesNoLocationOrWeatherRequest() = runTest {
        val id = repository.create("本文")
        permitted = false
        enricher().enrich(id)
        assertEquals(0, locationCalls)
        assertEquals(0, weatherCalls)
        assertNull(repository.find(id)!!.placeName)
    }

    @Test fun locationUnavailableTimeoutAndStaleFixNeverCallWeather() = runTest {
        val id = repository.create("本文")
        locate = { null }
        enricher().enrich(id)
        locate = { delay(60_000); fix }
        enricher().enrich(id)
        locate = { fix.copy(ageMillis = 120_001) }
        enricher().enrich(id)
        locate = { fix.copy(latitude = Double.NaN) }
        enricher().enrich(id)
        locate = { throw SecurityException("revoked") }
        enricher().enrich(id)
        assertEquals(0, weatherCalls)
        assertNull(repository.find(id)!!.weatherAt)
    }

    @Test fun geocoderFailureOrTimeoutStillAllowsWeather() = runTest {
        for (failure in listOf<suspend (CoarsePoint) -> String?>(
            { null }, { throw IOException("geocoder unavailable") }, { delay(30_000); "too late" },
        )) {
            val id = repository.create("本文")
            place = failure
            enricher().enrich(id)
            assertNull(repository.find(id)!!.placeName)
            assertEquals(25.5, repository.find(id)!!.temperatureC!!, 0.0)
        }
    }

    @Test fun weatherErrorTimeoutOrDistantEstimateKeepsPlaceOnly() = runTest {
        for (failure in listOf<suspend (CoarsePoint) -> Weather?>(
            { throw IOException("HTTP 503") }, { delay(60_000); Weather(0, 25.5, now) },
            { Weather(0, 25.5, now - 21 * 60_000) }, { Weather(0, 25.5, now + 21 * 60_000) },
        )) {
            val id = repository.create("本文")
            weather = failure
            enricher().enrich(id)
            val post = repository.find(id)!!
            assertEquals("沖縄県 糸満市", post.placeName)
            assertNull(post.weatherAt)
            assertNull(post.weatherCode)
        }
    }

    @Test fun editingDuringLookupIsNotOverwrittenAndDeletionIsNotRecreated() = runBlocking {
        for (delete in listOf(false, true)) {
            val id = repository.create("元の本文")
            val entered = CompletableDeferred<Unit>()
            val release = CompletableDeferred<Unit>()
            weather = { entered.complete(Unit); release.await(); Weather(3, null, now) }
            val job = launch { enricher().enrich(id) }
            entered.await()
            assertEquals("沖縄県 糸満市", repository.find(id)!!.placeName)
            if (delete) repository.delete(id) else repository.edit(id, "編集中に保存した本文")
            release.complete(Unit)
            job.join()
            if (delete) assertNull(repository.find(id)) else {
                assertEquals("編集中に保存した本文", repository.find(id)!!.body)
                assertEquals(3, repository.find(id)!!.weatherCode)
            }
        }
    }

    @Test fun turningOffDuringLookupPreventsSubsequentWeatherAndWrites() = runTest {
        val id = repository.create("本文")
        place = { permitted = false; "保存しない地名" }
        enricher().enrich(id)
        assertEquals(0, weatherCalls)
        assertNull(repository.find(id)!!.placeName)
    }

    @Test fun disablingDuringWeatherLookupDiscardsResultButKeepsPreviouslySavedPlace() = runTest {
        val id = repository.create("本文")
        weather = { permitted = false; Weather(0, 25.5, now) }
        enricher().enrich(id)
        assertEquals("沖縄県 糸満市", repository.find(id)!!.placeName)
        assertNull(repository.find(id)!!.weatherAt)
    }

    @Test fun pastPostsAreNeverEnrichedWithCurrentWeather() = runTest {
        val old = PostRepository(db.posts(), Clock.fixed(Instant.ofEpochMilli(now - 121_000), ZoneOffset.UTC))
        val id = old.create("過去の本文")
        enricher().enrich(id)
        assertEquals(0, locationCalls)
    }

    @Test fun diagnosticsDescribeSuccessAndIndependentPartialSuccess() = runTest {
        val service = enricher()
        service.enrich(repository.create("both"))
        assertEquals("地名・天気を付与しました", service.diagnostic.value.message)
        assertTrue(service.diagnostic.value.placeSaved)
        assertTrue(service.diagnostic.value.weatherSaved)
        for (reason in listOf(MetadataProblem.GEOCODER_UNSUPPORTED, MetadataProblem.PLACE_FAILED)) {
            place = { throw MetadataFailure(reason) }
            service.enrich(repository.create("weather only"))
            assertEquals(reason, service.diagnostic.value.placeProblem)
            assertTrue(service.diagnostic.value.weatherSaved)
            assertFalse(service.diagnostic.value.placeSaved)
        }
        place = { null }
        service.enrich(repository.create("empty geocoder"))
        assertEquals(MetadataProblem.PLACE_EMPTY, service.diagnostic.value.placeProblem)
        assertTrue(service.diagnostic.value.weatherSaved)
        place = { "市" }
        weather = { throw IOException("private server details") }
        service.enrich(repository.create("place only"))
        assertEquals(MetadataProblem.WEATHER_NETWORK, service.diagnostic.value.weatherProblem)
        assertTrue(service.diagnostic.value.placeSaved)
        assertFalse(service.diagnostic.value.weatherSaved)
        assertFalse(service.diagnostic.value.message.contains("private"))
    }

    @Test fun diagnosticsDistinguishPositionFailuresWithoutCallingExternalServices() = runTest {
        val service = enricher()
        for (reason in listOf(MetadataProblem.PERMISSION, MetadataProblem.LOCATION_OFF, MetadataProblem.PROVIDER)) {
            locate = { throw MetadataFailure(reason) }
            service.enrich(repository.create("body"))
            assertEquals(reason, service.diagnostic.value.problem)
            assertEquals(MetadataStage.STOPPED, service.diagnostic.value.stage)
        }
        locate = { delay(60_000); fix }
        service.enrich(repository.create("timeout"))
        assertEquals(MetadataProblem.LOCATION_TIMEOUT, service.diagnostic.value.problem)
        locate = { fix.copy(ageMillis = 120_001) }
        service.enrich(repository.create("old"))
        assertEquals(MetadataProblem.LOCATION_STALE, service.diagnostic.value.problem)
        locate = { fix.copy(accuracyMeters = 10_001f) }
        service.enrich(repository.create("inaccurate"))
        assertEquals(MetadataProblem.LOCATION_ACCURACY, service.diagnostic.value.problem)
        assertEquals(0, weatherCalls)
        permitted = false
        service.enrich(repository.create("off"))
        assertEquals(MetadataProblem.OFF, service.diagnostic.value.problem)
    }

    @Test fun diagnosticsDistinguishWeatherErrorsTimeoutAndTimeMismatch() = runTest {
        val service = enricher()
        for (reason in listOf(MetadataProblem.WEATHER_HTTP, MetadataProblem.WEATHER_INVALID)) {
            weather = { throw MetadataFailure(reason) }
            service.enrich(repository.create("body"))
            assertEquals(reason, service.diagnostic.value.weatherProblem)
            assertTrue(service.diagnostic.value.placeSaved)
        }
        weather = { delay(60_000); Weather(0, 20.0, now) }
        service.enrich(repository.create("timeout"))
        assertEquals(MetadataProblem.WEATHER_TIMEOUT, service.diagnostic.value.weatherProblem)
        weather = { Weather(0, 20.0, now + 21 * 60_000) }
        service.enrich(repository.create("time mismatch"))
        assertEquals(MetadataProblem.WEATHER_TIME, service.diagnostic.value.weatherProblem)
        place = { delay(30_000); "late" }
        weather = { Weather(0, 20.0, now) }
        service.enrich(repository.create("place timeout"))
        assertEquals(MetadataProblem.PLACE_TIMEOUT, service.diagnostic.value.placeProblem)
        assertTrue(service.diagnostic.value.weatherSaved)
    }

    @Test fun progressMatchesStagesAndOlderCompletionCannotReplaceLatestResult() = runBlocking {
        val service = enricher()
        val first = repository.create("first")
        val second = repository.create("second")
        val started = CompletableDeferred<Unit>()
        val resume = CompletableDeferred<Unit>()
        locate = {
            assertEquals(MetadataStage.LOCATION, service.diagnostic.value.stage)
            fix
        }
        var call = 0
        place = {
            assertEquals(MetadataStage.PLACE, service.diagnostic.value.stage)
            if (++call == 1) { started.complete(Unit); resume.await(); "市" } else null
        }
        weather = { Weather(0, 25.5, now) }
        val oldJob = launch { service.enrich(first) }
        started.await()
        service.enrich(second)
        val latest = service.diagnostic.value
        assertEquals(MetadataProblem.PLACE_EMPTY, latest.placeProblem)
        resume.complete(Unit)
        oldJob.join()
        assertEquals(latest, service.diagnostic.value)
        assertEquals("市", repository.find(first)!!.placeName)
    }

    @Test fun cancellationAndScreenExitReplaceSuccessAndFencePendingResults() = runBlocking {
        val service = enricher()
        service.enrich(repository.create("success"))
        service.interrupt(MetadataProblem.BACKGROUND)
        assertEquals(MetadataStage.STOPPED, service.diagnostic.value.stage)
        assertFalse(service.diagnostic.value.message.contains("付与しました"))
        val started = CompletableDeferred<Unit>()
        val id = repository.create("cancel")
        place = { started.complete(Unit); kotlinx.coroutines.awaitCancellation() }
        val job = launch { service.enrich(id) }
        started.await()
        service.interrupt(MetadataProblem.OFF)
        job.cancel(); job.join()
        assertEquals(MetadataProblem.OFF, service.diagnostic.value.problem)
        assertEquals(MetadataStage.PLACE, service.diagnostic.value.stoppedAt)
        assertNull(repository.find(id)!!.placeName)
        assertNull(repository.find(id)!!.weatherAt)
        assertEquals(1, weatherCalls)
    }

    @Test fun weatherStageAndDeletedPostNeverClaimSuccessfulSave() = runBlocking {
        val service = enricher()
        val id = repository.create("delete while fetching")
        weather = {
            assertEquals(MetadataStage.WEATHER, service.diagnostic.value.stage)
            repository.delete(id)
            Weather(0, 20.0, now)
        }
        service.enrich(id)
        assertEquals(MetadataProblem.POST_CHANGED, service.diagnostic.value.problem)
        assertFalse(service.diagnostic.value.weatherSaved)
    }

    @Test fun transientLocationTimeoutRecoversOnceAndPreservesPost() = runTest {
        var calls = 0
        locate = { if (++calls == 1) { delay(60_000) }; fix }
        val id = repository.create("先行保存")
        val original = repository.find(id)!!
        val service = enricher()
        service.enrich(id)
        assertEquals(2, calls)
        assertEquals(original.copy(placeName = "沖縄県 糸満市", weatherCode = 0, temperatureC = 25.5, weatherAt = now), repository.find(id))
        assertEquals(2, service.diagnostic.value.attempt)
        assertEquals(MetadataStage.DONE, service.diagnostic.value.stage)
    }

    @Test fun retriesOnlyMissingAttributeAndNeverOverwritesSuccessfulOne() = runTest {
        val service = enricher()
        var placeCalls = 0
        place = { placeCalls++; "最初の地名" }
        locate = { if (locationCalls > 1) throw MetadataFailure(MetadataProblem.PROVIDER); fix }
        weather = { if (weatherCalls == 1) throw IOException("temporary") else Weather(0, 25.5, now) }
        val id = repository.create("本文")
        service.enrich(id)
        assertEquals(2, weatherCalls)
        assertEquals(1, locationCalls)
        assertEquals(1, placeCalls)
        place = { fail("already attached place must not be queried"); null }
        weather = { fail("already attached weather must not be queried"); null }
        service.enrich(id)
        assertEquals("最初の地名", repository.find(id)!!.placeName)
        assertEquals(25.5, repository.find(id)!!.temperatureC!!, 0.0)
    }

    @Test fun failedPlaceRecoversWithoutRefetchingSuccessfulWeather() = runTest {
        var calls = 0
        locate = { if (locationCalls > 1) throw MetadataFailure(MetadataProblem.PROVIDER); fix }
        place = { if (++calls == 1) throw IOException("temporary") else "市" }
        val id = repository.create("本文")
        enricher().enrich(id)
        assertEquals(2, calls)
        assertEquals(1, locationCalls)
        assertEquals(1, weatherCalls)
        assertEquals("市", repository.find(id)!!.placeName)
        assertEquals(25.5, repository.find(id)!!.temperatureC!!, 0.0)
    }

    @Test fun cachedPositionExpiresBeforeRetryWithoutAnotherLocationRequest() = runTest {
        locate = { fix.copy(ageMillis = 119_000) }
        weather = { throw IOException("temporary") }
        val service = enricher { testScheduler.currentTime }
        val id = repository.create("本文")
        service.enrich(id)
        assertEquals(1, locationCalls)
        assertEquals(1, weatherCalls)
        assertEquals(MetadataProblem.LOCATION_STALE, service.diagnostic.value.problem)
        assertEquals("沖縄県 糸満市", repository.find(id)!!.placeName)
        assertNull(repository.find(id)!!.weatherAt)
    }

    @Test fun positionExpiryDuringWeatherResponsePreventsSavingIt() = runTest {
        locate = { fix.copy(ageMillis = 119_000) }
        weather = { delay(1_500); Weather(0, 25.5, now) }
        val service = enricher { testScheduler.currentTime }
        val id = repository.create("本文")
        service.enrich(id)
        assertEquals(MetadataProblem.LOCATION_STALE, service.diagnostic.value.problem)
        assertEquals(1, weatherCalls)
        assertNull(repository.find(id)!!.weatherAt)
    }

    @Test fun cachedPointCannotBeUsedAfterGuardsChangeDuringPartialRetry() = runTest {
        for (change in listOf("permission", "background", "delete", "restore")) {
            permitted = true
            var calls = 0
            lateinit var id: String
            lateinit var service: PostMetadata
            val entered = CompletableDeferred<Unit>()
            weather = { calls++; entered.complete(Unit); throw IOException("temporary") }
            service = enricher()
            id = repository.create(change)
            val job = launch { service.enrich(id) }
            entered.await()
            service.diagnostic.first { it.stage == MetadataStage.RETRY }
            // Change the guard while the 2-second retry is pending.
            when (change) {
                "permission" -> permitted = false
                "background" -> service.interrupt(MetadataProblem.BACKGROUND)
                "delete" -> repository.delete(id)
                "restore" -> repository.prepareReplacement { 0L }
            }
            job.join()
            assertEquals(1, calls)
            assertNull(repository.find(id)?.weatherAt)
        }
    }

    @Test fun retryLimitAndPermanentFailuresDoNotLoop() = runTest {
        val service = enricher()
        locate = { delay(60_000); fix }
        service.enrich(repository.create("timeouts"))
        assertEquals(2, locationCalls)
        assertEquals(MetadataProblem.LOCATION_TIMEOUT, service.diagnostic.value.problem)
        for (problem in listOf(MetadataProblem.PERMISSION, MetadataProblem.LOCATION_OFF, MetadataProblem.PROVIDER)) {
            var calls = 0
            locate = { calls++; throw MetadataFailure(problem) }
            service.enrich(repository.create("permanent"))
            assertEquals(1, calls)
        }
        locate = { fix }
        weatherCalls = 0
        weather = { throw MetadataFailure(MetadataProblem.WEATHER_HTTP) }
        service.enrich(repository.create("HTTP 4xx"))
        assertEquals(1, weatherCalls)
    }

    @Test fun boundedQueueServesEightBurstPostsWithAtMostTwoLookupsAndRejectsNinth() = runBlocking {
        val gate = CompletableDeferred<Unit>()
        val started = CompletableDeferred<Unit>()
        var running = 0
        var maximum = 0
        locate = {
            running++; maximum = maxOf(maximum, running)
            if (running == 2) started.complete(Unit)
            try { gate.await(); fix } finally { running-- }
        }
        val service = enricher()
        val ids = (1..9).map { repository.create("post $it") }
        val jobs = ids.take(8).map { id -> launch(start = kotlinx.coroutines.CoroutineStart.UNDISPATCHED) { service.enrich(id) } }
        started.await()
        service.enrich(ids.last())
        assertEquals(MetadataProblem.BUSY, service.diagnostic.value.problem)
        gate.complete(Unit)
        jobs.forEach { it.join() }
        assertEquals(2, maximum)
        assertEquals(8, locationCalls)
        for (id in ids.take(8)) assertNotNull(repository.find(id)!!.weatherAt)
        assertNull(repository.find(ids.last())!!.weatherAt)
        assertEquals("post 9", repository.find(ids.last())!!.body)
    }

    @Test fun permissionOffAndInterruptionDuringRetryPreventFurtherRequests() = runTest {
        val service = enricher()
        val entered = CompletableDeferred<Unit>()
        locate = { entered.complete(Unit); null }
        val id = repository.create("本文")
        val job = launch { service.enrich(id) }
        entered.await()
        permitted = false
        service.interrupt(MetadataProblem.OFF)
        job.cancel(); job.join()
        assertEquals(1, locationCalls)
        assertEquals(0, weatherCalls)
        assertNull(repository.find(id)!!.weatherAt)
        assertEquals(MetadataProblem.OFF, service.diagnostic.value.problem)
    }

    @Test fun expiryAndDeletionAfterFailureDoNotFetchAgain() = runTest {
        val time = java.util.concurrent.atomic.AtomicLong(now)
        val movingClock = object : Clock() {
            override fun getZone() = ZoneOffset.UTC
            override fun withZone(zone: java.time.ZoneId): Clock = this
            override fun instant() = Instant.ofEpochMilli(time.get())
        }
        val service = PostMetadata(repository, LocationSource { locationCalls++; fix }, PlaceSource { "市" },
            WeatherSource { weatherCalls++; time.set(now + 121_000); throw IOException("temporary") }, { true }, movingClock)
        service.enrich(repository.create("expires"))
        assertEquals(1, locationCalls)
        assertEquals(MetadataProblem.EXPIRED, service.diagnostic.value.problem)
        val id = repository.create("deleted")
        place = { repository.delete(id); throw IOException("temporary") }
        enricher().enrich(id)
        assertNull(repository.find(id))
    }

    @Test fun restoreAndNonCooperativeLateResultCannotWriteOrReplaceNewDiagnostic() = runBlocking {
        val started = CompletableDeferred<Unit>()
        val resume = CompletableDeferred<Unit>()
        val service = enricher()
        val id = repository.create("original")
        place = {
            kotlinx.coroutines.withContext(kotlinx.coroutines.NonCancellable) {
                started.complete(Unit); resume.await(); "古い地名"
            }
        }
        val job = launch { service.enrich(id) }
        started.await()
        repository.prepareReplacement { 0L } // Invalidate even if restored UUID is identical.
        service.interrupt(MetadataProblem.RESTORE)
        job.cancel()
        val expected = service.diagnostic.value
        resume.complete(Unit); job.join()
        assertNull(repository.find(id)!!.placeName)
        assertNull(repository.find(id)!!.weatherAt)
        assertEquals(expected, service.diagnostic.value)
    }

    @Test fun queuedCancellationReleasesCapacityForNextPost() = runBlocking {
        val gate = CompletableDeferred<Unit>()
        val started = CompletableDeferred<Unit>()
        locate = { if (locationCalls == 2) started.complete(Unit); gate.await(); fix }
        val service = enricher()
        val ids = (1..4).map { repository.create("queue $it") }
        val first = ids.take(2).map { id -> launch { service.enrich(id) } }
        started.await()
        val cancelled = launch(start = kotlinx.coroutines.CoroutineStart.UNDISPATCHED) { service.enrich(ids[2]) }
        cancelled.cancel(); cancelled.join()
        val next = launch { service.enrich(ids[3]) }
        gate.complete(Unit)
        first.forEach { it.join() }; next.join()
        assertNull(repository.find(ids[2])!!.weatherAt)
        assertNotNull(repository.find(ids[3])!!.weatherAt)
        assertEquals(3, locationCalls)
    }

    @Test fun platformPermissionAndMunicipalityOnlyExtraction() = runTest {
        try {
            AndroidLocationSource(context).locate()
            fail("permission should be required")
        } catch (failure: MetadataFailure) { assertEquals(MetadataProblem.PERMISSION, failure.problem) }
        val address = Address(Locale.JAPAN).apply {
            adminArea = "沖縄県"
            locality = "糸満市"
            thoroughfare = "保存しない通り"
            featureName = "保存しない建物"
            setAddressLine(0, "保存しない住所")
        }
        assertEquals("沖縄県 糸満市", AndroidPlaceSource.municipality(address))
        address.locality = null
        assertNull(AndroidPlaceSource.municipality(address))
    }

    @Test fun settingsDefaultOffAndPersistChoice() {
        context.getSharedPreferences("metadata", 0).edit().clear().commit()
        val settings = MetadataSettings(context)
        assertFalse(settings.enabled.value)
        settings.setEnabled(true)
        settings.permissionAsked = true
        val reopened = MetadataSettings(context)
        assertTrue(reopened.enabled.value)
        assertTrue(reopened.permissionAsked)
        reopened.setEnabled(false)
        assertFalse(MetadataSettings(context).enabled.value)
    }

    @Test fun versionOneMigrationPreservesEveryExistingFieldAndOrder() = runTest {
        val filename = "migration-test.db"
        context.deleteDatabase(filename)
        val schema = javaClass.classLoader!!.getResourceAsStream("com.yantkys.memonow.data.MemonowDatabase/1.json")!!.bufferedReader().use { JSONObject(it.readText()).getJSONObject("database") }
        val file = context.getDatabasePath(filename)
        file.parentFile!!.mkdirs()
        SQLiteDatabase.openOrCreateDatabase(file, null).use { old ->
            val entity = schema.getJSONArray("entities").getJSONObject(0)
            old.execSQL(entity.getString("createSql").replace("\${TABLE_NAME}", "posts"))
            val indexes = entity.getJSONArray("indices")
            for (i in 0 until indexes.length()) old.execSQL(indexes.getJSONObject(i).getString("createSql").replace("\${TABLE_NAME}", "posts"))
            val setup = schema.getJSONArray("setupQueries")
            for (i in 0 until setup.length()) old.execSQL(setup.getString(i))
            old.execSQL("INSERT INTO posts(sequence,id,body,createdAt) VALUES (3,'a','日本語の旧投稿',1000),(7,'b','同時刻の旧投稿',1000)")
            old.version = 1
        }
        val migrated = Room.databaseBuilder(context, MemonowDatabase::class.java, filename)
            .addMigrations(MemonowDatabase.MIGRATION_1_2).build()
        try {
            val posts = migrated.posts().observeAll().first()
            assertEquals(listOf("a", "b"), posts.map { it.id })
            assertEquals(listOf(3L, 7L), posts.map { it.sequence })
            assertEquals(listOf("日本語の旧投稿", "同時刻の旧投稿"), posts.map { it.body })
            assertTrue(posts.all { it.createdAt == 1000L && it.placeName == null && it.weatherCode == null && it.temperatureC == null && it.weatherAt == null })
            val id = PostRepository(migrated.posts(), clock).create("移行後の新規投稿")
            assertTrue(migrated.posts().find(id)!!.sequence > 7)
        } finally {
            migrated.close()
            context.deleteDatabase(filename)
        }
    }
}
