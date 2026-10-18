package com.aiglobal.jobfinder.data.jobs.search

/**
 * Local synonym expansion for the jobs search box.
 *
 * The defect this fixes: a candidate typed "Mechanical Seal Technician" and saw nothing, even
 * though the feed held near roles such as "Rotating Equipment Maintenance Technician". The
 * search box only did a literal substring test, so a posting phrased differently was invisible.
 *
 * This expands a query *within the postings already loaded* — it never asks a provider for new
 * results and never invents a posting. A query matches a posting when the posting's title,
 * company, country, summary or skills contains the query itself, or any synonym of a trade the
 * query names. The synonyms are the vocabulary a board actually uses for the same job.
 *
 * Matching is whole-word for a complete trade term, so "pump" does not fire inside "pumpkin".
 * A *partial* term (not yet a full trade phrase) is matched as a word prefix, so the list keeps
 * narrowing while the candidate is still typing ("rotat" finds "Rotating Equipment").
 */
object QueryExpansion {

    /**
     * Synonym groups for the candidate's trades, indexed for the cluster links below. Groups are
     * kept specific: only genuinely interchangeable phrases share a group, because a loose group
     * is how a "pump" search starts returning unrelated jobs.
     */
    private val groups: List<List<String>> = listOf(
        // 0. Mechanical seals — the original failing query.
        listOf(
            "mechanical seal", "mechanical seals", "seal technician", "seal fitter",
            "seal repair", "gland packing", "seal kit", "seal face", "sealing",
        ),
        // 1. Pumps and pumping systems.
        listOf(
            "pump", "pumps", "pump technician", "pump mechanic", "pump fitter",
            "pump maintenance", "centrifugal pump", "pump station",
        ),
        // 2. Rotating equipment.
        listOf(
            "rotating equipment", "rotating machinery", "rotating equipment technician",
            "rotating equipment mechanic", "turbo machinery", "turbomachinery",
        ),
        // 3. Component repair / rebuild workshop.
        listOf(
            "component repair", "component overhaul", "repair technician", "rebuild",
            "overhaul", "workshop technician", "bench technician", "machine shop",
        ),
        // 4. Field / site service.
        listOf(
            "field service", "field service technician", "field service engineer",
            "site services", "onsite services", "on-site services", "site technician",
            "field technician", "service technician", "maintenance engineer",
        ),
        // 5. Industrial / plant maintenance.
        listOf(
            "industrial maintenance", "plant maintenance", "production maintenance",
            "maintenance technician", "maintenance mechanic", "maintenance fitter",
            "preventive maintenance", "predictive maintenance", "maintenance",
        ),
        // 6. Mechanical fitter / millwright.
        listOf(
            "mechanical fitter", "fitter", "millwright", "mechanical technician",
            "mechanical mechanic",
        ),
        // 7. Mining / heavy industry maintenance.
        listOf(
            "mining maintenance", "mine maintenance", "mining technician", "fixed plant",
            "processing plant", "plant operator maintainer",
        ),
        // 8. Heavy equipment / diesel.
        listOf(
            "diesel mechanic", "heavy equipment mechanic", "heavy duty mechanic",
            "earthmoving mechanic",
        ),
        // 9. Condition monitoring / alignment.
        listOf(
            "vibration analysis", "condition monitoring", "laser alignment",
            "alignment technician", "thermography",
        ),
    )

    /**
     * Groups that are adjacent in the real labour market. A query naming a trade in one of these
     * clusters also reaches the other groups in that cluster, which is what lets a "Mechanical
     * Seal Technician" query find a "Rotating Equipment Maintenance Technician". The clusters are
     * hand-picked and small: a pump-and-seal fitter is hired for rotating equipment and plant
     * maintenance, not for every job on the board.
     */
    private val clusters: List<Set<Int>> = listOf(
        // Core mechanical maintenance trades.
        setOf(0, 1, 2, 3, 4, 5, 6),
        // Rotating equipment work in mining and heavy industry.
        setOf(2, 4, 5, 7, 8, 9),
    )

    /** Every synonym phrase, lowercased, for quick membership checks. */
    private val allPhrases: Set<String> = groups.flatten().toSet()

    /**
     * The query itself, plus every synonym of the trade clusters the query names. Returned
     * lowercased and de-duplicated, most specific first so the literal query always leads.
     */
    fun expand(query: String): List<String> {
        val q = query.trim().lowercase()
        if (q.isEmpty()) return emptyList()
        val matched = matchedGroupIndices(q)
        val reached = matched + clusters.filter { cluster -> cluster.any { it in matched } }.flatten()
        return (listOf(q) + reached.distinct().flatMap { groups[it] }).distinct()
    }

    /**
     * True when [haystack] contains the query, a synonym of a trade the query names, or — for a
     * partial term the candidate is still typing — a word that starts with the query.
     */
    fun matches(query: String, haystack: String): Boolean {
        val q = query.trim().lowercase()
        if (q.isEmpty()) return true
        val text = haystack.lowercase()
        if (Phrases.contains(text, q)) return true
        val terms = expand(q)
        if (terms.any { Phrases.contains(text, it) }) return true
        // Live typing: a term that is not itself a complete trade phrase may match as a word
        // prefix. A complete term ("pump") is deliberately excluded, so it cannot match "pumpkin".
        return !isTradeQuery(q) && matchesWordPrefix(text, q)
    }

    /** True when [query] names a trade this taxonomy knows, so the UI can say why a hit appeared. */
    fun isTradeQuery(query: String): Boolean = matchedGroupIndices(query.trim().lowercase()).isNotEmpty()

    /** All phrases known to the taxonomy; exposed for diagnostics and tests. */
    fun knownPhrases(): Set<String> = allPhrases

    private fun matchedGroupIndices(query: String): List<Int> =
        groups.indices.filter { index -> groups[index].any { Phrases.contains(query, it) } }

    /**
     * Whole-word-prefix match: every token but the last must appear as a complete word, and the
     * last token must begin a word. Used only for partial input, so "rotat" narrows to "Rotating"
     * while a finished trade term stays exact.
     */
    private fun matchesWordPrefix(text: String, query: String): Boolean {
        val tokens = query.split(Regex("\\s+")).filter { it.isNotBlank() }
        if (tokens.isEmpty()) return false
        val last = tokens.last()
        if (last.length < 3) return false
        val prefix = tokens.dropLast(1).joinToString(" ")
        if (prefix.isNotEmpty() && !Phrases.contains(text, prefix)) return false
        val at = text.indexOf(last)
        if (at < 0) return false
        val boundaryBefore = at == 0 || !text[at - 1].isLetterOrDigit()
        return boundaryBefore
    }
}
