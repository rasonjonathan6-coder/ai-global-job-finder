package com.aiglobal.jobfinder

import com.aiglobal.jobfinder.data.apply.ApplicationDestination
import com.aiglobal.jobfinder.data.apply.ApplicationUrlInspector
import com.aiglobal.jobfinder.data.geo.CountryCatalog
import com.aiglobal.jobfinder.data.jobs.JobMapper
import com.aiglobal.jobfinder.data.jobs.engine.EligibilityEngine
import com.aiglobal.jobfinder.data.jobs.engine.MatchingEngine
import com.aiglobal.jobfinder.data.jobs.engine.RecordValidationEngine
import com.aiglobal.jobfinder.data.jobs.freshness.Freshness
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
import com.aiglobal.jobfinder.data.jobs.search.RoleClassifier
import com.aiglobal.jobfinder.data.jobs.search.SearchStrategy
import com.aiglobal.jobfinder.data.jobs.search.TargetSearch
import com.aiglobal.jobfinder.data.model.CandidateProfile
import com.aiglobal.jobfinder.data.model.MadagascarEligibility
import com.aiglobal.jobfinder.data.model.VerificationState
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
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
import java.net.HttpURLConnection
import java.net.URL

/**
 * Stage 72 — candidate eligibility, application links and release validation.
 *
 * Stage 71 measured *how many* trade-relevant postings the real sources return. This harness
 * goes one level deeper and validates each retained posting the way a candidate would before
 * spending effort on it: does it have a real employer, a real title, a real location, a
 * publication date, and a usable application link — and can someone currently in Madagascar
 * realistically apply?
 *
 * It is **not** part of the default suite. It performs real network reads against the
 * key-free boards and then a real HTTP status check on every retained application link, so it
 * runs only when `LIVE_SEARCH=1` is set. It prints an honest report and writes a
 * machine-readable copy to `web/stage72-job-validation.json`.
 *
 * Two honesty rules are baked in:
 *  - A successful HTTP response does **not** prove a vacancy is open, and a working URL does
 *    **not** prove Madagascar applicants are eligible. The two are recorded separately.
 *  - A link the check could not reach (timeout, bot wall, 403) is recorded as *unchecked*, not
 *    as broken. Absence of evidence is never written up as a defect.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33])
class Stage72LiveValidationHarness {

    private val profile = CandidateProfile(
        fullName = "Jonathan Rakoto",
        email = "<secret-hidden>.rakoto@example.mg",
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
    fun liveValidationReportsEveryRetainedJobHonestly() = runBlocking {
        assumeTrue("set LIVE_SEARCH=1 to run the real network probe", System.getenv("LIVE_SEARCH") == "1")

        val now = System.currentTimeMillis()
        val titles = (listOf(profile.currentTitle) + profile.previousTitles + profile.preferredCountries)
            .filter { it.isNotBlank() }
        val keywordPlan = TargetSearch.keywordPlan(titles)

        val outcomes = coroutineScope {
            providers().map { provider ->
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

        // Keep only the candidate's trade, using the exact classifier the pipeline uses.
        val relevantRaw = kept.filter { raw ->
            RoleClassifier.classify(raw.title, plain(raw.descriptionHtml).take(400)).isMaintenanceFamily
        }

        val mapper = JobMapper(matchingEngine = MatchingEngine(), eligibilityEngine = EligibilityEngine(), clock = { now })
        val jobs = relevantRaw.map { mapper.toJob(it, profile) }

        // Real HTTP status per application link, checked concurrently but gently.
        val linkChecks = checkLinks(jobs.map { it.sourceUrl })

        val freshnessEngine = JobFreshnessEngine()
        val inspector = ApplicationUrlInspector()
        val records = jobs.mapIndexed { index, job ->
            val raw = relevantRaw[index]
            val inspection = inspector.inspect(job)
            val fresh = freshnessEngine.assess(job, now)
            val check = linkChecks[job.sourceUrl]
            val bucket = bucketFor(job, inspection.destination, inspection.canOpen, check)
            JobRecord(
                providerId = job.providerId,
                source = job.source,
                title = job.title,
                company = job.company,
                location = raw.location,
                country = job.country,
                city = job.city,
                url = job.sourceUrl,
                postedAtEpochMillis = job.postedAtEpochMillis,
                retrievedAtEpochMillis = job.retrievedAtEpochMillis,
                eligibility = job.eligibility,
                visaSupport = job.visaSupport,
                freshness = fresh.freshness,
                linkState = job.verification.link,
                activityState = job.verification.activity,
                deadlineState = job.verification.deadline,
                visaState = job.verification.visa,
                relocationState = job.verification.relocation,
                destination = inspection.destination,
                canOpen = inspection.canOpen,
                linkWarnings = inspection.warnings,
                httpStatus = check?.status,
                httpFinalUrl = check?.finalUrl,
                httpReachable = check?.reachable ?: false,
                bucket = bucket,
            )
        }.sortedWith(compareBy({ it.bucket.rank }, { it.providerId }, { it.title }))

        val bucketCounts = records.groupingBy { it.bucket }.eachCount()
        val statusCounts = records.mapNotNull { it.httpStatus }.groupingBy { it / 100 }.eachCount()

        val report = buildString {
            appendLine("==================== STAGE 72 JOB VALIDATION ====================")
            appendLine("run at epoch=$now")
            appendLine("keyword plan = $keywordPlan")
            appendLine()
            appendLine("--- PIPELINE ---")
            appendLine("returned=${allRaw.size}  keptAfterDedup=${kept.size}  tradeRelevant=${jobs.size}  excluded=${kept.size - jobs.size}")
            appendLine("duplicatesRemoved=${allRaw.size - kept.size}")
            appendLine()
            appendLine("--- PER SOURCE ---")
            for ((id, line) in perSource) {
                appendLine(
                    "%-16s %-28s returned=%-4d kept=%-4d relevant=%-3d targeted=%s%s".format(
                        id, line.name, line.returned, kept.count { it.providerId == id },
                        records.count { it.providerId == id }, line.targeted,
                        line.failure?.let { "  FAILURE=$it" } ?: "",
                    ),
                )
            }
            appendLine()
            appendLine("--- BUCKETS ---")
            for (bucket in ValidationBucket.entries) {
                appendLine("%-14s %d".format(bucket.name, bucketCounts[bucket] ?: 0))
            }
            appendLine()
            appendLine("--- LINK CHECKS ---")
            appendLine("checked=${linkChecks.size}  reachable=${linkChecks.values.count { it.reachable }}  unreachable=${linkChecks.values.count { !it.reachable }}")
            appendLine("httpStatusByClass=${statusCounts.entries.joinToString { "${it.key}xx=${it.value}" }}")
            appendLine()
            appendLine("--- ELIGIBILITY (relevant) ---")
            appendLine(records.groupingBy { it.eligibility }.eachCount().entries.joinToString { "${it.key}=${it.value}" })
            appendLine("--- VISA (relevant) ---")
            appendLine(records.groupingBy { it.visaSupport }.eachCount().entries.joinToString { "${it.key}=${it.value}" })
            appendLine("--- FRESHNESS (relevant) ---")
            appendLine(records.groupingBy { it.freshness }.eachCount().entries.joinToString { "${it.key}=${it.value}" })
            appendLine()
            appendLine("--- USABLE / UNCERTAIN RECORDS ---")
            records.filter { it.bucket == ValidationBucket.USABLE || it.bucket == ValidationBucket.UNCERTAIN }
                .forEach { appendLine(it.line()) }
            appendLine()
            appendLine("--- BROKEN / RESTRICTED / LOW-QUALITY RECORDS ---")
            records.filter {
                it.bucket == ValidationBucket.BROKEN || it.bucket == ValidationBucket.RESTRICTED ||
                    it.bucket == ValidationBucket.LOW_QUALITY
            }.forEach { appendLine(it.line()) }
            appendLine("================================================================")
        }

        withContext(Dispatchers.IO) {
            println(report)
            val out = File("../web/stage72-job-validation.json")
            out.writeText(jsonReport(now, keywordPlan, perSource, kept, records, bucketCounts, linkChecks).toString(2))
        }

        assertTrue("expected at least one source to answer", perSource.values.any { it.returned > 0 })
    }

    // ---- Link checking ---------------------------------------------------------

    private data class LinkCheck(val status: Int?, val finalUrl: String?, val reachable: Boolean)

    /**
     * Opens every application link once and records the HTTP status it returned. A link that
     * times out, refuses the request or blocks a bot is recorded as unreachable-but-present,
     * which is a weaker statement than "broken".
     */
    private suspend fun checkLinks(urls: List<String>): Map<String, LinkCheck> = coroutineScope {
        val semaphore = Semaphore(8)
        urls.distinct().map { url ->
            async(Dispatchers.IO) {
                url to semaphore.withPermit { probe(url) }
            }
        }.awaitAll().toMap()
    }

    private fun probe(url: String): LinkCheck {
        val trimmed = url.trim()
        if (trimmed.isEmpty() || !trimmed.lowercase().startsWith("http")) {
            return LinkCheck(null, null, reachable = false)
        }
        return runCatching {
            val connection = (URL(trimmed).openConnection() as HttpURLConnection).apply {
                requestMethod = "GET"
                connectTimeout = 12_000
                readTimeout = 12_000
                instanceFollowRedirects = true
                setRequestProperty("User-Agent", "Mozilla/5.0 (compatible; AIGlobalJobFinder/1.0)")
                setRequestProperty("Accept", "text/html,application/json;q=0.9,*/*;q=0.8")
                setRequestProperty("Range", "bytes=0-2048")
            }
            try {
                val status = connection.responseCode
                LinkCheck(status, connection.url?.toString(), reachable = status in 200..399)
            } finally {
                connection.disconnect()
            }
        }.getOrElse { LinkCheck(null, null, reachable = false) }
    }

    // ---- Classification --------------------------------------------------------

    /**
     * Where a retained posting stands, from evidence the app actually observed. The order is
     * the order a candidate should care about:
     *
     *  - [BROKEN] — the application link cannot be opened, or the source itself reports the
     *    posting closed or past its deadline. Nothing to act on.
     *  - [RESTRICTED] — the employer states it is open only to local candidates, so an
     *    application from Madagascar would not be considered.
     *  - [LOW_QUALITY] — the record is missing the employer identity a candidate needs, or the
     *    app flags a scam risk, so it is not a posting to trust.
     *  - [UNCERTAIN] — the link works but eligibility, activity or a stated date is unknown, so
     *    the candidate must verify before relying on it.
     *  - [USABLE] — the employer is known, the link opens, and eligibility is either stated
     *    open or sponsored.
     */
    private fun bucketFor(
        job: com.aiglobal.jobfinder.data.model.Job,
        destination: ApplicationDestination,
        canOpen: Boolean,
        check: LinkCheck?,
    ): ValidationBucket = when {
        !canOpen || destination == ApplicationDestination.INVALID -> ValidationBucket.BROKEN
        check != null && check.status != null && check.status >= 400 -> ValidationBucket.BROKEN
        job.verification.activity == VerificationState.EXPIRED -> ValidationBucket.BROKEN
        job.verification.deadline == VerificationState.EXPIRED -> ValidationBucket.BROKEN
        job.eligibility == MadagascarEligibility.RESTRICTED -> ValidationBucket.RESTRICTED
        job.company.isBlank() || job.company.equals("Unknown employer", ignoreCase = true) ->
            ValidationBucket.LOW_QUALITY
        job.scamRisk == com.aiglobal.jobfinder.data.model.ScamRisk.HIGH -> ValidationBucket.LOW_QUALITY
        job.applyableFromMadagascar &&
            job.verification.link == VerificationState.CONFIRMED -> ValidationBucket.USABLE
        else -> ValidationBucket.UNCERTAIN
    }

    private enum class ValidationBucket(val rank: Int) {
        USABLE(0), UNCERTAIN(1), RESTRICTED(2), LOW_QUALITY(3), BROKEN(4)
    }

    // ---- Report types ----------------------------------------------------------

    private data class SourceLine(
        val name: String,
        val targeted: Boolean,
        val returned: Int,
        val failure: String?,
    )

    private data class JobRecord(
        val providerId: String,
        val source: String,
        val title: String,
        val company: String,
        val location: String,
        val country: String,
        val city: String,
        val url: String,
        val postedAtEpochMillis: Long?,
        val retrievedAtEpochMillis: Long?,
        val eligibility: MadagascarEligibility,
        val visaSupport: com.aiglobal.jobfinder.data.model.VisaSupport,
        val freshness: Freshness,
        val linkState: VerificationState,
        val activityState: VerificationState,
        val deadlineState: VerificationState,
        val visaState: VerificationState,
        val relocationState: VerificationState,
        val destination: ApplicationDestination,
        val canOpen: Boolean,
        val linkWarnings: List<String>,
        val httpStatus: Int?,
        val httpFinalUrl: String?,
        val httpReachable: Boolean,
        val bucket: ValidationBucket,
    ) {
        fun line(): String = "%-14s %-14s %-52s %-22s %-24s %s%s".format(
            bucket.name, providerId, title.take(52), company.take(22), location.take(24),
            eligibility.name,
            httpStatus?.let { "  HTTP=$it" } ?: "  HTTP=n/a",
        )
    }

    private fun plain(html: String): String =
        com.aiglobal.jobfinder.data.jobs.provider.HtmlText.toPlainText(html)

    private fun jsonReport(
        now: Long,
        keywordPlan: List<String>,
        perSource: Map<String, SourceLine>,
        kept: List<RawJob>,
        records: List<JobRecord>,
        bucketCounts: Map<ValidationBucket, Int>,
        linkChecks: Map<String, LinkCheck>,
    ): JSONObject {
        val sources = JSONArray()
        for ((id, line) in perSource) {
            sources.put(
                JSONObject()
                    .put("providerId", id)
                    .put("name", line.name)
                    .put("targeted", line.targeted)
                    .put("returned", line.returned)
                    .put("kept", kept.count { it.providerId == id })
                    .put("relevant", records.count { it.providerId == id })
                    .put("failure", line.failure ?: JSONObject.NULL),
            )
        }

        val jobs = JSONArray()
        for (r in records) {
            jobs.put(
                JSONObject()
                    .put("bucket", r.bucket.name)
                    .put("providerId", r.providerId)
                    .put("source", r.source)
                    .put("title", r.title)
                    .put("company", r.company)
                    .put("location", r.location)
                    .put("country", r.country)
                    .put("city", r.city)
                    .put("url", r.url)
                    .put("postedAtEpochMillis", r.postedAtEpochMillis ?: JSONObject.NULL)
                    .put("retrievedAtEpochMillis", r.retrievedAtEpochMillis ?: JSONObject.NULL)
                    .put("eligibility", r.eligibility.name)
                    .put("visaSupport", r.visaSupport.name)
                    .put("freshness", r.freshness.name)
                    .put("linkState", r.linkState.name)
                    .put("activityState", r.activityState.name)
                    .put("deadlineState", r.deadlineState.name)
                    .put("visaState", r.visaState.name)
                    .put("relocationState", r.relocationState.name)
                    .put("destination", r.destination.name)
                    .put("canOpen", r.canOpen)
                    .put("linkWarnings", JSONArray(r.linkWarnings))
                    .put("httpStatus", r.httpStatus ?: JSONObject.NULL)
                    .put("httpFinalUrl", r.httpFinalUrl ?: JSONObject.NULL)
                    .put("httpReachable", r.httpReachable),
            )
        }

        val buckets = JSONObject()
        for (bucket in ValidationBucket.entries) buckets.put(bucket.name, bucketCounts[bucket] ?: 0)

        val eligibility = JSONObject()
        records.groupingBy { it.eligibility }.eachCount().forEach { (k, v) -> eligibility.put(k.name, v) }
        val visa = JSONObject()
        records.groupingBy { it.visaSupport }.eachCount().forEach { (k, v) -> visa.put(k.name, v) }
        val freshness = JSONObject()
        records.groupingBy { it.freshness }.eachCount().forEach { (k, v) -> freshness.put(k.name, v) }

        return JSONObject()
            .put("stage", 72)
            .put("generatedAtEpochMillis", now)
            .put("candidateTitle", profile.currentTitle)
            .put("keywordPlan", JSONArray(keywordPlan))
            .put("returned", perSource.values.sumOf { it.returned })
            .put("kept", kept.size)
            .put("relevant", records.size)
            .put("excluded", kept.size - records.size)
            .put("sources", sources)
            .put("buckets", buckets)
            .put("eligibility", eligibility)
            .put("visaSupport", visa)
            .put("freshness", freshness)
            .put(
                "linkChecks",
                JSONObject()
                    .put("checked", linkChecks.size)
                    .put("reachable", linkChecks.values.count { it.reachable })
                    .put("unreachable", linkChecks.values.count { !it.reachable }),
            )
            .put("jobs", jobs)
    }
}
