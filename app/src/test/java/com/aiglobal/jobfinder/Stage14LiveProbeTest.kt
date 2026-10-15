package com.aiglobal.jobfinder

import com.aiglobal.jobfinder.data.expat.ExpatBenefitExtractor
import com.aiglobal.jobfinder.data.jobs.JobMapper
import com.aiglobal.jobfinder.data.jobs.engine.EligibilityEngine
import com.aiglobal.jobfinder.data.jobs.engine.MatchingEngine
import com.aiglobal.jobfinder.data.jobs.provider.AdzunaCredentials
import com.aiglobal.jobfinder.data.jobs.provider.AdzunaProvider
import com.aiglobal.jobfinder.data.jobs.provider.ArbeitnowProvider
import com.aiglobal.jobfinder.data.jobs.provider.HimalayasProvider
import com.aiglobal.jobfinder.data.jobs.provider.JobProvider
import com.aiglobal.jobfinder.data.jobs.provider.JobProviderException
import com.aiglobal.jobfinder.data.jobs.provider.JobicyProvider
import com.aiglobal.jobfinder.data.jobs.provider.ProviderErrorCategory
import com.aiglobal.jobfinder.data.jobs.provider.RawJob
import com.aiglobal.jobfinder.data.jobs.provider.RemotiveProvider
import com.aiglobal.jobfinder.data.model.BenefitStatus
import com.aiglobal.jobfinder.data.model.CandidateProfile
import com.aiglobal.jobfinder.data.model.MadagascarEligibility
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * Stage 14 live retrieval probe.
 *
 * This is **not** part of the normal unit suite: it hits the real provider APIs and is
 * skipped unless `LIVE_PROBE=1` is set in the environment. It exists so the retrieval
 * claims in the Stage 14 report can be backed by output produced by the app's own
 * providers and engines rather than by a mock.
 *
 * It never writes a key anywhere: the credential-gated sources are exercised only in
 * their unconfigured state (which must report `NOT_CONFIGURED`, not a fabricated job).
 *
 * Robolectric is required even though there is no UI here: the providers parse with
 * `org.json`, which is a stub on a plain JVM and would return null for every field.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33])
class Stage14LiveProbeTest {

    private val targetRoles = listOf(
        "Maintenance Technician",
        "Industrial Maintenance Technician",
        "Pump Technician",
        "Mechanical Fitter",
        "Rotating Equipment Technician",
        "Mining Maintenance Technician",
    )

    /** A maintenance-technician candidate, so the engines score against a real CV shape. */
    private val profile = CandidateProfile(
        fullName = "Probe Candidate",
        currentTitle = "Maintenance Technician",
        previousTitles = listOf("Mechanical Technician", "Industrial Maintenance Technician"),
        yearsOfExperience = 8,
        skills = listOf(
            "preventive maintenance", "hydraulics", "pneumatics", "pumps", "bearings",
            "alignment", "welding", "CMMS", "troubleshooting", "mechanical",
        ),
        preferredRoles = listOf("Maintenance Technician", "Mechanical Fitter"),
    )

    private val mapper = JobMapper(
        matchingEngine = MatchingEngine(),
        eligibilityEngine = EligibilityEngine(),
        benefitExtractor = ExpatBenefitExtractor(),
    )

    private val keyFreeProviders: List<JobProvider> = listOf(
        ArbeitnowProvider(),
        RemotiveProvider(),
        JobicyProvider(),
        HimalayasProvider(),
    )

    @Test
    fun probe() = runBlocking {
        assumeTrue("set LIVE_PROBE=1 to run the live retrieval probe", System.getenv("LIVE_PROBE") == "1")

        val startedAt = System.currentTimeMillis()
        println("==== STAGE 14 LIVE PROBE ====")
        println("started_at_epoch_ms=$startedAt (${java.time.Instant.ofEpochMilli(startedAt)})")
        println("roles=$targetRoles")

        val retrieved = mutableListOf<Pair<String, RawJob>>() // providerId -> raw
        val perProvider = LinkedHashMap<String, Int>()

        for (provider in keyFreeProviders) {
            // Providers that support server-side search get each target role; the rest
            // ignore keywords and are fetched once.
            val keywordSets = if (provider.supportsServerSideSearch) {
                targetRoles.map { listOf(it) }
            } else {
                listOf(emptyList())
            }
            var count = 0
            for (keywords in keywordSets) {
                try {
                    val jobs = provider.fetch(keywords = keywords, limit = 60)
                    count += jobs.size
                    jobs.forEach { retrieved += provider.id to it }
                } catch (t: Throwable) {
                    println("PROVIDER_FAIL provider=${provider.id} keywords=$keywords error=${t.message}")
                }
            }
            perProvider[provider.id] = count
            println("provider=${provider.id} fetched=$count")
        }

        // Credential-gated source must report NOT_CONFIGURED, never invent a posting.
        val adzuna = AdzunaProvider(credentials = { AdzunaCredentials("", "") })
        val adzunaOutcome = runCatching { adzuna.fetch(emptyList(), 60) }
        val adzunaCategory = (adzunaOutcome.exceptionOrNull() as? JobProviderException)?.category
        println("provider=adzuna configured=false category=$adzunaCategory")
        assertEquals(ProviderErrorCategory.NOT_CONFIGURED, adzunaCategory)

        val unique = retrieved
            .distinctBy { (providerId, raw) -> "$providerId:${raw.externalId}" }
        println("total_raw=${retrieved.size} unique=${unique.size}")

        // Map every posting through the real pipeline, then rank by match score.
        val scored = unique
            .map { (_, raw) -> mapper.toJob(raw, profile) }
            .sortedByDescending { it.matchScore }

        println("---- TOP 15 BY MATCH SCORE ----")
        scored.take(15).forEach { job ->
            val evidence = job.benefitEvidence
                .filter { it.status != BenefitStatus.UNKNOWN }
                .joinToString("; ") { "${it.type.key}=${it.status}(${it.evidence.take(70)})" }
                .ifBlank { "none documented" }
            println(
                "score=${job.matchScore}% title=\"${job.title}\" company=\"${job.company}\" " +
                    "country=\"${job.country}${job.countryFlag}\" city=\"${job.city}\" " +
                    "eligibility=${job.eligibility} visa=${job.visaSupport} " +
                    "salary=${job.salaryMin}-${job.salaryMax} ${job.currencyCode}/${job.salaryPeriod} " +
                    "source=${job.source} url=${redact(job.sourceUrl)}",
            )
            println("    benefits: $evidence")
        }

        val eligibilityCounts = scored.groupingBy { it.eligibility }.eachCount()
        println("eligibility_counts=$eligibilityCounts")
        println("eligible_or_sponsored=${scored.count { it.eligibility == MadagascarEligibility.ELIGIBLE || it.eligibility == MadagascarEligibility.VISA_SPONSORED }}")
        println("score>=60=${scored.count { it.matchScore >= 60 }}")
        println("with_source_url=${scored.count { it.sourceUrl.isNotBlank() }}")
        println("with_documented_benefit=${scored.count { j -> j.benefitEvidence.any { it.status == BenefitStatus.CONFIRMED } }}")

        // ---- Maintenance-family breakdown --------------------------------------
        // The remote boards ignore search terms (Stage 9), so the question is not
        // "what did the keyword return" but "did any maintenance role arrive at all".
        val familyWords = listOf(
            "maintenance", "mechanic", "fitter", "pump", "rotating", "mining",
            "millwright", "technician", "industrial", "plant",
        )
        println("---- TITLE KEYWORD COUNTS (unique feed) ----")
        familyWords.forEach { word ->
            val n = scored.count { it.title.lowercase().contains(word) }
            println("  \"$word\" -> $n")
        }
        val family = scored.filter { job ->
            familyWords.any { job.title.lowercase().contains(it) }
        }
        println("maintenance_family_total=${family.size}")
        println("---- MAINTENANCE-FAMILY POSTINGS (all, by score) ----")
        family.sortedByDescending { it.matchScore }.forEach { job ->
            println(
                "score=${job.matchScore}% \"${job.title}\" @ ${job.company} " +
                    "country=\"${job.country}\" eligibility=${job.eligibility} source=${job.source}",
            )
        }

        val finishedAt = System.currentTimeMillis()
        println("finished_at_epoch_ms=$finishedAt duration_ms=${finishedAt - startedAt}")
        println("==== END STAGE 14 LIVE PROBE ====")
    }

    private fun redact(url: String): String {
        val q = url.indexOf('?')
        return if (q < 0) url else url.substring(0, q) + "?<redacted>"
    }
}
