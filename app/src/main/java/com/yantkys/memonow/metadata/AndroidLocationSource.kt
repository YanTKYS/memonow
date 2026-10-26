package com.yantkys.memonow.metadata

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.location.Location
import android.location.LocationListener
import android.location.LocationManager
import android.os.Build
import android.os.Bundle
import android.os.CancellationSignal
import android.os.Looper
import android.os.SystemClock
import androidx.annotation.RequiresPermission
import androidx.core.content.ContextCompat
import androidx.core.location.LocationManagerCompat
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withTimeoutOrNull

class AndroidLocationSource(private val context: Context) : LocationSource {
    fun hasPermission() = context.checkSelfPermission(Manifest.permission.ACCESS_COARSE_LOCATION) == PackageManager.PERMISSION_GRANTED

    override suspend fun locate(): LocationFix {
        if (!hasPermission()) throw MetadataFailure(MetadataProblem.PERMISSION)
        val manager = context.getSystemService(LocationManager::class.java)
            ?: throw MetadataFailure(MetadataProblem.PROVIDER)
        try {
            if (!LocationManagerCompat.isLocationEnabled(manager)) throw MetadataFailure(MetadataProblem.LOCATION_OFF)
            // Fused is a public coarse-capable provider from API 31. Never request GPS/fine permission.
            val providers = buildList {
                if (Build.VERSION.SDK_INT >= 31) add(LocationManager.FUSED_PROVIDER)
                add(LocationManager.NETWORK_PROVIDER)
            }.filter { it in manager.allProviders && manager.isProviderEnabled(it) }
            if (providers.isEmpty()) throw MetadataFailure(MetadataProblem.PROVIDER)
            providers.mapNotNull { provider -> manager.getLastKnownLocation(provider)?.toFix() }
                .filter { it.problem() == null }.minByOrNull { it.ageMillis }?.let { return it }
            var failure = MetadataProblem.LOCATION_TIMEOUT
            for (provider in providers) {
                // At most two 10-second attempts, inside the coordinator's 25-second deadline.
                val fix = try {
                    withTimeoutOrNull(10_000) { request(manager, provider) }
                } catch (error: MetadataFailure) {
                    failure = error.problem
                    continue
                } catch (_: IllegalArgumentException) {
                    failure = MetadataProblem.PROVIDER // Provider disappeared; try the next candidate.
                    continue
                }
                if (fix == null) {
                    failure = unavailableReason(manager, provider).takeUnless { it == MetadataProblem.LOCATION_EMPTY }
                        ?: MetadataProblem.LOCATION_TIMEOUT
                    continue
                }
                val invalid = fix.problem()
                if (invalid == null) return fix
                failure = invalid
            }
            throw MetadataFailure(failure)
        } catch (_: SecurityException) {
            throw MetadataFailure(MetadataProblem.PERMISSION)
        }
    }

    @Suppress("DEPRECATION", "OVERRIDE_DEPRECATION")
    @RequiresPermission(Manifest.permission.ACCESS_COARSE_LOCATION)
    private suspend fun request(manager: LocationManager, provider: String): LocationFix = suspendCancellableCoroutine { continuation ->
        if (Build.VERSION.SDK_INT >= 30) {
            val signal = CancellationSignal()
            continuation.invokeOnCancellation { signal.cancel() }
            try {
                manager.getCurrentLocation(provider, signal, ContextCompat.getMainExecutor(context)) { location ->
                    if (continuation.isActive) {
                        if (location == null) continuation.resumeWithException(MetadataFailure(unavailableReason(manager, provider)))
                        else continuation.resume(location.toFix())
                    }
                    signal.cancel()
                }
            } catch (error: Exception) {
                signal.cancel()
                if (continuation.isActive) continuation.resumeWithException(error)
            }
        } else {
            val completed = AtomicBoolean(false)
            val listener = object : LocationListener {
                override fun onLocationChanged(location: Location) {
                    manager.removeUpdates(this)
                    if (completed.compareAndSet(false, true)) continuation.resume(location.toFix())
                }
                override fun onProviderDisabled(provider: String) {
                    manager.removeUpdates(this)
                    if (completed.compareAndSet(false, true)) continuation.resumeWithException(MetadataFailure(unavailableReason(manager, provider)))
                }
                override fun onProviderEnabled(provider: String) = Unit
                override fun onStatusChanged(provider: String?, status: Int, extras: Bundle?) = Unit
            }
            continuation.invokeOnCancellation { completed.set(true); manager.removeUpdates(listener) }
            try {
                // Stop at the first callback; requestLocationUpdates also works on API 26–29.
                manager.requestLocationUpdates(provider, 0L, 0f, listener, Looper.getMainLooper())
                if (!continuation.isActive) manager.removeUpdates(listener)
            } catch (error: Exception) {
                manager.removeUpdates(listener)
                if (completed.compareAndSet(false, true)) continuation.resumeWithException(error)
            }
        }
    }

    private fun unavailableReason(manager: LocationManager, provider: String): MetadataProblem = try {
        when {
            !hasPermission() -> MetadataProblem.PERMISSION
            !LocationManagerCompat.isLocationEnabled(manager) -> MetadataProblem.LOCATION_OFF
            !manager.isProviderEnabled(provider) -> MetadataProblem.PROVIDER
            else -> MetadataProblem.LOCATION_EMPTY
        }
    } catch (_: SecurityException) { MetadataProblem.PERMISSION }

    private fun Location.toFix(): LocationFix {
        val elapsed = SystemClock.elapsedRealtimeNanos() - elapsedRealtimeNanos
        val age = if (elapsedRealtimeNanos <= 0 || elapsed < 0) -1 else elapsed / 1_000_000
        return LocationFix(latitude, longitude, age, if (hasAccuracy()) accuracy else Float.NaN)
    }
}
