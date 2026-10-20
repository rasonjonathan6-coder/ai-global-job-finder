package com.aiglobal.jobfinder

import com.aiglobal.jobfinder.data.jobs.JobMapper
import com.aiglobal.jobfinder.data.jobs.engine.EligibilityEngine
import com.aiglobal.jobfinder.data.jobs.engine.MatchingEngine
import com.aiglobal.jobfinder.data.jobs.engine.RecordValidationEngine
import com.aiglobal.jobfinder.data.jobs.freshness.JobFreshnessEngine
import com.aiglobal.jobfinder.data.jobs.provider.ArbeitnowProvider
import com.aiglobal.jobfinder.data.jobs.provider.AshbyProvider
import com.aiglobal.jobfinder.data.jobs.provider.EuresProvider
import com.aiglobal.jobfinder.data.jobs.provider.GreenhouseProvider
import com.aiglobal.jobfinder.data.jobs.provider.HimalayasProvider
import com.aiglobal.jobfinder.data.jobs.provider.HttpJsonFeed
import com.aiglobal.jobfinder.data.jobs.provider.JobProvider
import com.aiglobal.jobfinder.data.jobs.provider.JobicyProvider
import com.aiglobal.jobfinder.data.jobs.provider.LeverProvider
import com.aiglobal.jobfinder.data.jobs.provider.RawJob
import com.aiglobal.jobfinder.data.jobs.provider.RemotiveProvider
import com.aiglobal.jobfinder.data.jobs.provider.SmartRecruitersProvider
import com.aiglobal.jobfinder.data.jobs.provider.WorkdayProvider
import com.aiglobal.jobfinder.data.jobs.search.RelevanceFeed
import com.aiglobal.jobfinder.data.jobs.search.RoleClassifier
import com.aiglobal.jobfinder.data.jobs.search.SearchStrategy
import com.aiglobal.jobfinder.data.jobs.search.TargetSearch
import com.aiglobal.jobfinder.data.model.CandidateProfile
import com.aiglobal.jobfinder.data.model.MadagascarEligibility
import com.aiglobal.jobfinder.data.model.VisaSupport
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.io.File

/**
 * Stage 71 — live search quality validation.
 *
 * This harness is **not** part of the normal suite: it performs real network reads against
 * the key-free public boards the app ships, then runs the real engines (validation,
 * relevance classification, mapper, freshness) over what actually came back. It is enabled
 * only when `LIVE_SEARCH=1` is set in the environment, so CI and the default `test` task
 * stay offline and deterministic. When it does run it prints an honest report and writes a
 * machine-readable copy to `web/stage71-live-search.json`.
 *
 * It deliberately reports reality: per source, how many postings arrived, how many the
 * validator kept, how many the trade relevance filter retained, and how many it set aside.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33])
class Stage71LiveSearchHarness {

    private val profile = CandidateProfile(
        fullName = "Jonathan Rakoto",
        email = "jonathan.rakoto@example.mg",
        location = "Antananarivo, Madagascar",
        currentTitle = "Mechanical Seal Technician",
        previousTitles = listOf("Pump Technician", "Mechanical Maintenance Technician"),
        yearsOfExperience = 8,
        skills = listOf(
            "Mechanical seals", "Centrifugal pumps", "Rotating equipment",
            "Preventive maintenance", "Alignment", "Hydraulics",
        ),
        preferredCountries = listOf("Canada", "Germany", "Australia", "South Africa"),
    )

    /** Every registered key-free source the app actually runs, minus the key-gated ones. */
    private fun providers(): List<JobProvider> = listOf(
        ArbeitnowProvider(),
        RemotiveProvider(),
        JobicyProvider(),
        HimalayasProvider(),
        EuresProvider(feed = HttpJsonFeed),
        WorkdayProvider(feed = HttpJsonFeed),
        GreenhouseProvider(feed = HttpJsonFeed),
        AshbyProvider(feed = HttpJsonFeed),
        LeverProvider(feed = HttpJsonFeed),
        SmartRecruitersProvider(feed = HttpJsonFeed),
    )

    @Test
    fun liveSearchReportsHonestPerSourceCoverage() = runBlocking {
        assumeTrue("set LIVE_SEARCH=1 to run the real network probe", System.getenv("LIVE_SEARCH") == "1")

        val now = System.currentTimeMillis()
        val providers = providers()
        val titles = (listOf(profile.currentTitle) + profile.previousTitles + profile.preferredCountries)
            .filter { it.isNotBlank() }
        val keywordPlan = TargetSearch.keywordPlan(titles)

        val outcomes = coroutineScope {
            providers.map { provider ->
                async(Dispatchers.IO) {
                    val plan = SearchStrategy.planFor(provider, titles)
                    val fetched = mutableListOf<RawJob>()
                    var failure: Throwable? = null
                    for (keywords in plan.keywordSets) {
                        val outcome = runCatching {
                            withTimeoutOrNull(45_000) {
                                provider.fetch(keywords = keywords, limit = 60)
                            } ?: throw RuntimeException("timeout")
                        }
                        outcome.getOrNull()?.let { fetched += it }
                        if (outcome.isFailure && failure == null) failure = outcome.exceptionOrNull()
                    }
                    Triple(provider, plan.serverSideSearch, Pair(fetched, failure))
                }
            }.awaitAll()
        }

        val validation = RecordValidationEngine()
        val allRaw = mutableListOf<RawJob>()
        val perSource = linkedMapOf<String, SourceLine>()
        for ((provider, targeted, payload) in outcomes) {
            val (fetched, failure) = payload
            allRaw += fetched
            perSource[provider.id] = SourceLine(
                name = provider.name,
                targeted = targeted,
                returned = fetched.size,
                failure = failure?.let { "${it.javaClass.simpleName}: ${it.message}" },
            )
        }

        val dedup = validation.deduplicate(allRaw, now = now)
        val kept = dedup.kept

        // Relevance verdict per kept posting, using the exact engine the pipeline uses.
        val relevant = mutableListOf<RawJob>()
        val relevantByProvider = mutableMapOf<String, Int>()
        val totalByProvider = mutableMapOf<String, Int>()
        val retainedTitles = mutableListOf<String>()
        val excludedTitles = mutableListOf<String>()
        // Titles that read like a trade word but were still dropped are the false-negative
        // candidates worth reviewing: a genuine pump/seal/fitter role the classifier missed.
        val nearMiss = mutableListOf<String>()
        val tradeWords = listOf(
            "pump", "seal", "fitter", "millwright", "maintenance", "mechanic",
            "technician", "rotating", "electrician", "welder", "machinist", "valve",
        )
        for (raw in kept) {
            totalByProvider[raw.providerId] = (totalByProvider[raw.providerId] ?: 0) + 1
            val verdict = RoleClassifier.classify(raw.title, plain(raw.descriptionHtml).take(400))
            if (verdict.isMaintenanceFamily) {
                relevant += raw
                relevantByProvider[raw.providerId] = (relevantByProvider[raw.providerId] ?: 0) + 1
                retainedTitles += "${raw.providerId} | ${raw.title} | ${raw.company} | ${raw.location}"
            } else {
                excludedTitles += "${raw.providerId} | ${raw.title} | ${raw.company}"
                val lower = raw.title.lowercase()
                if (tradeWords.any { lower.contains(it) }) {
                    nearMiss += "${raw.providerId} | ${raw.title} | ${verdict.family} | ${raw.company}"
                }
            }
        }

        val mapper = JobMapper(matchingEngine = MatchingEngine(), eligibilityEngine = EligibilityEngine(), clock = { now })
        val mapped = kept.map { mapper.toJob(it, profile) }
        val feedRelevant = RelevanceFeed.relevant(mapped)

        val freshness = JobFreshnessEngine()
        val freshBuckets = mapped.groupingBy { freshness.assess(it, now).freshness }.eachCount()

        val report = buildString {
            appendLine("==================== STAGE 71 LIVE SEARCH ====================")
            appendLine("run at epoch=$now")
            appendLine("keyword plan = $keywordPlan")
            appendLine()
            appendLine("--- PER SOURCE ---")
            for ((id, line) in perSource) {
                val rel = relevantByProvider[id] ?: 0
                val tot = totalByProvider[id] ?: 0
                appendLine(
                    "%-16s %-28s returned=%-4d kept=%-4d relevant=%-3d excluded=%-4d targeted=%s%s"
                        .format(
                            id, line.name, line.returned, tot, rel, (tot - rel), line.targeted,
                            line.failure?.let { "  FAILURE=$it" } ?: "",
                        ),
                )
            }
            appendLine()
            appendLine("--- TOTALS ---")
            appendLine("returned=${allRaw.size}  keptAfterDedup=${kept.size}  relevant=${relevant.size}  excluded=${kept.size - relevant.size}")
            appendLine("duplicatesRemoved=${allRaw.size - kept.size}")
            appendLine("coverageSummary=${RelevanceFeed.summary(kept.size, relevant.size)}")
            appendLine("feedRelevantAfterMap=${feedRelevant.size}")
            appendLine()
            appendLine("--- FRESHNESS (mapped) ---")
            appendLine(freshBuckets.entries.joinToString { "${it.key}=${it.value}" })
            appendLine()
            appendLine("--- ELIGIBILITY (mapped) ---")
            appendLine(
                mapped.groupingBy { it.eligibility }.eachCount().entries
                    .joinToString { "${it.key}=${it.value}" },
            )
            appendLine(
                mapped.groupingBy { it.visaSupport }.eachCount().entries
                    .joinToString { "${it.key}=${it.value}" },
            )
            appendLine()
            appendLine("--- RETAINED (trade-relevant) TITLES ---")
            retainedTitles.sorted().forEach { appendLine(it) }
            appendLine()
            appendLine("--- NEAR MISS (trade word in title but excluded) ---")
            nearMiss.sorted().forEach { appendLine(it) }
            appendLine()
            appendLine("--- EXCLUDED TITLES (all) ---")
            excludedTitles.sorted().forEach { appendLine(it) }
            appendLine("============================================================")
        }

        withContext(Dispatchers.IO) {
            println(report)
            val out = File("../web/stage71-live-search.json")
            out.writeText(
                jsonReport(now, perSource, relevantByProvider, totalByProvider, allRaw.size, kept.size, relevant.size, mapped)
                    .let { base ->
                        val obj = JSONObject(base)
                        obj.put("retainedTitles", JSONArray(retainedTitles.sorted()))
                        obj.put("nearMiss", JSONArray(nearMiss.sorted()))
                        obj.put("excludedTitles", JSONArray(excludedTitles.sorted()))
                        obj.toString(2)
                    },
            )
        }

        // The probe must actually have reached the boards; a network-less run is not a result.
        assertTrue("expected at least one source to answer", perSource.values.any { it.returned > 0 })
    }

    private data class SourceLine(
        val name: String,
        val targeted: Boolean,
        val returned: Int,
        val failure: String?,
    )

    private fun plain(html: String): String =
        com.aiglobal.jobfinder.data.jobs.provider.HtmlText.toPlainText(html)

    private fun jsonReport(
        now: Long,
        perSource: Map<String, SourceLine>,
        relevantByProvider: Map<String, Int>,
        totalByProvider: Map<String, Int>,
        returned: Int,
        kept: Int,
        relevant: Int,
        mapped: List<com.aiglobal.jobfinder.data.model.Job>,
    ): String {
        val sources = JSONArray()
        for ((id, line) in perSource) {
            sources.put(
                JSONObject()
                    .put("providerId", id)
                    .put("name", line.name)
                    .put("targeted", line.targeted)
                    .put("returned", line.returned)
                    .put("kept", totalByProvider[id] ?: 0)
                    .put("relevant", relevantByProvider[id] ?: 0)
                    .put("excluded", (totalByProvider[id] ?: 0) - (relevantByProvider[id] ?: 0))
                    .put("failure", line.failure ?: JSONObject.NULL),
            )
        }
        val eligibility = JSONObject()
        mapped.groupingBy { it.eligibility }.eachCount().forEach { (k, v) -> eligibility.put(k.name, v) }
        val visa = JSONObject()
        mapped.groupingBy { it.visaSupport }.eachCount().forEach { (k, v) -> visa.put(k.name, v) }
        return JSONObject()
            .put("stage", 71)
            .put("generatedAtEpochMillis", now)
            .put("candidateTitle", profile.currentTitle)
            .put("returned", returned)
            .put("kept", kept)
            .put("relevant", relevant)
            .put("excluded", kept - relevant)
            .put("sources", sources)
            .put("eligibility", eligibility)
            .put("visaSupport", visa)
            .toString(2)
    }
}
