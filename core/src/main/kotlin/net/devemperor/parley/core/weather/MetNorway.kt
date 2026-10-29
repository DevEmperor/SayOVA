package net.devemperor.parley.core.weather

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.doubleOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonObject
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.util.Locale
import kotlin.math.roundToInt

/**
 * The weather from MET Norway's Locationforecast 2.0 ("compact"), boiled down to what a spoken answer
 * needs. MET Norway's data is free also for paid apps under CC BY 4.0 (Open-Meteo's free tier is
 * non-commercial only); its terms ask for an identifying user agent and credit, which the app's
 * licence screen gives. Read 2026-10-29 from api.met.no/doc/TermsOfService.
 */
object MetNorway {
    const val USER_AGENT = "Parley/1.0 github.com/DevEmperor/Parley contact@devemperor.net"

    /** Two decimals, about a kilometre: plenty for the weather, kinder to privacy and to MET's cache. */
    fun url(latitude: Double, longitude: Double): String =
        "https://api.met.no/weatherapi/locationforecast/2.0/compact?lat=%.2f&lon=%.2f".format(Locale.ROOT, latitude, longitude)

    data class Now(
        val temperature: Double,
        val conditions: String?,
        val windSpeed: Double?,
        val humidity: Double?,
        val precipitationNextHour: Double?,
    )

    data class Day(val date: LocalDate, val min: Double, val max: Double, val conditions: String?, val precipitation: Double)

    data class Report(val now: Now, val days: List<Day>, val seaLevelPressure: Double?)

    private val json = Json { ignoreUnknownKeys = true }

    /** Now, today and tomorrow in [zone]; null when the answer holds no forecast. */
    fun parse(body: String, zone: ZoneId, now: Instant): Report? {
        val series = runCatching { json.parseToJsonElement(body).jsonObject["properties"]!!.jsonObject["timeseries"]!!.jsonArray }
            .getOrNull() ?: return null
        val steps = series.mapNotNull { element ->
            val step = element.jsonObject
            val time = runCatching { Instant.parse(step["time"]!!.jsonPrimitive.content) }.getOrNull() ?: return@mapNotNull null
            val data = step["data"]?.jsonObject ?: return@mapNotNull null
            val instant = data["instant"]?.jsonObject?.get("details")?.jsonObject ?: return@mapNotNull null
            val nextHour = data["next_1_hours"]?.jsonObject
            Step(
                time = time,
                temperature = instant["air_temperature"]?.jsonPrimitive?.doubleOrNull ?: return@mapNotNull null,
                windSpeed = instant["wind_speed"]?.jsonPrimitive?.doubleOrNull,
                humidity = instant["relative_humidity"]?.jsonPrimitive?.doubleOrNull,
                pressure = instant["air_pressure_at_sea_level"]?.jsonPrimitive?.doubleOrNull,
                symbol = (nextHour ?: data["next_6_hours"]?.jsonObject)?.get("summary")?.jsonObject?.get("symbol_code")?.jsonPrimitive?.content,
                precipitation = nextHour?.get("details")?.jsonObject?.get("precipitation_amount")?.jsonPrimitive?.doubleOrNull,
            )
        }
        // The current hour's step, or the first one when the forecast starts later.
        val current = steps.lastOrNull { !it.time.isAfter(now) } ?: steps.firstOrNull() ?: return null
        val today = now.atZone(zone).toLocalDate()
        val days = listOf(today, today.plusDays(1)).mapNotNull { date ->
            val hours = steps.filter { it.time.atZone(zone).toLocalDate() == date && (date != today || !it.time.isBefore(current.time)) }
            if (hours.isEmpty()) return@mapNotNull null
            // What the day looks like: the most common sky of its daytime hours.
            val daytime = hours.filter { it.time.atZone(zone).hour in 8..20 }.ifEmpty { hours }
            Day(
                date = date,
                min = hours.minOf { it.temperature },
                max = hours.maxOf { it.temperature },
                conditions = daytime.mapNotNull { it.symbol?.let(::conditions) }.groupingBy { it }.eachCount().maxByOrNull { it.value }?.key,
                precipitation = hours.sumOf { it.precipitation ?: 0.0 },
            )
        }
        return Report(
            now = Now(current.temperature, current.symbol?.let(::conditions), current.windSpeed, current.humidity, current.precipitation),
            days = days,
            seaLevelPressure = current.pressure,
        )
    }

    /** "lightrainshowers_day" → "light rain showers": MET's symbol codes as words the model can say. */
    fun conditions(symbol: String): String {
        val code = symbol.substringBefore('_')
        val words = WORDS.findAll(code).map { it.value }.toList()
        // Only when the words make up the whole code; anything new from MET stays as it came.
        if (words.joinToString("") != code) return code
        return words.joinToString(" ").replace("clearsky", "clear sky").replace("partlycloudy", "partly cloudy")
    }

    /** For the model: rounded, labelled with units, today and tomorrow named. */
    fun toJson(report: Report, place: String?): JsonObject = buildJsonObject {
        place?.let { put("place", it) }
        putJsonObject("now") {
            put("temperature_c", report.now.temperature.roundToInt())
            report.now.conditions?.let { put("conditions", it) }
            report.now.windSpeed?.let { put("wind_m_per_s", (it * 10).roundToInt() / 10.0) }
            report.now.humidity?.let { put("humidity_percent", it.roundToInt()) }
            report.now.precipitationNextHour?.let { put("precipitation_next_hour_mm", it) }
        }
        report.days.forEachIndexed { index, day ->
            putJsonObject(if (index == 0) "rest_of_today" else "tomorrow") {
                put("date", day.date.toString())
                put("min_c", day.min.roundToInt())
                put("max_c", day.max.roundToInt())
                day.conditions?.let { put("conditions", it) }
                put("precipitation_mm", (day.precipitation * 10).roundToInt() / 10.0)
            }
        }
        put("source", "MET Norway")
    }

    private class Step(
        val time: Instant,
        val temperature: Double,
        val windSpeed: Double?,
        val humidity: Double?,
        val pressure: Double?,
        val symbol: String?,
        val precipitation: Double?,
    )

    private val WORDS = Regex("clearsky|partlycloudy|fair|cloudy|fog|light|heavy|rain|sleet|snow|showers|and|thunder")
}
