package com.yantkys.memonow

import android.app.Application
import com.yantkys.memonow.metadata.*
import kotlinx.coroutines.test.runTest
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28], application = Application::class)
class OpenMeteoTest {
    private val response = """{"utc_offset_seconds":0,"current_units":{"time":"unixtime","temperature_2m":"°C","weather_code":"wmo code"},"current":{"time":1792976400,"temperature_2m":25.5,"weather_code":3}}"""

    @Test fun requestContainsOnlyRoundedCoordinatesAndMinimalVariables() = runTest {
        val source = OpenMeteoWeather { url ->
            assertEquals("https://api.open-meteo.com/v1/forecast?latitude=26.12&longitude=127.68&current=temperature_2m,weather_code&temperature_unit=celsius&timeformat=unixtime&timezone=GMT", url)
            response
        }
        val weather = source.current(CoarsePoint.from(LocationFix(26.123456, 127.678912, 0))!!)!!
        assertEquals(3, weather.code)
        assertEquals(25.5, weather.temperatureC!!, 0.0)
        assertEquals(1792976400000, weather.at)
    }

    @Test fun missingValuesUnknownCodesAndInvalidUnitsAreNotInvented() {
        assertNull(OpenMeteoWeather.parse("{}"))
        assertNull(OpenMeteoWeather.parse(response.replace("\"unixtime\"", "\"iso8601\"")))
        val codeOnly = OpenMeteoWeather.parse(response.replace("25.5", "null"))!!
        assertNull(codeOnly.temperatureC)
        val invalidTemp = OpenMeteoWeather.parse(response.replace("25.5", "200"))!!
        assertNull(invalidTemp.temperatureC)
        val unknown = OpenMeteoWeather.parse(response.replace("\"weather_code\":3", "\"weather_code\":999"))!!
        assertNull(weatherLabel(unknown.code))
        assertEquals("雨", weatherLabel(61))
        assertNull(OpenMeteoWeather.parse(response.replace("1792976400", "1792976400.5")))
    }
}
