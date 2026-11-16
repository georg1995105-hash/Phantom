package xyz.wraithnet.phantom.data

/** Country flags are inferred only from explicit location tokens in a server title. */
object CountryFlags {
    private val names = linkedMapOf(
        "NL" to listOf("nl", "netherlands", "nederland", "нидерланды", "голландия"),
        "SE" to listOf("se", "sweden", "sverige", "швеция"),
        "DE" to listOf("de", "germany", "deutschland", "германия"),
        "PL" to listOf("pl", "poland", "polska", "польша"),
        "FI" to listOf("fi", "finland", "suomi", "финляндия"),
        "GB" to listOf("uk", "gb", "britain", "united kingdom", "england", "великобритания", "англия"),
        "US" to listOf("us", "usa", "united states", "america", "сша"),
        "FR" to listOf("fr", "france", "франция"),
        "IT" to listOf("it", "italy", "италия"),
        "ES" to listOf("es", "spain", "испания"),
        "CH" to listOf("ch", "switzerland", "швейцария"),
        "AT" to listOf("at", "austria", "австрия"),
        "CZ" to listOf("cz", "czech", "czechia", "чехия"),
        "RO" to listOf("ro", "romania", "румыния"),
        "BG" to listOf("bg", "bulgaria", "болгария"),
        "RU" to listOf("ru", "russia", "россия"),
        "UA" to listOf("ua", "ukraine", "украина"),
        "TR" to listOf("tr", "turkey", "türkiye", "турция"),
        "LT" to listOf("lt", "lithuania", "литва"),
        "LV" to listOf("lv", "latvia", "латвия"),
        "EE" to listOf("ee", "estonia", "эстония"),
        "NO" to listOf("no", "norway", "норвегия"),
        "DK" to listOf("dk", "denmark", "дания"),
        "BE" to listOf("be", "belgium", "бельгия"),
        "IE" to listOf("ie", "ireland", "ирландия"),
        "PT" to listOf("pt", "portugal", "португалия"),
        "HU" to listOf("hu", "hungary", "венгрия"),
        "GR" to listOf("gr", "greece", "греция"),
        "KZ" to listOf("kz", "kazakhstan", "казахстан"),
        "GE" to listOf("ge", "georgia", "грузия"),
        "CA" to listOf("ca", "canada", "канада"),
        "JP" to listOf("jp", "japan", "япония"),
        "SG" to listOf("sg", "singapore", "сингапур"),
        "HK" to listOf("hk", "hong kong", "гонконг"),
        "KR" to listOf("kr", "korea", "south korea", "корея"),
        "IN" to listOf("in", "india", "индия"),
        "BR" to listOf("br", "brazil", "бразилия"),
        "AE" to listOf("ae", "uae", "emirates", "dubai", "оаэ"),
    )

    fun inferred(name: String): String? {
        val normalized = (" " + name.lowercase()
            .replace(Regex("[^\\p{L}\\p{N}]+"), " ").trim() + " ")
        val iso = names.entries.firstOrNull { (_, aliases) ->
            aliases.any { normalized.contains(" $it ") }
        }?.key ?: return null
        return isoToFlag(iso)
    }

    fun flag(server: ServerProfile): String =
        server.countryFlag.takeIf { it.isNotBlank() } ?: inferred(server.name) ?: "🌐"

    private fun isoToFlag(iso: String): String {
        val start = 0x1F1E6
        return String(Character.toChars(start + iso[0].code - 'A'.code)) +
            String(Character.toChars(start + iso[1].code - 'A'.code))
    }
}
