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
class MetadataTest {
    private val context get() = ApplicationProvider.getApplicationContext<Application>()
    private lateinit var db: MemonowDatabase
    private lateinit var repository: PostRepository
    private val now = Instant.parse("2026-10-26T01:00:00Z").toEpochMilli()
    private val clock = Clock.fixed(Instant.ofEpochMilli(now), ZoneOffset.UTC)
    private var permitted = true
    private var locationCalls = 0
    private var weatherCalls = 0
    private val fix = LocationFix(26.123456, 127.678912, 1000)
    private var locate: suspend () -> LocationFix? = { fix }
    private var place: suspend (CoarsePoint) -> String? = { "沖縄県 糸満市" }
    private var weather: suspend (CoarsePoint) -> Weather? = { Weather(0, 25.5, now) }

    @Before fun setUp() {
        db = Room.inMemoryDatabaseBuilder(context, MemonowDatabase::class.java).build()
        repository = PostRepository(db.posts(), clock)
    }
    @After fun tearDown() { db.close() }
    private fun enricher() = PostMetadata(repository,
        LocationSource { locationCalls++; locate() }, PlaceSource { place(it) },
        WeatherSource { weatherCalls++; weather(it) }, { permitted }, clock)

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

    @Test fun platformPermissionAndMunicipalityOnlyExtraction() = runTest {
        assertNull(AndroidLocationSource(context).locate())
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
