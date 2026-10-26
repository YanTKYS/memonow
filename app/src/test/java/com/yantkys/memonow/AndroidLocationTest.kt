package com.yantkys.memonow

import android.Manifest
import android.app.Application
import android.location.Location
import android.location.LocationManager
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
import org.robolectric.shadows.ShadowSystemClock
import java.time.Duration

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28, 31], application = Application::class)
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
    @Test @Config(sdk = [31]) fun stalledFusedRequestFallsBackToNetworkWithinDeadline() = runTest {
        shadow.setProviderEnabled("fused", true)
        val pending = async { source.locate() }
        runCurrent()
        testScheduler.advanceTimeBy(10_000)
        runCurrent()
        assertTrue(shadow.getLocationUpdateListeners("fused").isEmpty())
        shadow.simulateLocation(network, fix())
        shadowOf(Looper.getMainLooper()).idle(); runCurrent()
        assertEquals(26.123456, pending.await().latitude, 0.0)
        assertTrue(shadow.getLocationUpdateListeners(network).isEmpty())
    }
    @Test @Config(sdk = [31]) fun fusedProviderCanSupplyCoarseFixWithoutNetworkProvider() = runTest {
        shadow.setProviderEnabled(network, false)
        shadow.setProviderEnabled("fused", true)
        shadow.setLastKnownLocation("fused", fix())
        assertEquals(26.123456, source.locate().latitude, 0.0)
    }
}
