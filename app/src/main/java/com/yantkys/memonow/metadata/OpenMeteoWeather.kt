package com.yantkys.memonow.metadata

import java.net.URL
import java.io.ByteArrayOutputStream
import javax.net.ssl.HttpsURLConnection
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import org.json.JSONObject

class OpenMeteoWeather(
    private val fetch: suspend (String) -> String = { url -> download(url) },
) : WeatherSource {
    override suspend fun current(point: CoarsePoint): Weather? {
        val url = "https://api.open-meteo.com/v1/forecast?latitude=${point.latitude}&longitude=${point.longitude}" +
            "&current=temperature_2m,weather_code&temperature_unit=celsius&timeformat=unixtime&timezone=GMT"
        val body = fetch(url)
        return try {
            parse(body) ?: throw MetadataFailure(MetadataProblem.WEATHER_INVALID)
        } catch (_: org.json.JSONException) {
            throw MetadataFailure(MetadataProblem.WEATHER_INVALID)
        }
    }

    companion object {
        fun parse(body: String): Weather? {
            val root = JSONObject(body)
            if (root.optBoolean("error", false) || root.optInt("utc_offset_seconds", Int.MIN_VALUE) != 0) return null
            val current = root.optJSONObject("current") ?: return null
            val units = root.optJSONObject("current_units") ?: return null
            if (units.optString("time") != "unixtime") return null
            val seconds = (current.opt("time") as? Number)?.toDouble() ?: return null
            if (!seconds.isFinite() || seconds % 1 != 0.0 || seconds !in 0.0..10_000_000_000.0) return null
            val temperature = (current.opt("temperature_2m") as? Number)?.toDouble()?.takeIf {
                units.optString("temperature_2m") == "°C" && it.isFinite() && it in -100.0..70.0
            }
            val code = (current.opt("weather_code") as? Number)?.toDouble()?.takeIf {
                units.optString("weather_code") == "wmo code" && it.isFinite() && it % 1 == 0.0 && it in 0.0..999.0
            }?.toInt()
            return if (temperature == null && code == null) null else Weather(code, temperature, seconds.toLong() * 1000)
        }

        private suspend fun download(url: String): String = withContext(Dispatchers.IO) {
            val connection = URL(url).openConnection() as HttpsURLConnection
            try {
                connection.connectTimeout = 3_000
                connection.readTimeout = 3_000
                connection.instanceFollowRedirects = false
                connection.setRequestProperty("Accept", "application/json")
                if (connection.responseCode != 200) throw MetadataFailure(MetadataProblem.WEATHER_HTTP)
                connection.inputStream.use { input ->
                    val output = ByteArrayOutputStream()
                    val buffer = ByteArray(4096)
                    while (true) {
                        currentCoroutineContext().ensureActive()
                        val count = input.read(buffer)
                        if (count < 0) break
                        if (output.size() + count > 32_768) throw MetadataFailure(MetadataProblem.WEATHER_INVALID)
                        output.write(buffer, 0, count)
                    }
                    output.toString("UTF-8")
                }
            } finally {
                connection.disconnect()
            }
        }
    }
}
