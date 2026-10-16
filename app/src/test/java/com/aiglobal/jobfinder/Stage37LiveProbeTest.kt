package com.aiglobal.jobfinder

import com.aiglobal.jobfinder.data.expat.ExpatBenefitExtractor
import com.aiglobal.jobfinder.data.geo.CountryCatalog
import com.aiglobal.jobfinder.data.jobs.JobMapper
import com.aiglobal.jobfinder.data.jobs.engine.EligibilityEngine
import com.aiglobal.jobfinder.data.jobs.engine.MatchingEngine
import com.aiglobal.jobfinder.data.jobs.engine.RecordRejectionReason
import com.aiglobal.jobfinder.data.jobs.engine.RecordValidationEngine
import com.aiglobal.jobfinder.data.jobs.freshness.Freshness
import com.aiglobal.jobfinder.data.jobs.freshness.JobFreshnessEngine
import com.aiglobal.jobfinder.data.jobs.provider.HttpJsonFeed
import com.aiglobal.jobfinder.data.jobs.provider.WorkdayBoards
import com.aiglobal.jobfinder.data.jobs.provider.WorkdayProvider
import com.aiglobal.jobfinder.data.model.CandidateProfile
import kotlinx.coroutines.runBlocking
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * Stage 37 live probe.
 *
 * **Not** part of the normal unit suite: it hits the real Workday boards (Weir, Sulzer, EPSA,
 * First Quantum) and is skipped unless `LIVE_PROBE=1` is set. It exists so the Stage 37 claims
 * — that a returned offer is current, relevant and carries what is needed to apply from
 * Madagascar — are backed by the app's own provider and engines against live data, not a mock.
 *
 * Run it with:
 *   LIVE_PROBE=1 ./gradlew :app:testDebugUnitTest --tests '*Stage37LiveProbeTest' --rerun-tasks -i
 *
 * It asserts nothing about how many postings exist (that changes daily); it prints the evidence
 * and only fails if a *structural* guarantee is broken: a carried publication date that the
 * freshness engine calls undated, an unresolved country, or a closed posting left in the feed.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33])
class Stage37LiveProbeTest {

    private val profile = CandidateProfile(
        fullName = "Probe Candidate",
        currentTitle = "Maintenance Technician",
        previousTitles = listOf("Mechanical Technician"),
        yearsOfExperience = 8,
        skills = listOf(
            "preventive maintenance", "hydraulics", "pneumatics", "pumps", "bearings",
            "alignment", "welding", "troubleshooting", "mechanical",
        ),
        preferredRoles = listOf("Maintenance Technician", "Mechanical Fitter"),
    )

    @Test
    fun probe() = runBlocking {
        assumeTrue("set LIVE_PROBE=1 to run the live probe", System.getenv("LIVE_PROBE") == "1")

        println("==== STAGE 37 LIVE PROBE: current / relevant / applicable ====")

        val mapper = JobMapper(
            matchingEngine = MatchingEngine(),
            eligibilityEngine = EligibilityEngine(),
            benefitExtractor = ExpatBenefitExtractor(),
        )
        val validation = RecordValidationEngine()
        val freshnessEngine = JobFreshnessEngine()
        val now = System.currentTimeMillis()

        // Focus on the boards added for Gulf/Africa expat roles.
        val focus = WorkdayBoards.SITES.filter {
            it.tenant in setOf("weir", "sulzer", "epsa", "firstquantum")
        }
        val provider = WorkdayProvider(feed = HttpJsonFeed, sites = focus)

        val raw = provider.fetch(
            keywords = listOf("maintenance", "fitter", "mechanic"),
            limit = 40,
        )
        println("workday fetched=${raw.size} sites=${focus.map { it.label }}")

        val closed = raw.count { it.closedToNewApplicants }
        val undated = raw.count { it.postedAtEpochMillis == null }
        println("closed_to_new_applicants=$closed undated=$undated")

        val dedup = validation.deduplicate(raw, now)
        val closedRejected = dedup.summaries
            .sumOf { it.rejectedByReason[RecordRejectionReason.CLOSED_TO_APPLICANTS] ?: 0 }
        println("kept=${dedup.kept.size} closed_rejected=$closedRejected")
        check(closedRejected == closed) { "every closed posting must be rejected; $closed stated, $closedRejected rejected" }

        val jobs = dedup.kept.map { mapper.toJob(it, profile) }

        // Report the best opportunities with the three facts the stage is about.
        jobs.sortedByDescending { it.matchScore }.take(15).forEach { job ->
            val fresh = freshnessEngine.assess(job, now)
            val flag = job.countryFlag.ifBlank { "??" }
            val known = CountryCatalog.isKnownCountry(job.country)
            println(
                "  ${job.matchScore}% ${job.title} | ${job.company} | $flag ${job.country} (known=$known) | " +
                    "${fresh.freshness} | ${job.roleFamily} | ${job.eligibility}",
            )
        }

        // Structural guarantees, independent of how many postings the boards happen to return.
        jobs.forEach { job ->
            val fresh = freshnessEngine.assess(job, now)
            check(!(job.postedAtEpochMillis != null && fresh.freshness == Freshness.UNKNOWN)) {
                "a stated date must not be classified as undated: ${job.title}"
            }
            check(job.country.isNotBlank() && CountryCatalog.isKnownCountry(job.country)) {
                "country must resolve for eligibility: ${job.title} -> '${job.country}'"
            }
            check(job.sourceUrl.startsWith("http")) { "application link must be present: ${job.title}" }
        }
        println("structural checks passed for ${jobs.size} kept postings")
    }
}
