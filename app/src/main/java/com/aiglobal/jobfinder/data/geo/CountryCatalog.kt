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
        // "america" alone is deliberately not an alias: it is a substring of "South America",
        // "Central America", "Latin America" and "North America", so mapping it here labelled
        // whole regions — and North America, a continent — as the United States.
        Entry("United States", "🇺🇸", listOf("united states of america", "united states", "usa", "u.s.a", "u.s.")),
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
        Entry("Slovakia", "🇸🇰", listOf("slovakia", "slovak republic")),
        Entry("Slovenia", "🇸🇮", listOf("slovenia")),
        Entry("Bulgaria", "🇧🇬", listOf("bulgaria")),
        Entry("Croatia", "🇭🇷", listOf("croatia")),
        Entry("Cyprus", "🇨🇾", listOf("cyprus")),
        Entry("Malta", "🇲🇹", listOf("malta")),
        Entry("Estonia", "🇪🇪", listOf("estonia")),
        Entry("Latvia", "🇱🇻", listOf("latvia")),
        Entry("Lithuania", "🇱🇹", listOf("lithuania")),
        Entry("Liechtenstein", "🇱🇮", listOf("liechtenstein")),
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
        // "korea" alone is deliberately not an alias: it is a substring of "North Korea" too,
        // so it would label a North Korean posting as South Korea. "Korea" with no qualifier
        // resolves to no country (see the resolve() note), and a qualifier is required.
        Entry("South Korea", "🇰🇷", listOf("south korea")),
        Entry("North Korea", "🇰🇵", listOf("north korea")),
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
        // The three Guineas must stay distinct. Their specific names are longer than the bare
        // "guinea", so longest-alias wins for them, while a lone "Guinea" still resolves here.
        Entry("Guinea", "🇬🇳", listOf("guinea")),
        Entry("Guinea-Bissau", "🇬🇼", listOf("guinea-bissau", "guinea bissau")),
        Entry("Equatorial Guinea", "🇬🇶", listOf("equatorial guinea")),
        Entry("Mali", "🇲🇱", listOf("mali")),
        Entry("Burkina Faso", "🇧🇫", listOf("burkina faso")),
        Entry("Tanzania", "🇹🇿", listOf("tanzania")),
        Entry("Zambia", "🇿🇲", listOf("zambia")),
        Entry("Zimbabwe", "🇿🇼", listOf("zimbabwe", "harare")),
        Entry("Côte d'Ivoire", "🇨🇮", listOf("côte d'ivoire", "cote d'ivoire", "ivory coast", "abidjan")),
        Entry("Botswana", "🇧🇼", listOf("botswana")),
        Entry("Namibia", "🇳🇦", listOf("namibia")),
        // The two Congos must stay distinct: "Republic of the Congo" / "Congo-Brazzaville" is
        // Brazzaville, not Kinshasa. The bare "congo" alias is kept for the DRC only because it
        // is the shorter name it is commonly shortened to, but every longer, unambiguous name of
        // either country (above and here) outranks it, so a specific spelling never falls to it.
        Entry("Democratic Republic of the Congo", "🇨🇩", listOf("democratic republic of the congo", "democratic republic of congo", "dr congo", "drc", "congo", "kinshasa")),
        Entry("Republic of the Congo", "🇨🇬", listOf("republic of the congo", "republic of congo", "congo-brazzaville", "congo brazzaville", "brazzaville")),
        Entry("Mongolia", "🇲🇳", listOf("mongolia")),
        Entry("Kazakhstan", "🇰🇿", listOf("kazakhstan")),
        Entry("Chile", "🇨🇱", listOf("chile")),
        Entry("Peru", "🇵🇪", listOf("peru")),
        Entry("Suriname", "🇸🇷", listOf("suriname")),
        Entry("Panama", "🇵🇦", listOf("panama")),
        Entry("Papua New Guinea", "🇵🇬", listOf("papua new guinea")),
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
        Entry("Iceland", "🇮🇸", listOf("iceland")),
    )

    /** Canonical label used when no country can be resolved. */
    const val WORLDWIDE = "Worldwide"
    const val WORLDWIDE_FLAG = "🌍"

    /**
     * Flag for a location the catalog cannot map to a country — a bare city ("Munich") or a
     * region ("Europe"). It is deliberately **not** the worldwide globe: the source said a
     * place, not "anywhere", so badging it as worldwide would misrepresent a local vacancy
     * as a role open to applicants from anywhere.
     */
    const val UNRESOLVED_FLAG = "📍"

    private val worldwideWords = listOf(
        "worldwide", "anywhere", "global", "world", "international", "any location", "flexible",
    )

    /** Every country the catalog knows, sorted for the filter list. */
    val countries: List<Country> = entries
        .map { Country(it.country, it.flag) }
        .sortedBy { it.name }

    /** Canonical country names the catalog recognises, for a cheap membership check. */
    private val knownCountryNames: Set<String> = entries.mapTo(mutableSetOf()) { it.country }

    /**
     * True when [name] is a country the catalog knows. A resolved region ("EMEA"),
     * [WORLDWIDE] and any unmatched free-text label all return false.
     */
    fun isKnownCountry(name: String): Boolean = name in knownCountryNames

    /**
     * Resolves a published location to a country. Location strings arrive as
     * "Berlin", "France (Remote)" or "Anywhere in the World", so the longest alias
     * found as a whole word in the text wins.
     *
     * Longest-alias-wins is what keeps compound names apart: the specific spelling of a
     * country ("equatorial guinea", "guinea-bissau", "republic of the congo", "north korea")
     * is longer than the generic word it contains ("guinea", "congo", "korea"), so it wins
     * and the generic alias never captures it. A generic alias is therefore only reached by a
     * genuinely generic string.
     *
     * A bare "Korea" carries no qualifier and is deliberately left unresolved: the catalog
     * will not guess between North and South Korea, so it returns the raw label with
     * [UNRESOLVED_FLAG] rather than mislabelling a posting. "North Korea" and "South Korea"
     * both resolve correctly.
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
        return if (worldwide) Country(WORLDWIDE, WORLDWIDE_FLAG) else Country(raw, UNRESOLVED_FLAG)
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

    /**
     * True when the label matched a country the catalog actually knows (Canada, Germany,
     * …). False for [isWorldwide] and for a free-text label that resolved to no known
     * country — which is also the case for a region such as "EMEA", "Europe" or "Africa".
     *
     * This is deliberately stricter than "not worldwide": a region is not a country, and
     * treating one as a country let the eligibility engine's region rules (which do
     * recognise regions) be short-circuited before they could run.
     */
    val isRecognized: Boolean get() = CountryCatalog.isKnownCountry(name)
}
