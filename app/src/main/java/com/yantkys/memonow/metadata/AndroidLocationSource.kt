package com.yantkys.memonow.metadata

import android.Manifest
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.coroutines.resume
import android.content.Context
import android.content.pm.PackageManager
import android.location.Location
import android.location.LocationListener
import android.location.LocationManager
import android.os.Bundle
import android.os.Looper
import android.os.SystemClock
import androidx.annotation.RequiresPermission
import kotlinx.coroutines.suspendCancellableCoroutine

class AndroidLocationSource(private val context: Context) : LocationSource {
    fun hasPermission() = context.checkSelfPermission(Manifest.permission.ACCESS_COARSE_LOCATION) == PackageManager.PERMISSION_GRANTED

    @Suppress("DEPRECATION", "OVERRIDE_DEPRECATION")
    override suspend fun locate(): LocationFix? {
        if (!hasPermission()) return null
        val manager = context.getSystemService(LocationManager::class.java) ?: return null
        // NETWORK_PROVIDER works with coarse-only permission and does not request GPS fixes.
        return try {
            if (!manager.isProviderEnabled(LocationManager.NETWORK_PROVIDER)) null else request(manager)
        } catch (_: SecurityException) {
            null // Permission may be revoked after the initial check.
        }
    }

    @Suppress("DEPRECATION", "OVERRIDE_DEPRECATION")
    @RequiresPermission(Manifest.permission.ACCESS_COARSE_LOCATION)
    private suspend fun request(manager: LocationManager): LocationFix? = suspendCancellableCoroutine { continuation ->
        val completed = AtomicBoolean(false)
        val listener = object : LocationListener {
            override fun onLocationChanged(location: Location) {
                val age = if (location.elapsedRealtimeNanos <= 0) -1 else
                    (SystemClock.elapsedRealtimeNanos() - location.elapsedRealtimeNanos) / 1_000_000
                if (completed.compareAndSet(false, true)) continuation.resume(LocationFix(location.latitude, location.longitude, age))
                manager.removeUpdates(this)
            }
            override fun onProviderDisabled(provider: String) {
                if (completed.compareAndSet(false, true)) continuation.resume(null)
                manager.removeUpdates(this)
            }
            override fun onProviderEnabled(provider: String) = Unit
            override fun onStatusChanged(provider: String?, status: Int, extras: Bundle?) = Unit
        }
        continuation.invokeOnCancellation { completed.set(true); manager.removeUpdates(listener) }
        try {
            manager.requestSingleUpdate(LocationManager.NETWORK_PROVIDER, listener, Looper.getMainLooper())
            if (!continuation.isActive) manager.removeUpdates(listener)
        } catch (error: Exception) {
            manager.removeUpdates(listener)
            if (completed.compareAndSet(false, true)) continuation.resume(null)
        }
    }
}
