package net.devemperor.sayova.core

import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import net.devemperor.sayova.core.weather.MetNorway
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import java.time.Instant
import java.time.ZoneId

class WeatherTest {
    private val zone = ZoneId.of("Europe/Berlin")

    /** One forecast step as MET Norway sends it (compact). */
    private fun step(time: String, temp: Double, symbol: String, rain: Double, pressure: Double = 1031.3) = """
        {"time": "$time", "data": {
          "instant": {"details": {"air_pressure_at_sea_level": $pressure, "air_temperature": $temp,
                                  "relative_humidity": 90.0, "wind_speed": 1.4}},
          "next_1_hours": {"summary": {"symbol_code": "$symbol"}, "details": {"precipitation_amount": $rain}}}}
    """

    private val body = """{"properties": {"timeseries": [
        ${step("2026-10-29T07:00:00Z", 3.0, "fog", 0.0)},
        ${step("2026-10-29T08:00:00Z", 4.9, "cloudy", 0.0, 1030.0)},
        ${step("2026-10-29T11:00:00Z", 11.2, "partlycloudy_day", 0.0)},
        ${step("2026-10-29T14:00:00Z", 12.6, "partlycloudy_day", 0.2)},
        ${step("2026-10-29T20:00:00Z", 6.1, "clearsky_night", 0.0)},
        ${step("2026-10-30T09:00:00Z", 8.0, "lightrainshowers_day", 0.5)},
        ${step("2026-10-30T12:00:00Z", 10.4, "lightrainshowers_day", 1.1)}
    ]}}"""

    @Test
    fun `the current hour, the rest of today and tomorrow`() {
        val report = MetNorway.parse(body, zone, Instant.parse("2026-10-29T08:20:00Z"))!!
        assertEquals(4.9, report.now.temperature, 0.0)
        assertEquals("cloudy", report.now.conditions)
        assertEquals(1030.0, report.seaLevelPressure!!, 0.0)
        // Today starts at the current hour: the 3 °C fog before it no longer counts.
        val today = report.days[0]
        assertEquals(4.9, today.min, 0.0)
        assertEquals(12.6, today.max, 0.0)
        assertEquals("partly cloudy", today.conditions)
        assertEquals(0.2, today.precipitation, 1e-9)
        assertEquals("light rain showers", report.days[1].conditions)
        assertEquals(1.6, report.days[1].precipitation, 1e-9)

        val forModel = MetNorway.toJson(report, "Giessen")
        assertEquals(13, forModel["rest_of_today"]!!.jsonObject["max_c"]!!.jsonPrimitive.int)
        assertEquals("MET Norway", forModel["source"]!!.jsonPrimitive.content)
    }

    @Test
    fun `symbol codes become words, unknown ones stay as they are`() {
        assertEquals("clear sky", MetNorway.conditions("clearsky_day"))
        assertEquals("heavy rain and thunder", MetNorway.conditions("heavyrainandthunder"))
        assertEquals("something", MetNorway.conditions("something_night"))
    }

    @Test
    fun `coordinates are rounded to a kilometre and a broken answer is no forecast`() {
        assertEquals("https://api.met.no/weatherapi/locationforecast/2.0/compact?lat=50.58&lon=8.68", MetNorway.url(50.58412, 8.67839))
        assertNull(MetNorway.parse("{\"error\": true}", zone, Instant.now()))
    }
}
