package com.aiglobal.jobfinder.data.jobs.provider

import androidx.compose.runtime.Immutable

/**
 * What one registered source really is, written as data rather than prose.
 *
 * The audit is the single place that states, per source: where its postings come from
 * ([DataSourceKind], so a fixture or a mock is never read as live), what the API actually
 * supports ([capabilities], so the app does not promise a targeted search a source cannot
 * do), and what stands between the app and the feed — a required key and/or a host that is
 * known to challenge automated requests.
 *
 * Nothing here is a live measurement. Whether a source answered *this time* is recorded
 * separately, on [com.aiglobal.jobfinder.data.model.ProviderDiagnostic]; the audit records
 * only what is true of the source itself, from its own documentation and from what the feed
 * does with a parameter. Keeping the two apart is what stops the audit from ever claiming a
 * live check happened when it did not.
 */
@Immutable
data class ProviderAudit(
    val id: String,
    val name: String,
    val kind: ProviderKind,
    val dataSource: DataSourceKind,
    /** Official API or developer-documentation page, so the source's own limits can be read. */
    val documentationUrl: String,
    val capabilities: ProviderCapabilities,
    /** True when the source stays dormant until the user saves an API key, by its own terms. */
    val requiresCredentials: Boolean,
    /**
     * True when the host is documented to answer automated requests with a challenge (for
     * example a Cloudflare interstitial). Recorded as a fact about the host so the app can
     * report the failure honestly instead of reading the challenge as "no jobs".
     */
    val botGated: Boolean = false,
    /**
     * True when the feed's location field is the employer's own place of work, so a
     * Madagascar applicant can be judged against it. False when the source publishes no
     * usable location, in which case eligibility stays unknown rather than guessed.
     */
    val locationIsMeaningful: Boolean = true,
    /**
     * The curated employer portals this source actually reads, by their published names
     * (e.g. "Rio Tinto", "SGS"). Empty for a source that is a single feed rather than a
     * family of employer boards. Taken straight from the same curated lists the providers
     * walk, so the status screen can name the employers instead of showing only the
     * aggregating source — and can never list a portal the feed does not read.
     */
    val portals: List<String> = emptyList(),
) {
    /** True when this source's postings are fetched from the real API rather than a fixture. */
    val isLive: Boolean get() = dataSource == DataSourceKind.LIVE

    /**
     * True when the app is expected to be able to read this source without the user doing
     * anything. A documentation fact, not a guarantee the source answered on the last attempt.
     */
    val reachableWithoutSetup: Boolean get() = !requiresCredentials && !botGated

    /** Short, honest label for how the source is reached. */
    val reachabilityLabel: String
        get() = when {
            requiresCredentials && botGated -> "Key required · bot-gated"
            botGated -> "Bot-gated"
            requiresCredentials -> "Needs a key"
            else -> "Key-free"
        }
}

/**
 * The audit of every registered source, derived from the providers themselves so it cannot
 * drift from what the app actually runs. This answers "which sources are real, and how are
 * they reached?", and it is built from live provider instances rather than a hand-maintained
 * table.
 */
object ProviderAuditRegistry {

    /** One audit row per provider, in the order the providers were supplied. */
    fun audit(providers: List<JobProvider>): List<ProviderAudit> = providers.map { provider ->
        ProviderAudit(
            id = provider.id,
            name = provider.name,
            kind = provider.kind,
            dataSource = provider.dataSource,
            documentationUrl = provider.documentationUrl,
            capabilities = provider.capabilities,
            requiresCredentials = provider.requiresCredentials,
            botGated = provider.id in BOT_GATED_IDS,
            portals = CuratedPortals.namesFor(provider.id),
        )
    }

    /**
     * The employer portals each multi-employer source reads, by the names the employers
     * publish. Read from the very lists the providers walk ([WorkdayBoards], [GreenhouseBoards],
     * [AshbyBoards], [LeverBoards], [SmartRecruitersBoards]), so the status screen can name the
     * curated employers and can never show a portal the feed does not actually query.
     *
     * A source that is a single feed (Arbeitnow, Remotive, Jobicy, Himalayas, EURES, Adzuna,
     * Jooble) maps to an empty list: it is one source, not a family of employer boards.
     */
    private object CuratedPortals {
        private val byProviderId: Map<String, List<String>> = mapOf(
            "workday" to WorkdayBoards.SITES.map { it.label },
            "greenhouse" to GreenhouseBoards.BOARDS.map { it.label },
            "ashby" to AshbyBoards.BOARDS.map { it.label },
            "lever" to LeverBoards.BOARDS.map { it.label },
            "smartrecruiters" to SmartRecruitersBoards.BOARDS.map { it.label },
        )

        fun namesFor(providerId: String): List<String> = byProviderId[providerId].orEmpty()
    }

    /**
     * Key-free hosts known to gate automated traffic. Jooble answers an unauthenticated
     * request with a Cloudflare challenge; the app classifies that as an error, never as an
     * empty result. Kept here as a documented fact about the host, not a live measurement.
     */
    private val BOT_GATED_IDS = setOf("jooble")
}
