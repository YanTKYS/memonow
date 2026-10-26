package com.yantkys.memonow.metadata

import com.yantkys.memonow.data.PostRepository
import java.time.Clock
import kotlin.math.abs
import kotlin.math.round
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.withTimeout

// Coordinates are transient: never part of a Room entity or persisted work request.
data class LocationFix(val latitude: Double, val longitude: Double, val ageMillis: Long)
class CoarsePoint private constructor(val latitude: Double, val longitude: Double) {
    companion object {
        fun from(fix: LocationFix): CoarsePoint? {
            if (fix.ageMillis !in 0..120_000 || !fix.latitude.isFinite() || !fix.longitude.isFinite() ||
                fix.latitude !in -90.0..90.0 || fix.longitude !in -180.0..180.0
            ) return null
            return CoarsePoint(round(fix.latitude * 100) / 100, round(fix.longitude * 100) / 100)
        }
    }
}
data class Weather(val code: Int?, val temperatureC: Double?, val at: Long)
fun interface LocationSource { suspend fun locate(): LocationFix? }
fun interface PlaceSource { suspend fun name(point: CoarsePoint): String? }
fun interface WeatherSource { suspend fun current(point: CoarsePoint): Weather? }

class PostMetadata(
    private val repository: PostRepository,
    private val location: LocationSource,
    private val place: PlaceSource,
    private val weather: WeatherSource,
    private val allowed: () -> Boolean,
    private val clock: Clock = Clock.systemUTC(),
) {
    private val slots = Semaphore(2)

    suspend fun enrich(id: String) {
        // Best effort only. Never queue old posts for a later "current" weather lookup.
        if (!allowed() || !slots.tryAcquire()) return
        try {
            val token = repository.metadataToken()
            val post = repository.find(id) ?: return
            fun active() = allowed() && clock.millis() - post.createdAt in 0..120_000
            if (!active()) return
            val fix = optional { withTimeout(6_000) { location.locate() } } ?: return
            val point = CoarsePoint.from(fix) ?: return
            if (!active()) return
            val name = optional { withTimeout(3_000) { place.name(point) } }
            if (!active()) return
            if (!name.isNullOrBlank()) repository.attachPlace(id, name, token)
            if (repository.find(id) == null || !active()) return
            val result = optional { withTimeout(6_000) { weather.current(point) } } ?: return
            // Open-Meteo current estimates use 15-minute steps; reject distant/future estimates.
            if (active() && abs(result.at.toDouble() - post.createdAt) <= 20 * 60_000 &&
                (result.code != null || result.temperatureC != null)
            ) {
                repository.attachWeather(id, result.code, result.temperatureC, result.at, token)
            }
        } finally {
            slots.release()
        }
    }

    private suspend fun <T> optional(block: suspend () -> T): T? = try {
        block()
    } catch (_: TimeoutCancellationException) {
        null
    } catch (cancelled: CancellationException) {
        throw cancelled
    } catch (_: Exception) {
        null
    }
}

fun weatherLabel(code: Int?): String? = when (code) {
    0 -> "快晴"
    1 -> "晴れ"
    2 -> "晴れ時々曇り"
    3 -> "曇り"
    45, 48 -> "霧"
    51, 53, 55 -> "霧雨"
    56, 57 -> "着氷性の霧雨"
    61, 63, 65 -> "雨"
    66, 67 -> "着氷性の雨"
    71, 73, 75, 77 -> "雪"
    80, 81, 82 -> "にわか雨"
    85, 86 -> "にわか雪"
    95, 96, 97, 99 -> "雷雨"
    else -> null
}
