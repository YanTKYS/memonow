package com.yantkys.memonow

import android.Manifest
import android.app.Application
import android.location.Location
import android.location.LocationManager
import android.os.CancellationSignal
import android.os.Looper
import android.os.SystemClock
import androidx.test.core.app.ApplicationProvider
import com.yantkys.memonow.metadata.*
import kotlinx.coroutines.async
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.advanceUntilIdle
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.annotation.Implements
import org.robolectric.annotation.Implementation
import org.robolectric.shadows.ShadowLocationManager
import java.util.concurrent.Executor
import java.util.function.Consumer
import org.robolectric.shadows.ShadowSystemClock
import java.time.Duration

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28, 31, 36], application = Application::class)
@OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
class AndroidLocationTest {
    private val context get() = ApplicationProvider.getApplicationContext<Application>()
    private val manager get() = context.getSystemService(LocationManager::class.java)
    private val shadow get() = shadowOf(manager)
    private val source get() = AndroidLocationSource(context)
    private val network = LocationManager.NETWORK_PROVIDER

    @Before fun setUp() {
        shadowOf(context).grantPermissions(Manifest.permission.ACCESS_COARSE_LOCATION)
        shadow.setLocationEnabled(true)
        shadow.setProviderEnabled(network, true)
        shadow.removeProvider("fused")
        ShadowSystemClock.advanceBy(Duration.ofMinutes(5))
    }
    private fun fix(age: Long = 1_000, accuracyMeters: Float = 500f) = Location(network).apply {
        latitude = 26.123456; longitude = 127.678912
        accuracy = accuracyMeters
        elapsedRealtimeNanos = SystemClock.elapsedRealtimeNanos() - age * 1_000_000
    }
    @Test fun freshCoarseCacheWorksWithoutStartingListener() = runTest {
        shadow.setLastKnownLocation(network, fix())
        assertEquals(26.123456, source.locate().latitude, 0.0)
        assertTrue(shadow.getLocationUpdateListeners(network).isEmpty())
    }
    @Test fun staleCacheRequestsNewPositionAndRemovesListener() = runTest {
        shadow.setLastKnownLocation(network, fix(121_000))
        val pending = async { source.locate() }
        runCurrent()
        assertFalse(pending.isCompleted)
        shadow.simulateLocation(network, fix())
        shadowOf(Looper.getMainLooper()).idle()
        runCurrent()
        assertEquals(127.678912, pending.await().longitude, 0.0)
        assertTrue(shadow.getLocationUpdateListeners(network).isEmpty())
    }
    @Test fun cancellationAndTimeoutRemovePlatformRequests() = runTest {
        val pending = async { source.locate() }
        runCurrent()
        pending.cancel(); runCurrent()
        assertTrue(shadow.getLocationUpdateListeners(network).isEmpty())
        val timeout = async { runCatching { source.locate() } }
        advanceUntilIdle()
        assertEquals(MetadataProblem.LOCATION_TIMEOUT, (timeout.await().exceptionOrNull() as MetadataFailure).problem)
        assertTrue(shadow.getLocationUpdateListeners(network).isEmpty())
    }
    @Test fun permissionLocationOffAndNoCoarseProviderAreDistinct() = runTest {
        shadowOf(context).denyPermissions(Manifest.permission.ACCESS_COARSE_LOCATION)
        assertEquals(MetadataProblem.PERMISSION, (runCatching { source.locate() }.exceptionOrNull() as MetadataFailure).problem)
        shadowOf(context).grantPermissions(Manifest.permission.ACCESS_COARSE_LOCATION)
        shadow.setLocationEnabled(false)
        assertEquals(MetadataProblem.LOCATION_OFF, (runCatching { source.locate() }.exceptionOrNull() as MetadataFailure).problem)
        shadow.setLocationEnabled(true)
        shadow.setProviderEnabled(network, false)
        // GPS can be enabled without becoming a candidate for this coarse-only app.
        shadow.setProviderEnabled(LocationManager.GPS_PROVIDER, true)
        assertEquals(MetadataProblem.PROVIDER, (runCatching { source.locate() }.exceptionOrNull() as MetadataFailure).problem)
    }
    @Test fun inaccurateCacheCannotBeUsedAndFutureTimestampIsRejected() = runTest {
        shadow.setLastKnownLocation(network, fix(121_000, 20_000f))
        val pending = async { runCatching { source.locate() } }
        runCurrent()
        shadow.simulateLocation(network, fix(1_000, 20_000f))
        shadowOf(Looper.getMainLooper()).idle(); runCurrent()
        assertEquals(MetadataProblem.LOCATION_ACCURACY, (pending.await().exceptionOrNull() as MetadataFailure).problem)
        shadow.setLastKnownLocation(network, null)
        val future = async { runCatching { source.locate() } }
        runCurrent()
        shadow.simulateLocation(network, fix(-10_000))
        shadowOf(Looper.getMainLooper()).idle(); runCurrent()
        assertEquals(MetadataProblem.LOCATION_STALE, (future.await().exceptionOrNull() as MetadataFailure).problem)
    }
    @Test @Config(sdk = [31, 36]) fun stalledFusedRequestFallsBackToNetworkWithinDeadline() = runTest {
        shadow.setProviderEnabled("fused", true)
        val pending = async { source.locate() }
        runCurrent()
        assertFalse(shadow.getLocationUpdateListeners("fused").isEmpty())
        assertFalse(shadow.getLocationUpdateListeners(network).isEmpty())
        shadow.simulateLocation(network, fix())
        shadowOf(Looper.getMainLooper()).idle(); runCurrent()
        assertEquals(26.123456, pending.await().latitude, 0.0)
        assertTrue(shadow.getLocationUpdateListeners(network).isEmpty())
        assertTrue(shadow.getLocationUpdateListeners("fused").isEmpty())
        assertEquals(0, testScheduler.currentTime)
        val diagnostic = pending.await().diagnostic!!
        assertEquals(LocationResult.CANCELLED, diagnostic.candidates.first { it.provider == LocationProvider.FUSED }.result)
        assertEquals(LocationResult.ACQUIRED, diagnostic.candidates.first { it.provider == LocationProvider.NETWORK }.result)
    }
    @Test @Config(sdk = [31, 36]) fun bothProvidersTimeOutTogetherAndCancelTogether() = runTest {
        shadow.setProviderEnabled("fused", true)
        val pending = async { runCatching { source.locate() } }
        runCurrent()
        advanceUntilIdle()
        val failure = pending.await().exceptionOrNull() as MetadataFailure
        assertEquals(MetadataProblem.LOCATION_TIMEOUT, failure.problem)
        assertEquals(10_000, testScheduler.currentTime)
        assertTrue(failure.location!!.candidates.all { it.result == LocationResult.TIMEOUT })
        assertTrue(shadow.getLocationUpdateListeners(network).isEmpty())
        assertTrue(shadow.getLocationUpdateListeners("fused").isEmpty())
        val cancel = async { source.locate() }
        runCurrent(); cancel.cancel(); cancel.join()
        assertTrue(shadow.getLocationUpdateListeners(network).isEmpty())
        assertTrue(shadow.getLocationUpdateListeners("fused").isEmpty())
    }

    @Test @Config(sdk = [31, 36]) fun invalidFusedResponseDoesNotStopNetworkCandidate() = runTest {
        shadow.setProviderEnabled("fused", true)
        shadow.setLastKnownLocation(network, fix(121_000))
        val pending = async { source.locate() }
        runCurrent()
        shadow.simulateLocation("fused", fix(1_000, 20_000f))
        shadowOf(Looper.getMainLooper()).idle(); runCurrent()
        assertFalse(pending.isCompleted)
        shadow.simulateLocation(network, fix())
        shadowOf(Looper.getMainLooper()).idle(); runCurrent()
        val diagnostic = pending.await().diagnostic!!
        assertEquals(LocationCache.STALE, diagnostic.candidates.first { it.provider == LocationProvider.NETWORK }.cache)
        assertEquals(LocationResult.ACCURACY, diagnostic.candidates.first { it.provider == LocationProvider.FUSED }.result)
        assertEquals(LocationResult.ACQUIRED, diagnostic.candidates.first { it.provider == LocationProvider.NETWORK }.result)
    }

    @Test @Config(sdk = [31, 36], shadows = [NullFusedLocationManager::class])
    fun nullFusedResponseStillAllowsNetworkWithoutWaiting() = runTest {
        shadow.setProviderEnabled("fused", true)
        val pending = async { source.locate() }
        runCurrent(); shadowOf(Looper.getMainLooper()).idle(); runCurrent()
        assertFalse(pending.isCompleted)
        shadow.simulateLocation(network, fix())
        shadowOf(Looper.getMainLooper()).idle(); runCurrent()
        val diagnostic = pending.await().diagnostic!!
        assertEquals(LocationResult.EMPTY, diagnostic.candidates.first { it.provider == LocationProvider.FUSED }.result)
        assertEquals(LocationResult.ACQUIRED, diagnostic.candidates.first { it.provider == LocationProvider.NETWORK }.result)
        assertEquals(0, testScheduler.currentTime)
        assertTrue(shadow.getLocationUpdateListeners(network).isEmpty())
    }

    @Test @Config(sdk = [31, 36]) fun networkRaceWinnerReachesPlaceWeatherAndDatabase() = runTest {
        shadow.setProviderEnabled("fused", true)
        val clock = java.time.Clock.fixed(java.time.Instant.parse("2026-10-27T01:00:00Z"), java.time.ZoneOffset.UTC)
        val db = androidx.room.Room.inMemoryDatabaseBuilder(context, com.yantkys.memonow.data.MemonowDatabase::class.java).build()
        try {
            val repository = com.yantkys.memonow.data.PostRepository(db.posts(), clock)
            val entered = kotlinx.coroutines.CompletableDeferred<Unit>()
            var weatherCalls = 0
            val service = PostMetadata(repository, LocationSource { entered.complete(Unit); source.locate() },
                PlaceSource { assertEquals(26.12, it.latitude, 0.0); "市" },
                WeatherSource { weatherCalls++; Weather(0, 25.5, clock.millis()) }, { true }, clock)
            val id = repository.create("先に保存する本文")
            val pending = async { service.enrich(id) }
            entered.await(); runCurrent()
            shadow.simulateLocation(network, fix())
            shadowOf(Looper.getMainLooper()).idle(); runCurrent()
            pending.await()
            val post = repository.find(id)!!
            assertEquals("先に保存する本文", post.body)
            assertEquals("市", post.placeName)
            assertEquals(25.5, post.temperatureC!!, 0.0)
            assertEquals(1, weatherCalls)
            assertNotNull(service.diagnostic.value.location)
            assertTrue(shadow.getLocationUpdateListeners("fused").isEmpty())
        } finally { db.close() }
    }

    @Test @Config(sdk = [31, 36]) fun losingProviderDisabledDuringRaceDoesNotPreventOtherSuccess() = runTest {
        shadow.setProviderEnabled("fused", true)
        val pending = async { source.locate() }
        runCurrent()
        shadow.setProviderEnabled("fused", false)
        shadowOf(Looper.getMainLooper()).idle(); runCurrent()
        shadow.simulateLocation(network, fix())
        shadowOf(Looper.getMainLooper()).idle(); runCurrent()
        assertEquals(26.123456, pending.await().latitude, 0.0)
        assertTrue(shadow.getLocationUpdateListeners(network).isEmpty())
        assertTrue(shadow.getLocationUpdateListeners("fused").isEmpty())
    }

    @Test @Config(sdk = [31, 36]) fun freshButInaccurateCacheIsNotAdoptedOverValidNetworkResponse() = runTest {
        shadow.setProviderEnabled("fused", true)
        shadow.setLastKnownLocation("fused", fix(1_000, 20_000f))
        val pending = async { source.locate() }
        runCurrent(); shadowOf(Looper.getMainLooper()).idle(); runCurrent()
        assertFalse(pending.isCompleted)
        shadow.simulateLocation(network, fix())
        shadowOf(Looper.getMainLooper()).idle(); runCurrent()
        val fused = pending.await().diagnostic!!.candidates.first { it.provider == LocationProvider.FUSED }
        assertEquals(LocationCache.ACCURACY, fused.cache)
        assertEquals(LocationResult.ACCURACY, fused.result)
    }

    @Test @Config(sdk = [31, 36]) fun fusedProviderCanSupplyCoarseFixWithoutNetworkProvider() = runTest {
        shadow.setProviderEnabled(network, false)
        shadow.setProviderEnabled("fused", true)
        shadow.setLastKnownLocation("fused", fix())
        assertEquals(26.123456, source.locate().latitude, 0.0)
    }
}

@Implements(LocationManager::class)
class NullFusedLocationManager : ShadowLocationManager() {
    @Implementation
    public override fun getCurrentLocation(provider: String, signal: CancellationSignal?, executor: Executor, consumer: Consumer<Location?>) {
        if (provider == LocationManager.FUSED_PROVIDER) executor.execute { consumer.accept(null) }
        else super.getCurrentLocation(provider, signal, executor, consumer)
    }
}
