package com.yantkys.memonow

import android.app.Application
import android.location.Address
import android.location.Geocoder
import androidx.test.core.app.ApplicationProvider
import com.yantkys.memonow.metadata.*
import java.util.Locale
import kotlinx.coroutines.async
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.Implementation
import org.robolectric.annotation.Implements

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33], application = Application::class, shadows = [HeldGeocoder::class])
@OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
class AndroidPlaceTest {
    @Before fun reset() { HeldGeocoder.listeners.clear() }
    private val point get() = CoarsePoint.from(LocationFix(26.12, 127.68, 0, 500f))!!
    private fun address() = Address(Locale.JAPAN).apply { adminArea = "県"; locality = "市" }

    @Test fun lateNativeCallbacksAreBoundedAndIgnoredAfterCancellation() = runTest {
        val source = AndroidPlaceSource(ApplicationProvider.getApplicationContext<Application>())
        val first = async { source.name(point) }
        val second = async { source.name(point) }
        runCurrent()
        assertEquals(2, HeldGeocoder.listeners.size)
        first.cancel(); second.cancel(); first.join(); second.join()
        val failure = runCatching { source.name(point) }.exceptionOrNull() as MetadataFailure
        assertEquals(MetadataProblem.PLACE_FAILED, failure.problem)
        assertEquals(2, HeldGeocoder.listeners.size)
        HeldGeocoder.listeners[0].onGeocode(mutableListOf(address()))
        HeldGeocoder.listeners[1].onError("must not enter diagnostics")
        val fresh = async { source.name(point) }
        runCurrent()
        assertEquals(3, HeldGeocoder.listeners.size)
        HeldGeocoder.listeners.last().onGeocode(mutableListOf(address()))
        assertEquals("県 市", fresh.await())
    }

    @Test fun asyncEmptyAndErrorResultsDoNotInventPlaceOrExposeErrorText() = runTest {
        val source = AndroidPlaceSource(ApplicationProvider.getApplicationContext<Application>())
        val empty = async { source.name(point) }
        runCurrent()
        HeldGeocoder.listeners.last().onGeocode(mutableListOf())
        assertNull(empty.await())
        val error = async { runCatching { source.name(point) } }
        runCurrent()
        HeldGeocoder.listeners.last().onError("private provider message")
        val failure = error.await().exceptionOrNull() as MetadataFailure
        assertEquals(MetadataProblem.PLACE_FAILED, failure.problem)
        assertFalse(failure.message!!.contains("private"))
    }
}

@Implements(Geocoder::class)
class HeldGeocoder {
    @Implementation
    fun getFromLocation(latitude: Double, longitude: Double, maxResults: Int, listener: Geocoder.GeocodeListener) {
        listeners.add(listener)
    }
    companion object {
        val listeners = mutableListOf<Geocoder.GeocodeListener>()
        @JvmStatic @Implementation fun isPresent() = true
    }
}
