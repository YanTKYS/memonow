package com.yantkys.memonow.metadata

import android.content.Context
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException
import android.location.Address
import android.location.Geocoder
import java.util.Locale
import java.util.concurrent.ArrayBlockingQueue
import java.util.concurrent.ThreadPoolExecutor
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.suspendCancellableCoroutine

class AndroidPlaceSource(context: Context) : PlaceSource {
    private val geocoder = Geocoder(context, Locale.getDefault())

    @Suppress("DEPRECATION")
    override suspend fun name(point: CoarsePoint): String? {
        if (!Geocoder.isPresent()) throw MetadataFailure(MetadataProblem.GEOCODER_UNSUPPORTED)
        // Legacy Geocoder may block in a system service. Limit workers and queued work;
        // cancellation releases the coroutine even if an OEM's Binder call ignores interruption.
        return suspendCancellableCoroutine { continuation ->
            val future = worker.submit {
                try {
                    val name = municipality(geocoder.getFromLocation(point.latitude, point.longitude, 1)?.firstOrNull())
                    if (continuation.isActive) continuation.resume(name)
                } catch (_: Exception) {
                    if (continuation.isActive) continuation.resumeWithException(MetadataFailure(MetadataProblem.PLACE_FAILED))
                }
            }
            continuation.invokeOnCancellation {
                future.cancel(true)
                worker.remove(future as Runnable)
            }
        }
    }

    companion object {
        private val worker = ThreadPoolExecutor(1, 1, 0, TimeUnit.MILLISECONDS, ArrayBlockingQueue(1)) { runnable ->
            Thread(runnable, "municipality-lookup").apply { isDaemon = true }
        }

        fun municipality(address: Address?): String? {
            address ?: return null
            val city = address.locality?.takeIf { it.isNotBlank() }
                ?: address.subAdminArea?.takeIf { it.isNotBlank() } ?: return null
            // Never use addressLine, street, featureName, or neighborhood-level subLocality.
            return listOfNotNull(address.adminArea?.takeIf { it.isNotBlank() }, city).distinct().joinToString(" ")
        }
    }
}
