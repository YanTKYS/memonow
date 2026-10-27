package com.yantkys.memonow.metadata

import com.yantkys.memonow.data.PostRepository
import java.time.Clock
import kotlin.math.abs
import kotlin.math.round
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.delay
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow

// Coordinates are transient: never part of a Room entity or persisted work request.
data class LocationFix(val latitude: Double, val longitude: Double, val ageMillis: Long, val accuracyMeters: Float, val diagnostic: LocationDiagnostic? = null)
class CoarsePoint private constructor(val latitude: Double, val longitude: Double) {
    companion object {
        fun from(fix: LocationFix): CoarsePoint? {
            if (fix.problem() != null) return null
            return CoarsePoint(round(fix.latitude * 100) / 100, round(fix.longitude * 100) / 100)
        }
    }
}
data class Weather(val code: Int?, val temperatureC: Double?, val at: Long)
fun interface LocationSource { suspend fun locate(): LocationFix? }
fun interface PlaceSource { suspend fun name(point: CoarsePoint): String? }
fun interface WeatherSource { suspend fun current(point: CoarsePoint): Weather? }

fun LocationFix.problem(): MetadataProblem? = when {
    ageMillis !in 0..120_000 -> MetadataProblem.LOCATION_STALE
    !accuracyMeters.isFinite() || accuracyMeters <= 0 || accuracyMeters > 10_000 -> MetadataProblem.LOCATION_ACCURACY
    !latitude.isFinite() || !longitude.isFinite() || latitude !in -90.0..90.0 || longitude !in -180.0..180.0 -> MetadataProblem.LOCATION_INVALID
    else -> null
}

class PostMetadata(
    private val repository: PostRepository,
    private val location: LocationSource,
    private val place: PlaceSource,
    private val weather: WeatherSource,
    private val allowed: () -> Boolean,
    private val clock: Clock = Clock.systemUTC(),
    private val blockedReason: () -> MetadataProblem = { MetadataProblem.OFF },
    private val elapsedMillis: () -> Long = { android.os.SystemClock.elapsedRealtime() },
) {
    private val slots = Semaphore(2)
    private val admitted = Semaphore(8) // Two running + at most six waiting; no persistent backlog.
    private var cancellationEpoch = 0L
    private var generation = 0L
    private val _diagnostic = MutableStateFlow(MetadataDiagnostic())
    val diagnostic = _diagnostic.asStateFlow()

    @Synchronized private fun begin(): Long {
        generation++
        _diagnostic.value = MetadataDiagnostic(MetadataStage.START)
        return generation
    }
    @Synchronized private fun report(run: Long, value: MetadataDiagnostic) {
        if (run == generation) _diagnostic.value = value
    }
    @Synchronized fun interrupt(reason: MetadataProblem) {
        cancellationEpoch++
        generation++ // Fence late results, including a completed operation from an older post.
        _diagnostic.value = _diagnostic.value.copy(stage = MetadataStage.STOPPED, stoppedAt = _diagnostic.value.stage, problem = reason)
    }

    suspend fun enrich(id: String) {
        val run = begin()
        val epoch = cancellationEpoch
        var state = MetadataDiagnostic(MetadataStage.START)
        fun stage(value: MetadataStage) { state = state.copy(stage = value); report(run, state) }
        fun stop(reason: MetadataProblem) {
            state = state.copy(stage = MetadataStage.STOPPED, stoppedAt = state.stage, problem = reason); report(run, state)
        }
        if (!allowed()) { stop(blockedReason()); return }
        if (!admitted.tryAcquire()) { stop(MetadataProblem.BUSY); return }
        var acquired = false
        try {
            val token = repository.metadataToken()
            val post = repository.find(id) ?: run { stop(MetadataProblem.POST_CHANGED); return }
            suspend fun active(): Boolean {
                currentCoroutineContext().ensureActive()
                if (!allowed()) { stop(blockedReason()); return false }
                if (epoch != cancellationEpoch) { stop(MetadataProblem.CANCELLED); return false }
                if (repository.metadataToken() != token || repository.find(id) == null) {
                    stop(MetadataProblem.POST_CHANGED); return false
                }
                if (clock.millis() - post.createdAt !in 0..120_000) { stop(MetadataProblem.EXPIRED); return false }
                return true
            }
            if (!active()) return
            stage(MetadataStage.QUEUED)
            val queued = withTimeoutOrNull(30_000) { slots.acquire(); acquired = true; true }
            if (queued != true) { stop(MetadataProblem.BUSY); return }
            val existing = repository.find(id) ?: run { stop(MetadataProblem.POST_CHANGED); return }
            state = state.copy(placeSaved = !existing.placeName.isNullOrBlank(), weatherSaved = existing.weatherAt != null)
            // Keep only the rounded point and its age for this post/job; never persist it.
            var point: CoarsePoint? = null
            var positionAgeAtFetch = 0L
            var positionFetchedAt = 0L
            suspend fun positionActive(): Boolean {
                if (!active()) return false
                if (point != null && elapsedMillis() - positionFetchedAt !in 0..(120_000 - positionAgeAtFetch)) {
                    stop(MetadataProblem.LOCATION_STALE); return false
                }
                return true
            }
            for (number in 1..2) {
                if (!positionActive()) return
                state = state.copy(attempt = number)
                if (state.placeSaved && state.weatherSaved) { stage(MetadataStage.DONE); return }
                if (point == null) {
                    stage(MetadataStage.LOCATION)
                    val fix = attempt(25_000, MetadataProblem.LOCATION_TIMEOUT, MetadataProblem.LOCATION_EMPTY) {
                        location.locate() ?: throw MetadataFailure(MetadataProblem.LOCATION_EMPTY)
                    }
                    state = state.copy(location = fix.location ?: fix.value?.diagnostic)
                    val positionProblem = fix.problem ?: fix.value!!.problem()
                    if (positionProblem != null) {
                        if (number == 1 && retryable(positionProblem)) {
                            state = state.copy(problem = positionProblem)
                            stage(MetadataStage.RETRY)
                            delay(2_000)
                            state = state.copy(problem = null)
                            continue
                        }
                        stop(positionProblem); return
                    }
                    point = CoarsePoint.from(fix.value!!) ?: run { stop(MetadataProblem.LOCATION_INVALID); return }
                    positionAgeAtFetch = fix.value.ageMillis
                    positionFetchedAt = elapsedMillis()
                }
                val currentPoint = point ?: return
                if (!positionActive()) return
                if (!state.placeSaved && (state.placeProblem == null || retryable(state.placeProblem))) {
                    stage(MetadataStage.PLACE)
                    val name = attempt(3_000, MetadataProblem.PLACE_TIMEOUT, MetadataProblem.PLACE_FAILED) {
                        place.name(currentPoint)?.takeIf { it.isNotBlank() } ?: throw MetadataFailure(MetadataProblem.PLACE_EMPTY)
                    }
                    state = state.copy(placeProblem = name.problem)
                    if (!positionActive()) return
                    if (name.value != null) {
                        stage(MetadataStage.SAVE)
                        if (repository.attachPlace(id, name.value, token) == 0) { stop(MetadataProblem.POST_CHANGED); return }
                        state = state.copy(placeSaved = true)
                    }
                }
                if (!positionActive()) return
                if (!state.weatherSaved && (state.weatherProblem == null || retryable(state.weatherProblem))) {
                    stage(MetadataStage.WEATHER)
                    val result = attempt(6_000, MetadataProblem.WEATHER_TIMEOUT, MetadataProblem.WEATHER_NETWORK) {
                        weather.current(currentPoint) ?: throw MetadataFailure(MetadataProblem.WEATHER_INVALID)
                    }
                    state = state.copy(weatherProblem = result.problem)
                    if (!positionActive()) return
                    val value = result.value
                    if (value != null) {
                        if (abs(value.at.toDouble() - post.createdAt) > 20 * 60_000) {
                            state = state.copy(weatherProblem = MetadataProblem.WEATHER_TIME)
                        } else if ((value.code == null && value.temperatureC == null) ||
                            (value.code != null && value.code !in 0..999) ||
                            (value.temperatureC != null && (!value.temperatureC.isFinite() || value.temperatureC !in -100.0..70.0))) {
                            state = state.copy(weatherProblem = MetadataProblem.WEATHER_INVALID)
                        } else {
                            stage(MetadataStage.SAVE)
                            if (repository.attachWeather(id, value.code, value.temperatureC, value.at, token) == 0) {
                                stop(MetadataProblem.POST_CHANGED); return
                            }
                            state = state.copy(weatherSaved = true)
                        }
                    }
                }
                val retry = (!state.placeSaved && retryable(state.placeProblem)) ||
                    (!state.weatherSaved && retryable(state.weatherProblem))
                if (number == 2 || !retry) { stage(MetadataStage.DONE); return }
                stage(MetadataStage.RETRY)
                delay(2_000)
            }
        } catch (cancelled: CancellationException) {
            stop(if (allowed()) MetadataProblem.CANCELLED else blockedReason())
            throw cancelled
        } catch (_: Exception) {
            stop(MetadataProblem.SAVE_FAILED)
        } finally {
            if (acquired) slots.release()
            admitted.release()
        }
    }

    private fun retryable(problem: MetadataProblem?) = problem in setOf(
        MetadataProblem.LOCATION_TIMEOUT, MetadataProblem.LOCATION_EMPTY, MetadataProblem.LOCATION_STALE,
        MetadataProblem.LOCATION_ACCURACY, MetadataProblem.PLACE_TIMEOUT, MetadataProblem.PLACE_FAILED,
        MetadataProblem.PLACE_EMPTY, MetadataProblem.WEATHER_TIMEOUT, MetadataProblem.WEATHER_NETWORK,
        MetadataProblem.WEATHER_UNAVAILABLE,
    )

    private data class Attempt<T>(val value: T? = null, val problem: MetadataProblem? = null, val location: LocationDiagnostic? = null)
    private suspend fun <T> attempt(timeout: Long, timedOut: MetadataProblem, failed: MetadataProblem, block: suspend () -> T): Attempt<T> = try {
        Attempt(value = withTimeout(timeout) { block() })
    } catch (_: TimeoutCancellationException) {
        currentCoroutineContext().ensureActive() // Do not swallow cancellation of the enclosing job.
        Attempt(problem = timedOut)
    } catch (cancelled: CancellationException) {
        throw cancelled
    } catch (failure: MetadataFailure) {
        Attempt(problem = failure.problem, location = failure.location)
    } catch (_: SecurityException) {
        Attempt(problem = MetadataProblem.PERMISSION)
    } catch (_: Exception) {
        Attempt(problem = failed)
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
