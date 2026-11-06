package dev.tonal.launcher

import androidx.core.content.edit
import androidx.preference.PreferenceManager
import androidx.test.core.app.ApplicationProvider
import com.sun.net.httpserver.HttpServer
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.net.InetSocketAddress

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class HomeAssistantTest {
    private lateinit var prefs: Prefs
    private lateinit var server: HttpServer
    private var lastAuth: String? = null

    @Before
    fun setUp() {
        prefs = Prefs(PreferenceManager.getDefaultSharedPreferences(ApplicationProvider.getApplicationContext()))
        server = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0)
        server.createContext("/api/states") { exchange ->
            lastAuth = exchange.requestHeaders.getFirst("Authorization")
            val (code, body) = if (lastAuth == "Bearer good") 200 to STATES else 401 to "{}"
            exchange.sendResponseHeaders(code, body.length.toLong())
            exchange.responseBody.use { it.write(body.toByteArray()) }
        }
        server.start()
    }

    @After
    fun tearDown() = server.stop(0)

    private val serverUrl get() = "http://127.0.0.1:${server.address.port}"

    private fun configure(local: String, remote: String, token: String) = prefs.sp.edit {
        putString(Prefs.HA_LOCAL, local)
        putString(Prefs.HA_REMOTE, remote)
        putString(Prefs.HA_TOKEN, token)
    }

    @Test
    fun normalizesAddresses() {
        assertEquals("http://homeassistant.local:8123", HomeAssistant.normalizeUrl(" homeassistant.local:8123/ "))
        assertEquals("https://x.ui.nabu.casa", HomeAssistant.normalizeUrl("https://x.ui.nabu.casa/"))
        assertEquals("", HomeAssistant.normalizeUrl("  "))
    }

    @Test
    fun parsesOnlyLightsWithTheirCapabilities() {
        val lights = HomeAssistant.parseLights(STATES)
        assertEquals(listOf("light.desk", "light.porch"), lights.map { it.id })
        val desk = lights.first()
        assertTrue(desk.on)
        assertEquals(0.5f, desk.level, 0.01f)
        assertTrue(desk.supportsTemperature)
        assertTrue(desk.supportsColor)
        val porch = lights.last()
        assertFalse(porch.dimmable)
        assertEquals(0f, porch.level)
    }

    @Test
    fun parsesAreasAndSkipsLightsWithoutOne() {
        val areas = HomeAssistant.parseAreas("light.desk|Office\nlight.porch|\n\n")
        assertEquals(mapOf("light.desk" to "Office"), areas)
    }

    private fun light(id: String, name: String) =
        Light(id, name, "off", null, setOf("onoff"), null, null, 2000, 6500)

    @Test
    fun findsTheFirstWeatherEntity() {
        val weather = HomeAssistant.findWeather(
            """[{"entity_id":"light.desk","state":"on","attributes":{}},
                {"entity_id":"weather.forecast_home","state":"cloudy","attributes":{"temperature":55.4,"temperature_unit":"°F"}}]""",
        )!!
        assertEquals("weather.forecast_home", weather.entityId)
        assertEquals("cloudy", weather.condition)
        assertEquals(55.4, weather.temperature!!, 0.01)
        assertEquals("°F", weather.unit)
        assertNull(HomeAssistant.findWeather("""[{"entity_id":"light.desk","state":"on"}]"""))
    }

    @Test
    fun shortcutKeysRoundTrip() {
        val key = ShortcutKey("com.google.android.apps.maps", "route:home|work", 0)
        assertEquals(key, ShortcutKey.parse(key.toString()))
        assertNull(ShortcutKey.parse("com.android.chrome/.Main#0"))
    }

    @Test
    fun segmentsAreHiddenUntilThePersonChooses() {
        val all = listOf(
            light("light.desk", "Desk Lights"),
            light("light.desk_segment_1", "Desk Lights Segment 1"),
            light("light.strip_2", "TV strip Segment 2"),
            light("light.hall", "Hall"),
        )
        val ha = HomeAssistant(prefs)
        assertEquals(setOf("light.desk_segment_1", "light.strip_2"), ha.hiddenIds(all))

        ha.setHidden(setOf("light.hall"))
        assertEquals(setOf("light.hall"), ha.hiddenIds(all))

        ha.hide("light.desk", all)
        assertEquals(setOf("light.hall", "light.desk"), ha.hiddenIds(all))
    }

    @Test
    fun fallsBackToRemoteWhenHomeAddressIsDown() {
        configure(local = "http://127.0.0.1:1", remote = serverUrl, token = "good")
        val ha = HomeAssistant(prefs)
        HomeAssistant.parseLights(ha.request("GET", "/api/states"))
        assertEquals(serverUrl, ha.activeBase)
        assertTrue(ha.isRemote)
        assertEquals("Bearer good", lastAuth)
    }

    @Test
    fun rejectedTokenIsReportedAndNotRetried() {
        configure(local = serverUrl, remote = "", token = "bad")
        val ha = HomeAssistant(prefs)
        try {
            ha.request("GET", "/api/states")
            fail("expected a rejected token")
        } catch (e: HaException) {
            assertEquals(HaException.Kind.UNAUTHORIZED, e.kind)
        }
        assertNull(ha.activeBase)
    }

    @Test
    fun missingTokenMeansNotConfigured() {
        configure(local = serverUrl, remote = "", token = "")
        try {
            HomeAssistant(prefs).request("GET", "/api/states")
            fail("expected not configured")
        } catch (e: HaException) {
            assertEquals(HaException.Kind.NOT_CONFIGURED, e.kind)
        }
    }

    private companion object {
        val STATES = """
            [
              {"entity_id": "light.porch", "state": "off", "attributes": {"friendly_name": "Porch", "supported_color_modes": ["onoff"]}},
              {"entity_id": "switch.fan", "state": "on", "attributes": {"friendly_name": "Fan"}},
              {"entity_id": "light.desk", "state": "on", "attributes": {"friendly_name": "Desk", "brightness": 128,
                "supported_color_modes": ["color_temp", "hs"], "color_temp_kelvin": 3000,
                "min_color_temp_kelvin": 2200, "max_color_temp_kelvin": 6500, "hs_color": [30.0, 50.0]}}
            ]
        """.trimIndent()
    }
}
