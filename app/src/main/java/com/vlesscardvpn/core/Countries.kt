package com.vlesscardvpn.core

/**
 * Server country from its name: a flag emoji (🇳🇱), an upper-case ISO code ("NL-1", "[DE]"), a country or city name
 * (English/Russian). "" when the name says nothing. Used for «Страна» (Auto picks servers of that country first).
 */
object Countries {
    val NAMES = linkedMapOf(
        "NL" to "Нидерланды", "DE" to "Германия", "FI" to "Финляндия", "SE" to "Швеция", "PL" to "Польша", "FR" to "Франция",
        "GB" to "Великобритания", "US" to "США", "CA" to "Канада", "TR" to "Турция", "KZ" to "Казахстан", "AE" to "ОАЭ",
        "JP" to "Япония", "SG" to "Сингапур", "HK" to "Гонконг", "EE" to "Эстония", "LV" to "Латвия", "LT" to "Литва",
        "AT" to "Австрия", "CH" to "Швейцария", "RU" to "Россия", "UA" to "Украина", "ES" to "Испания", "IT" to "Италия",
        "CZ" to "Чехия", "RO" to "Румыния", "BG" to "Болгария", "MD" to "Молдова", "AM" to "Армения", "GE" to "Грузия",
        "IL" to "Израиль", "IN" to "Индия", "KR" to "Корея", "TW" to "Тайвань", "AU" to "Австралия", "BR" to "Бразилия",
        "NO" to "Норвегия", "DK" to "Дания", "IE" to "Ирландия", "BE" to "Бельгия", "LU" to "Люксембург", "HU" to "Венгрия",
        "RS" to "Сербия", "AZ" to "Азербайджан", "UZ" to "Узбекистан", "KG" to "Киргизия", "BY" to "Беларусь", "PT" to "Португалия",
        "GR" to "Греция", "CY" to "Кипр", "IS" to "Исландия", "SK" to "Словакия", "SI" to "Словения", "HR" to "Хорватия", "MX" to "Мексика",
        "AR" to "Аргентина", "TH" to "Таиланд", "VN" to "Вьетнам", "ID" to "Индонезия", "MY" to "Малайзия", "ZA" to "ЮАР",
    )

    private val WORDS: List<Pair<String, String>> = listOf(
        "netherlands" to "NL", "нидерланд" to "NL", "голланд" to "NL", "amsterdam" to "NL", "амстердам" to "NL",
        "germany" to "DE", "герман" to "DE", "frankfurt" to "DE", "франкфурт" to "DE", "berlin" to "DE", "берлин" to "DE", "nuremberg" to "DE",
        "finland" to "FI", "финлянд" to "FI", "helsinki" to "FI", "хельсинк" to "FI",
        "sweden" to "SE", "швец" to "SE", "stockholm" to "SE", "poland" to "PL", "польш" to "PL", "warsaw" to "PL", "варшав" to "PL",
        "france" to "FR", "франц" to "FR", "paris" to "FR", "париж" to "FR",
        "united kingdom" to "GB", "britain" to "GB", "england" to "GB", "london" to "GB", "лондон" to "GB", "великобритан" to "GB", "англи" to "GB",
        "united states" to "US", "usa" to "US", "сша" to "US", "america" to "US", "америк" to "US", "new york" to "US", "los angeles" to "US", "dallas" to "US", "miami" to "US",
        "canada" to "CA", "канад" to "CA", "turkey" to "TR", "türkiye" to "TR", "турц" to "TR", "istanbul" to "TR", "стамбул" to "TR",
        "kazakhstan" to "KZ", "казахстан" to "KZ", "almaty" to "KZ", "алмат" to "KZ", "uae" to "AE", "dubai" to "AE", "дубай" to "AE", "эмират" to "AE",
        "japan" to "JP", "япон" to "JP", "tokyo" to "JP", "токио" to "JP", "singapore" to "SG", "сингапур" to "SG", "hong kong" to "HK", "гонконг" to "HK",
        "estonia" to "EE", "эстон" to "EE", "tallinn" to "EE", "latvia" to "LV", "латви" to "LV", "riga" to "LV", "lithuania" to "LT", "литв" to "LT",
        "austria" to "AT", "австри" to "AT", "vienna" to "AT", "switzerland" to "CH", "швейцар" to "CH", "zurich" to "CH",
        "russia" to "RU", "росси" to "RU", "moscow" to "RU", "москв" to "RU", "ukraine" to "UA", "украин" to "UA",
        "spain" to "ES", "испан" to "ES", "madrid" to "ES", "italy" to "IT", "итал" to "IT", "milan" to "IT", "czech" to "CZ", "чех" to "CZ", "prague" to "CZ",
        "romania" to "RO", "румын" to "RO", "bulgaria" to "BG", "болгар" to "BG", "moldova" to "MD", "молдов" to "MD", "armenia" to "AM", "армени" to "AM",
        "georgia" to "GE", "грузия" to "GE", "tbilisi" to "GE", "israel" to "IL", "израил" to "IL", "india" to "IN", "индия" to "IN", "korea" to "KR", "корея" to "KR",
        "taiwan" to "TW", "тайван" to "TW", "australia" to "AU", "австрал" to "AU", "brazil" to "BR", "бразил" to "BR", "norway" to "NO", "норвег" to "NO",
        "denmark" to "DK", "дания" to "DK", "ireland" to "IE", "ирланд" to "IE", "belgium" to "BE", "бельги" to "BE", "luxembourg" to "LU", "люксембург" to "LU",
        "hungary" to "HU", "венгр" to "HU", "serbia" to "RS", "серби" to "RS", "azerbaijan" to "AZ", "азербайджан" to "AZ", "uzbekistan" to "UZ", "узбекистан" to "UZ",
        "belarus" to "BY", "беларус" to "BY", "portugal" to "PT", "португал" to "PT", "greece" to "GR", "греци" to "GR", "cyprus" to "CY", "кипр" to "CY",
    )

    private val FLAG = Regex("[\\uD83C][\\uDDE6-\\uDDFF][\\uD83C][\\uDDE6-\\uDDFF]")

    fun of(name: String): String {
        FLAG.find(name)?.value?.let { f ->
            val a = f.codePointAt(0) - 0x1F1E6; val b = f.codePointAt(2) - 0x1F1E6
            val iso = "${'A' + a}${'A' + b}".let { if (it == "UK") "GB" else it }
            if (iso in NAMES) return iso
        }
        val low = name.lowercase()
        WORDS.firstOrNull { low.contains(it.first) }?.let { return it.second }
        // upper-case codes as separate tokens only ("NL-1", "[DE] Fast"), so words like "in"/"de" are not codes
        name.split(Regex("[^A-Za-z]+")).firstOrNull { it.length == 2 && it == it.uppercase() && (it in NAMES || it == "UK") }?.let { return if (it == "UK") "GB" else it }
        return ""
    }

    fun flag(iso: String): String = if (iso.length != 2) "🌐" else String(Character.toChars(0x1F1E6 + (iso[0] - 'A'))) + String(Character.toChars(0x1F1E6 + (iso[1] - 'A')))

    fun title(iso: String): String = if (iso.isEmpty()) "Любая" else flag(iso) + " " + (NAMES[iso] ?: iso)
}
