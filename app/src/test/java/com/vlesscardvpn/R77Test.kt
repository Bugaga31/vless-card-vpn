package com.vlesscardvpn

import com.vlesscardvpn.core.Actions
import com.vlesscardvpn.core.AppState
import com.vlesscardvpn.core.Countries
import com.vlesscardvpn.model.Server
import com.vlesscardvpn.model.ServerState
import com.vlesscardvpn.model.Settings
import org.junit.Assert.assertEquals
import org.junit.Test

class R77Test {
    @Test fun countryFromName() {
        assertEquals("NL", Countries.of("🇳🇱 Amsterdam #3"))
        assertEquals("DE", Countries.of("Германия — быстрый"))
        assertEquals("FI", Countries.of("[FI] Helsinki-2"))
        assertEquals("GB", Countries.of("🇬🇧 London"))
        assertEquals("US", Countries.of("US-1 reality"))
        assertEquals("", Countries.of("Server de luxe in cloud"))   // lower-case words are not codes
        assertEquals("", Countries.of("Загрузить быстро"))           // «грузи» is not Georgia
        assertEquals("🇳🇱", Countries.flag("NL")); assertEquals("Любая", Countries.title(""))
    }

    @Test fun preferredCountryAndFavorites() {
        val nl = Server("🇳🇱 NL", "vless", "1.1.1.1", 443, "u"); val de = Server("🇩🇪 DE", "vless", "2.2.2.2", 443, "u")
        val ok = ServerState(realMs = 100, bigOk = true)
        val st = AppState(listOf(nl, de), mapOf(nl.id to ok, de.id to ok), Settings(country = "DE"))
        assertEquals(listOf(de), Actions.preferred(st, st.servers))
        assertEquals(st.servers, Actions.preferred(st.copy(settings = Settings(country = "JP")), st.servers)) // none there → all
        assertEquals(listOf("NL" to 1, "DE" to 1).toSet(), Actions.countries(st).toSet())
        val s = Settings(country = "NL", favorites = listOf("x"))
        val back = Settings.fromJson(s.toJson()); assertEquals("NL", back.country); assertEquals(listOf("x"), back.favorites)
    }
}
