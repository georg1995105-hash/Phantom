package xyz.wraithnet.phantom.data

import org.junit.Assert.*
import org.junit.Test

class CountryFlagsTest {
    @Test fun recognizableLocationsShowCountryFlag() {
        assertEquals("🇳🇱", CountryFlags.inferred("NL Amsterdam 01"))
        assertEquals("🇸🇪", CountryFlags.inferred("Швеция · запасной"))
        assertEquals("🇩🇪", CountryFlags.inferred("Germany - XHTTP"))
        assertEquals("🇵🇱", CountryFlags.inferred("Poland 1337"))
        assertEquals("🇷🇴", CountryFlags.inferred("Romania / Bucharest"))
    }

    @Test fun noAccidentalMatchesWithinUnrelatedWords() {
        assertNull(CountryFlags.inferred("server-unknown"))
        assertNull(CountryFlags.inferred("inbound"))
        assertNull(CountryFlags.inferred("news"))
    }

    @Test fun realFlagTakesPrecedenceOverTextGuess() {
        val p = ServerProfile("1", "NL VPN", "vless", raw = "", countryFlag = "🇸🇪")
        assertEquals("🇸🇪", CountryFlags.flag(p))
    }
}
