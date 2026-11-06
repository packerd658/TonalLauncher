package dev.tonal.launcher

import android.os.Handler
import android.os.Looper
import androidx.core.content.edit
import org.json.JSONArray
import org.json.JSONObject
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL
import java.util.concurrent.Executors

class Light(
    val id: String,
    val name: String,
    var state: String,
    var brightness: Int?,
    val modes: Set<String>,
    val hs: Pair<Float, Float>?,
    val kelvin: Int?,
    val minKelvin: Int,
    val maxKelvin: Int,
    var area: String? = null,
) {
    val on get() = state == "on"
    val available get() = state != "unavailable" && state != "unknown"
    val dimmable get() = modes.any { it != "onoff" }
    val supportsTemperature get() = "color_temp" in modes
    val supportsColor get() = modes.any { it in COLOR_MODES }

    /** 0..1, what the tile fill shows. Lights that can't dim read as full when on. */
    val level: Float
        get() = when {
            !on -> 0f
            !dimmable -> 1f
            else -> (brightness ?: 255) / 255f
        }

    private companion object {
        val COLOR_MODES = setOf("hs", "rgb", "rgbw", "rgbww", "xy")
    }
}

class Weather(val entityId: String, val condition: String, val temperature: Double?, val unit: String)

class HaException(val kind: Kind, message: String? = null) : Exception(message ?: kind.name) {
    enum class Kind { NOT_CONFIGURED, UNAUTHORIZED, UNREACHABLE }
}

/**
 * Talks to Home Assistant's REST API with a long-lived access token. Tries whichever address worked
 * last, then the local URL, then the remote one, so the same controls work at home and away.
 */
class HomeAssistant(private val prefs: Prefs) {
    private val worker = Executors.newFixedThreadPool(2)
    private val main = Handler(Looper.getMainLooper())

    @Volatile
    var activeBase: String? = null
        private set

    val isConfigured get() = prefs.haToken.isNotBlank() && (prefs.haLocal.isNotBlank() || prefs.haRemote.isNotBlank())
    val isRemote get() = activeBase != null && activeBase == normalizeUrl(prefs.haRemote) && activeBase != normalizeUrl(prefs.haLocal)

    fun lights(callback: (Result<List<Light>>) -> Unit) = async(callback) {
        val lights = parseLights(request("GET", "/api/states"))
        // Areas are a nicety; a server without the template API still lists its lights.
        val areas = runCatching {
            parseAreas(request("POST", "/api/template", JSONObject().put("template", AREA_TEMPLATE).toString()))
        }.getOrDefault(emptyMap())
        lights.onEach { it.area = areas[it.id] }
    }

    @Volatile
    private var weatherEntity: String? = null

    /** The first weather entity in Home Assistant; remembered so later reads fetch just that one. */
    fun weather(callback: (Result<Weather?>) -> Unit) = async(callback) {
        // Asking for just the entity id keeps this small; a full /api/states can run to megabytes.
        val chosen = prefs.haWeatherEntity.trim().takeIf { it.startsWith("weather.") }
        val id = chosen ?: weatherEntity ?: runCatching {
            request("POST", "/api/template", JSONObject().put("template", WEATHER_TEMPLATE).toString()).trim()
        }.getOrNull()?.takeIf { it.startsWith("weather.") }
        if (id != null) {
            weatherEntity = id
            return@async parseWeather(JSONObject(request("GET", "/api/states/$id")))
        }
        findWeather(request("GET", "/api/states")).also { weatherEntity = it?.entityId }
    }

    /** Lights left out of the panel: the person's choice once made, otherwise LED strip segments. */
    fun hiddenIds(all: List<Light>): Set<String> =
        prefs.haHidden ?: all.filter { isSegment(it) }.map { it.id }.toSet()

    fun setHidden(ids: Set<String>) = prefs.sp.edit { putStringSet(Prefs.HA_HIDDEN, ids) }

    fun hide(id: String, all: List<Light>) = setHidden(hiddenIds(all) + id)

    fun turnOn(ids: List<String>, extra: JSONObject = JSONObject(), callback: ((Result<Unit>) -> Unit)? = null) =
        callService("turn_on", extra.put("entity_id", JSONArray(ids)), callback)

    fun turnOff(ids: List<String>, callback: ((Result<Unit>) -> Unit)? = null) =
        callService("turn_off", JSONObject().put("entity_id", JSONArray(ids)), callback)

    private fun callService(service: String, data: JSONObject, callback: ((Result<Unit>) -> Unit)?) = async(callback) {
        request("POST", "/api/services/light/$service", data.toString())
        Unit
    }

    private fun <T> async(callback: ((Result<T>) -> Unit)?, block: () -> T) {
        worker.execute {
            val result = runCatching(block)
            main.post { callback?.invoke(result) }
        }
    }

    internal fun request(method: String, path: String, body: String? = null): String {
        val token = prefs.haToken.trim()
        val candidates = listOf(
            normalizeUrl(prefs.haLocal) to LOCAL_TIMEOUT_MS,
            normalizeUrl(prefs.haRemote) to REMOTE_TIMEOUT_MS,
        ).filter { it.first.isNotEmpty() }
        if (token.isEmpty() || candidates.isEmpty()) throw HaException(HaException.Kind.NOT_CONFIGURED)

        val ordered = candidates.sortedByDescending { it.first == activeBase }
        var failure: HaException? = null
        for ((base, timeout) in ordered) {
            try {
                val response = send(base, timeout, token, method, path, body)
                activeBase = base
                return response
            } catch (e: HaException) {
                // A rejected token is the same everywhere; trying the other address wouldn't help.
                if (e.kind == HaException.Kind.UNAUTHORIZED) throw e
                failure = e
            }
        }
        activeBase = null
        throw failure ?: HaException(HaException.Kind.UNREACHABLE)
    }

    private fun send(base: String, timeout: Int, token: String, method: String, path: String, body: String?): String {
        val connection = try {
            URL(base + path).openConnection() as HttpURLConnection
        } catch (e: Exception) {
            throw HaException(HaException.Kind.UNREACHABLE, e.message)
        }
        try {
            connection.requestMethod = method
            connection.connectTimeout = timeout
            connection.readTimeout = READ_TIMEOUT_MS
            connection.setRequestProperty("Authorization", "Bearer $token")
            connection.setRequestProperty("Content-Type", "application/json")
            if (body != null) {
                connection.doOutput = true
                connection.outputStream.use { it.write(body.toByteArray()) }
            }
            val code = connection.responseCode
            if (code == 401 || code == 403) throw HaException(HaException.Kind.UNAUTHORIZED)
            if (code !in 200..299) throw HaException(HaException.Kind.UNREACHABLE, "HTTP $code")
            return connection.inputStream.bufferedReader().use { it.readText() }
        } catch (e: IOException) {
            throw HaException(HaException.Kind.UNREACHABLE, e.message)
        } finally {
            connection.disconnect()
        }
    }

    companion object {
        private const val LOCAL_TIMEOUT_MS = 2000
        private const val REMOTE_TIMEOUT_MS = 8000
        private const val READ_TIMEOUT_MS = 8000
        private const val WEATHER_TEMPLATE = "{{ states.weather | map(attribute='entity_id') | first | default('') }}"
        private const val AREA_TEMPLATE =
            "{% for s in states.light %}{{ s.entity_id }}|{{ area_name(s.entity_id) or '' }}\n{% endfor %}"

        private val SEGMENT_ID = Regex("_segment_\\d+$")
        private val SEGMENT_NAME = Regex("\\bsegment\\s*\\d+\\b", RegexOption.IGNORE_CASE)

        /** WLED and similar strips expose every segment as its own light alongside the main one. */
        fun isSegment(light: Light) = SEGMENT_ID.containsMatchIn(light.id) || SEGMENT_NAME.containsMatchIn(light.name)

        fun normalizeUrl(raw: String): String {
            val trimmed = raw.trim().trimEnd('/')
            if (trimmed.isEmpty()) return ""
            return if ("://" in trimmed) trimmed else "http://$trimmed"
        }

        fun parseLights(json: String): List<Light> {
            val array = JSONArray(json)
            return (0 until array.length()).map { array.getJSONObject(it) }
                .filter { it.getString("entity_id").startsWith("light.") }
                .map { o ->
                    val attrs = o.optJSONObject("attributes") ?: JSONObject()
                    val modes = attrs.optJSONArray("supported_color_modes")
                        ?.let { m -> (0 until m.length()).map { m.getString(it) }.toSet() }
                        ?: setOf("onoff")
                    val hs = attrs.optJSONArray("hs_color")
                        ?.takeIf { it.length() == 2 }
                        ?.let { it.getDouble(0).toFloat() to it.getDouble(1).toFloat() }
                    Light(
                        id = o.getString("entity_id"),
                        name = attrs.optString("friendly_name").ifEmpty { o.getString("entity_id").substringAfter('.') },
                        state = o.optString("state"),
                        brightness = attrs.intOrNull("brightness"),
                        modes = modes,
                        hs = hs,
                        kelvin = attrs.intOrNull("color_temp_kelvin"),
                        minKelvin = attrs.intOrNull("min_color_temp_kelvin") ?: 2000,
                        maxKelvin = attrs.intOrNull("max_color_temp_kelvin") ?: 6500,
                    )
                }
                .sortedBy { it.name.lowercase() }
        }

        fun parseWeather(o: JSONObject): Weather {
            val attrs = o.optJSONObject("attributes") ?: JSONObject()
            val temperature = if (attrs.has("temperature") && !attrs.isNull("temperature")) attrs.optDouble("temperature") else null
            return Weather(
                entityId = o.getString("entity_id"),
                condition = o.optString("state"),
                temperature = temperature?.takeIf { !it.isNaN() },
                unit = attrs.optString("temperature_unit", "°"),
            )
        }

        fun findWeather(json: String): Weather? {
            val array = JSONArray(json)
            return (0 until array.length()).map { array.getJSONObject(it) }
                .firstOrNull { it.getString("entity_id").startsWith("weather.") }
                ?.let { parseWeather(it) }
        }

        fun parseAreas(text: String): Map<String, String> = text.lines()
            .mapNotNull { line ->
                val (id, area) = line.split('|', limit = 2).takeIf { it.size == 2 } ?: return@mapNotNull null
                if (area.isBlank()) null else id.trim() to area.trim()
            }
            .toMap()

        private fun JSONObject.intOrNull(key: String) = if (has(key) && !isNull(key)) optDouble(key).toInt() else null
    }
}
