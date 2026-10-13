package com.aiglobal.jobfinder.data.geo

/**
 * Maps the free-text location strings that job boards publish ("Berlin", "France",
 * "Worldwide", "Anywhere in the World") onto a country with a flag.
 *
 * Job APIs are inconsistent: some send a city, some a country, some a region. This
 * catalog resolves all of them to one canonical name so the eligibility engine and
 * the country filters can work on a single value.
 */
object CountryCatalog {

    /** Canonical country plus the strings that map to it. */
    private data class Entry(
        val country: String,
        val flag: String,
        val aliases: List<String>,
    )

    private val entries: List<Entry> = listOf(
        Entry("United States", "🇺🇸", listOf("united states", "usa", "u.s.a", "u.s.", "america")),
        Entry("Canada", "🇨🇦", listOf("canada")),
        Entry("United Kingdom", "🇬🇧", listOf("united kingdom", "uk", "great britain", "britain", "england", "scotland", "wales", "northern ireland")),
        Entry("Germany", "🇩🇪", listOf("germany", "deutschland")),
        Entry("France", "🇫🇷", listOf("france")),
        Entry("Netherlands", "🇳🇱", listOf("netherlands", "holland")),
        Entry("Belgium", "🇧🇪", listOf("belgium")),
        Entry("Switzerland", "🇨🇭", listOf("switzerland")),
        Entry("Austria", "🇦🇹", listOf("austria")),
        Entry("Ireland", "🇮🇪", listOf("ireland")),
        Entry("Spain", "🇪🇸", listOf("spain")),
        Entry("Portugal", "🇵🇹", listOf("portugal")),
        Entry("Italy", "🇮🇹", listOf("italy")),
        Entry("Sweden", "🇸🇪", listOf("sweden")),
        Entry("Norway", "🇳🇴", listOf("norway")),
        Entry("Denmark", "🇩🇰", listOf("denmark")),
        Entry("Finland", "🇫🇮", listOf("finland")),
        Entry("Poland", "🇵🇱", listOf("poland")),
        Entry("Czechia", "🇨🇿", listOf("czechia", "czech republic")),
        Entry("Luxembourg", "🇱🇺", listOf("luxembourg")),
        Entry("United Arab Emirates", "🇦🇪", listOf("united arab emirates", "uae", "dubai", "abu dhabi")),
        Entry("Qatar", "🇶🇦", listOf("qatar", "doha")),
        Entry("Saudi Arabia", "🇸🇦", listOf("saudi arabia", "riyadh", "jeddah")),
        Entry("Kuwait", "🇰🇼", listOf("kuwait")),
        Entry("Oman", "🇴🇲", listOf("oman")),
        Entry("Bahrain", "🇧🇭", listOf("bahrain")),
        Entry("Australia", "🇦🇺", listOf("australia")),
        Entry("New Zealand", "🇳🇿", listOf("new zealand")),
        Entry("Japan", "🇯🇵", listOf("japan")),
        Entry("South Korea", "🇰🇷", listOf("south korea", "korea")),
        Entry("Singapore", "🇸🇬", listOf("singapore")),
        Entry("Malaysia", "🇲🇾", listOf("malaysia")),
        Entry("India", "🇮🇳", listOf("india")),
        Entry("China", "🇨🇳", listOf("china")),
        Entry("South Africa", "🇿🇦", listOf("south africa")),
        Entry("Kenya", "🇰🇪", listOf("kenya", "nairobi")),
        Entry("Nigeria", "🇳🇬", listOf("nigeria")),
        Entry("Ghana", "🇬🇭", listOf("ghana")),
        Entry("Morocco", "🇲🇦", listOf("morocco")),
        Entry("Tunisia", "🇹🇳", listOf("tunisia")),
        Entry("Egypt", "🇪🇬", listOf("egypt")),
        Entry("Mauritius", "🇲🇺", listOf("mauritius")),
        Entry("Madagascar", "🇲🇬", listOf("madagascar", "antananarivo")),
        Entry("Brazil", "🇧🇷", listOf("brazil")),
        Entry("Mexico", "🇲🇽", listOf("mexico")),
        Entry("Argentina", "🇦🇷", listOf("argentina")),
        Entry("Turkey", "🇹🇷", listOf("turkey", "türkiye", "turkiye")),
        Entry("Israel", "🇮🇱", listOf("israel")),
        Entry("Philippines", "🇵🇭", listOf("philippines")),
        Entry("Indonesia", "🇮🇩", listOf("indonesia")),
        Entry("Thailand", "🇹🇭", listOf("thailand")),
        Entry("Vietnam", "🇻🇳", listOf("vietnam")),
        Entry("Romania", "🇷🇴", listOf("romania")),
        Entry("Hungary", "🇭🇺", listOf("hungary")),
        Entry("Greece", "🇬🇷", listOf("greece")),
        Entry("Croatia", "🇭🇷", listOf("croatia")),
        Entry("Estonia", "🇪🇪", listOf("estonia")),
        Entry("Lithuania", "🇱🇹", listOf("lithuania")),
        Entry("Latvia", "🇱🇻", listOf("latvia")),
        Entry("Iceland", "🇮🇸", listOf("iceland")),
    )

    /** Canonical label used when no country can be resolved. */
    const val WORLDWIDE = "Worldwide"
    const val WORLDWIDE_FLAG = "🌍"

    private val worldwideWords = listOf(
        "worldwide", "anywhere", "global", "world", "international", "any location", "flexible",
    )

    /** Every country the catalog knows, sorted for the filter list. */
    val countries: List<Country> = entries
        .map { Country(it.country, it.flag) }
        .sortedBy { it.name }

    /**
     * Resolves a published location to a country. Location strings arrive as
     * "Berlin", "France (Remote)" or "Anywhere in the World", so the longest alias
     * found as a whole word in the text wins.
     */
    fun resolve(rawLocation: String?): Country {
        val raw = rawLocation?.trim().orEmpty()
        if (raw.isEmpty()) return Country(WORLDWIDE, WORLDWIDE_FLAG)
        val normalized = raw.lowercase()

        val longest = entries
            .asSequence()
            .flatMap { entry -> entry.aliases.asSequence().map { it to entry } }
            .filter { (alias, _) -> containsWord(normalized, alias) }
            .maxByOrNull { (alias, _) -> alias.length }
            ?.second

        if (longest != null) return Country(longest.country, longest.flag)

        val worldwide = worldwideWords.any { normalized.contains(it) }
        return if (worldwide) Country(WORLDWIDE, WORLDWIDE_FLAG) else Country(raw, WORLDWIDE_FLAG)
    }

    /** Matches [needle] only when it is bounded by non-letters, so "us" ≠ "business". */
    private fun containsWord(haystack: String, needle: String): Boolean {
        var index = haystack.indexOf(needle)
        while (index >= 0) {
            val before = index == 0 || !haystack[index - 1].isLetter()
            val afterIndex = index + needle.length
            val after = afterIndex >= haystack.length || !haystack[afterIndex].isLetter()
            if (before && after) return true
            index = haystack.indexOf(needle, index + 1)
        }
        return false
    }
}

/** A resolved country with its flag emoji. */
data class Country(val name: String, val flag: String) {
    val isWorldwide: Boolean get() = name == CountryCatalog.WORLDWIDE
}
