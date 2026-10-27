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
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.launch
import kotlinx.coroutines.joinAll
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withTimeoutOrNull

class AndroidLocationSource(private val context: Context) : LocationSource {
    fun hasPermission() = context.checkSelfPermission(Manifest.permission.ACCESS_COARSE_LOCATION) == PackageManager.PERMISSION_GRANTED

    override suspend fun locate(): LocationFix {
        if (!hasPermission()) throw MetadataFailure(MetadataProblem.PERMISSION)
        val manager = context.getSystemService(LocationManager::class.java)
            ?: throw MetadataFailure(MetadataProblem.PROVIDER)
        val candidates = buildList {
            if (Build.VERSION.SDK_INT >= 31) add(LocationProvider.FUSED to LocationManager.FUSED_PROVIDER)
            add(LocationProvider.NETWORK to LocationManager.NETWORK_PROVIDER)
        }
        val caches = java.util.concurrent.ConcurrentHashMap<LocationProvider, LocationCache>()
        val results = java.util.concurrent.ConcurrentHashMap<LocationProvider, LocationResult>()
        candidates.forEach { (kind, _) -> caches[kind] = LocationCache.UNAVAILABLE; results[kind] = LocationResult.DISABLED }
        fun snapshot() = LocationDiagnostic(candidates.map { (kind, _) -> LocationCandidate(kind, caches.getValue(kind), results.getValue(kind)) })
        try {
            if (!LocationManagerCompat.isLocationEnabled(manager)) throw MetadataFailure(MetadataProblem.LOCATION_OFF, snapshot())
            val enabled = candidates.filter { (_, provider) ->
                try { provider in manager.allProviders && manager.isProviderEnabled(provider) }
                catch (_: IllegalArgumentException) { false }
            }
            if (enabled.isEmpty()) throw MetadataFailure(MetadataProblem.PROVIDER, snapshot())
            val cached = enabled.mapNotNull { (kind, provider) ->
                results[kind] = LocationResult.NOT_REQUESTED
                val fix = try { manager.getLastKnownLocation(provider)?.toFix() }
                    catch (_: IllegalArgumentException) { null }
                caches[kind] = when (fix?.problem()) {
                    null -> if (fix == null) LocationCache.EMPTY else LocationCache.FRESH
                    MetadataProblem.LOCATION_STALE -> LocationCache.STALE
                    MetadataProblem.LOCATION_ACCURACY -> LocationCache.ACCURACY
                    else -> LocationCache.INVALID
                }
                fix?.takeIf { it.problem() == null }?.let { kind to it }
            }.minByOrNull { it.second.ageMillis }
            if (cached != null) {
                results[cached.first] = LocationResult.CACHE
                return cached.second.copy(diagnostic = snapshot())
            }
            // Both coarse-capable OS providers start together. At most two requests per attempt;
            // a stalled fused provider cannot consume the network provider's opportunity.
            return coroutineScope {
                val completed = Channel<Pair<LocationFix?, MetadataProblem?>>(enabled.size)
                val jobs = enabled.map { (kind, provider) -> launch {
                    var problem: MetadataProblem? = null
                    var requestFailed = false
                    val fix = try {
                        withTimeoutOrNull(10_000) { request(manager, provider) }.also {
                            if (it == null) problem = unavailableReason(manager, provider)
                                .takeUnless { reason -> reason == MetadataProblem.LOCATION_EMPTY } ?: MetadataProblem.LOCATION_TIMEOUT
                        }
                    } catch (cancelled: CancellationException) {
                        results[kind] = LocationResult.CANCELLED
                        throw cancelled
                    } catch (error: MetadataFailure) {
                        problem = error.problem; null
                    } catch (_: SecurityException) {
                        problem = MetadataProblem.PERMISSION; null
                    } catch (_: IllegalArgumentException) {
                        problem = MetadataProblem.PROVIDER; null
                    } catch (_: Exception) {
                        requestFailed = true; problem = MetadataProblem.LOCATION_EMPTY; null
                    }
                    if (fix != null) problem = fix.problem()
                    results[kind] = if (requestFailed) LocationResult.ERROR else when (problem) {
                        null -> LocationResult.ACQUIRED
                        MetadataProblem.LOCATION_TIMEOUT -> LocationResult.TIMEOUT
                        MetadataProblem.LOCATION_EMPTY -> LocationResult.EMPTY
                        MetadataProblem.LOCATION_STALE -> LocationResult.STALE
                        MetadataProblem.LOCATION_ACCURACY -> LocationResult.ACCURACY
                        MetadataProblem.LOCATION_INVALID -> LocationResult.INVALID
                        MetadataProblem.PERMISSION -> LocationResult.PERMISSION
                        else -> LocationResult.UNAVAILABLE
                    }
                    completed.send(fix to problem)
                } }
                try {
                    val failures = mutableListOf<MetadataProblem>()
                    repeat(enabled.size) {
                        val (fix, problem) = completed.receive()
                        if (problem == null && fix != null) {
                            jobs.forEach { it.cancel() }; jobs.joinAll()
                            return@coroutineScope fix.copy(diagnostic = snapshot())
                        }
                        if (problem != null) failures.add(problem)
                    }
                    jobs.joinAll()
                    // Stable summary independent of callback order; candidate details retain all outcomes.
                    val failure = listOf(MetadataProblem.PERMISSION, MetadataProblem.LOCATION_OFF,
                        MetadataProblem.LOCATION_TIMEOUT, MetadataProblem.LOCATION_STALE,
                        MetadataProblem.LOCATION_ACCURACY, MetadataProblem.LOCATION_INVALID,
                        MetadataProblem.LOCATION_EMPTY, MetadataProblem.PROVIDER).first { it in failures }
                    throw MetadataFailure(failure, snapshot())
                } finally {
                    jobs.forEach { it.cancel() }
                    completed.cancel()
                }
            }
        } catch (_: SecurityException) {
            throw MetadataFailure(MetadataProblem.PERMISSION, snapshot())
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
    catch (_: IllegalArgumentException) { MetadataProblem.PROVIDER }

    private fun Location.toFix(): LocationFix {
        val elapsed = SystemClock.elapsedRealtimeNanos() - elapsedRealtimeNanos
        val age = if (elapsedRealtimeNanos <= 0 || elapsed < 0) -1 else elapsed / 1_000_000
        return LocationFix(latitude, longitude, age, if (hasAccuracy()) accuracy else Float.NaN)
    }
}
