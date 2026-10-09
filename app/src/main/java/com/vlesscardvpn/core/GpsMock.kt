package com.vlesscardvpn.core

import android.content.Context
import android.location.Location
import android.location.LocationManager
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableStateFlow

/**
 * «GPS под страну сервера»: Android's mock location (the app must be chosen in Developer options →
 * «Приложение для фиктивных местоположений»). Puts the phone in the server country's capital with a little jitter.
 */
object GpsMock {
    val COORDS: Map<String, Pair<Double, Double>> = mapOf(
        "US" to (37.7749 to -122.4194), "NL" to (52.3676 to 4.9041), "DE" to (50.1109 to 8.6821), "FI" to (60.1699 to 24.9384),
        "GB" to (51.5074 to -0.1278), "FR" to (48.8566 to 2.3522), "SE" to (59.3293 to 18.0686), "PL" to (52.2297 to 21.0122),
        "CA" to (43.6532 to -79.3832), "JP" to (35.6762 to 139.6503), "SG" to (1.3521 to 103.8198), "TR" to (41.0082 to 28.9784),
        "KZ" to (43.2389 to 76.8897), "AE" to (25.2048 to 55.2708), "EE" to (59.4370 to 24.7536), "LV" to (56.9496 to 24.1052),
        "LT" to (54.6872 to 25.2797), "AT" to (48.2082 to 16.3738), "CH" to (47.3769 to 8.5417), "ES" to (40.4168 to -3.7038),
        "IT" to (45.4642 to 9.1900), "CZ" to (50.0755 to 14.4378), "HK" to (22.3193 to 114.1694), "KR" to (37.5665 to 126.9780),
        "IN" to (19.0760 to 72.8777), "AU" to (-33.8688 to 151.2093), "BR" to (-23.5505 to -46.6333), "NO" to (59.9139 to 10.7522),
        "DK" to (55.6761 to 12.5683), "IE" to (53.3498 to -6.2603), "BE" to (50.8503 to 4.3517), "RO" to (44.4268 to 26.1025),
        "BG" to (42.6977 to 23.3219), "AM" to (40.1792 to 44.4991), "GE" to (41.7151 to 44.8271), "IL" to (32.0853 to 34.7818),
        "RS" to (44.7866 to 20.4489), "HU" to (47.4979 to 19.0402), "PT" to (38.7223 to -9.1393), "MD" to (47.0105 to 28.8638),
        "UA" to (50.4501 to 30.5234), "RU" to (55.7558 to 37.6173), "LU" to (49.6116 to 6.1319), "GR" to (37.9838 to 23.7275),
        "CY" to (35.1856 to 33.3823), "SK" to (48.1486 to 17.1077), "AZ" to (40.4093 to 49.8671), "UZ" to (41.2995 to 69.2401),
    )
    private val PROVIDERS = listOf(LocationManager.GPS_PROVIDER, LocationManager.NETWORK_PROVIDER)
    val state = MutableStateFlow("")
    @Volatile private var country = ""
    private var job: Job? = null

    fun openDevSettings(ctx: Context) = runCatching {
        ctx.startActivity(android.content.Intent(android.provider.Settings.ACTION_APPLICATION_DEVELOPMENT_SETTINGS).addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK))
    }

    /** Keeps the fake location in [iso] (no-op when already there). */
    @Synchronized fun follow(ctx: Context, iso: String) {
        if (iso == country && job?.isActive == true) return
        val c = COORDS[iso] ?: run { stop(ctx); return }
        stop(ctx)
        val lm = ctx.getSystemService(Context.LOCATION_SERVICE) as LocationManager
        try {
            PROVIDERS.forEach { p ->
                runCatching { lm.removeTestProvider(p) }
                @Suppress("DEPRECATION")
                lm.addTestProvider(p, false, false, false, false, true, true, true, 1, 1)
                lm.setTestProviderEnabled(p, true)
            }
        } catch (e: SecurityException) {
            state.value = "Нужно разрешение: Параметры разработчика → «Приложение для фиктивных местоположений» → VLESS Card"
            return
        } catch (e: Exception) { state.value = "GPS не подменить: ${e.message}"; return }
        country = iso
        state.value = "GPS: ${Countries.title(iso)}"
        val rnd = java.util.Random()
        job = CoroutineScope(SupervisorJob() + Dispatchers.Default).launch {
            while (isActive) {
                PROVIDERS.forEach { p -> runCatching {
                    lm.setTestProviderLocation(p, Location(p).apply {
                        latitude = c.first + rnd.nextGaussian() * 0.00005; longitude = c.second + rnd.nextGaussian() * 0.00005
                        altitude = 30.0; accuracy = 6f; speed = 0f; bearing = 0f
                        time = System.currentTimeMillis(); elapsedRealtimeNanos = android.os.SystemClock.elapsedRealtimeNanos()
                    })
                } }
                delay(1000)
            }
        }
    }

    @Synchronized fun stop(ctx: Context) {
        if (country.isEmpty() && job == null) return
        job?.cancel(); job = null; country = ""; state.value = ""
        val lm = ctx.getSystemService(Context.LOCATION_SERVICE) as LocationManager
        PROVIDERS.forEach { p -> runCatching { lm.removeTestProvider(p) } }
    }
}
