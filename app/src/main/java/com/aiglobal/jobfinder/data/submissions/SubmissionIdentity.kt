package com.aiglobal.jobfinder.data.submissions

import androidx.compose.runtime.Immutable
import com.aiglobal.jobfinder.data.model.Job

/**
 * What the registry knows about a posting's identity, in order of reliability.
 *
 * The problem this solves: the same opening reaches the app from several boards (Adzuna,
 * Jooble, EURES, an employer ATS), each with its own id and URL, while two genuinely
 * different roles can look almost identical ("Maintenance Technician" vs "Maintenance
 * Technician II"). A single key cannot tell those apart, so identity is layered:
 *
 * 1. [providerRecordId] — the source record id (`providerId:externalId`), stable for a
 *    given board. Reliable, but only comparable within the same board.
 * 2. [canonicalUrl] — the posting URL stripped of scheme, `www.`, tracking parameters and
 *    the fragment. Reliable, and comparable across sources only when they share the URL.
 * 3. [fingerprint] — employer + title + country, normalised. The fallback when neither id
 *    nor URL matches, and the only signal that can link, say, an Adzuna listing to the
 *    employer's own page.
 *
 * The first two are used "when reliable"; the fingerprint is the safety net. Anything the
 * three cannot separate with confidence is reported as ambiguous and sent for the user to
 * verify, never silently merged and never silently allowed.
 *
 * Canonical signals establish *difference* as well as sameness: two postings that share a
 * board but carry different record ids, or sit on the same host but at different URLs, are
 * provably different postings even when their titles read almost the same. Without that,
 * a fuzzy title match ("Maintenance Technician" vs "Maintenance Planner", or a test's
 * "Role 1" vs "Role 2") would be parked for the user when it is really just a different
 * job. Ambiguity is reserved for the case where nothing canonical distinguishes the two.
 */
@Immutable
data class SubmissionIdentity(
    val providerRecordId: String,
    val canonicalUrl: String,
    val company: String,
    val title: String,
    val country: String,
) {
    /** Employer + title + country, normalised. The provider-agnostic fallback identity. */
    val fingerprint: String get() = "$company|$title|$country"

    /**
     * The board a record came from (`providerId` in `providerId:externalId`), or blank when
     * the id carries no provider prefix. Only comparable between two ids that both have one.
     */
    val providerKey: String
        get() = if (providerRecordId.contains(':')) providerRecordId.substringBefore(':') else ""

    /** The per-board record id (`externalId`), or the whole id when there is no prefix. */
    val externalId: String
        get() = if (providerRecordId.contains(':')) providerRecordId.substringAfter(':') else providerRecordId

    /** The host of the canonical URL, e.g. `adzuna.ca`; blank when there is no URL. */
    val urlHost: String get() = canonicalUrl.substringBefore('/')

    /** How [other] relates to this identity. */
    fun relationTo(other: SubmissionIdentity): IdentityRelation = when {
        matchesExactly(other) -> IdentityRelation.SAME
        matchesProvablyDifferent(other) -> IdentityRelation.DISTINCT
        matchesAmbiguously(other) -> IdentityRelation.AMBIGUOUS
        else -> IdentityRelation.DISTINCT
    }

    /**
     * True when the canonical signals prove the two are *different* postings: the same board
     * with different record ids, or the same host with different URLs. Evaluated only after
     * [matchesExactly] has ruled out a shared id, URL or fingerprint.
     */
    fun matchesProvablyDifferent(other: SubmissionIdentity): Boolean {
        val sameBoard = providerKey.isNotBlank() && providerKey == other.providerKey
        val differentRecords = externalId != other.externalId
        if (sameBoard && differentRecords) return true

        val sameHost = urlHost.isNotBlank() && urlHost == other.urlHost
        if (sameHost && canonicalUrl != other.canonicalUrl) return true
        return false
    }

    /**
     * True when the two identities are the same posting beyond reasonable doubt: a shared
     * source record id, a shared canonical URL, or an identical normalised fingerprint.
     */
    fun matchesExactly(other: SubmissionIdentity): Boolean {
        val sameProviderRecord = providerRecordId.isNotBlank() && providerRecordId == other.providerRecordId
        val sameUrl = canonicalUrl.isNotBlank() && canonicalUrl == other.canonicalUrl
        return sameProviderRecord || sameUrl || fingerprint == other.fingerprint
    }

    /**
     * True when the two identities are close but not identical: the same country and both
     * the employer and the title are very similar, yet not the same posting. This is the
     * "could be a re-post, could be a different role" case — the app refuses to guess.
     */
    fun matchesAmbiguously(other: SubmissionIdentity): Boolean {
        if (country.isBlank() || country != other.country) return false
        if (fingerprint == other.fingerprint) return false
        val companySim = TextSimilarity.similarity(company, other.company)
        val titleSim = TextSimilarity.similarity(title, other.title)
        return companySim >= COMPANY_SIMILAR && titleSim >= TITLE_SIMILAR
    }

    companion object {
        /** Employer names at or above this similarity are treated as the same employer. */
        const val COMPANY_SIMILAR = 0.90

        /** Titles at or above this similarity are treated as the same role. */
        const val TITLE_SIMILAR = 0.85

        fun of(job: Job): SubmissionIdentity = SubmissionIdentity(
            providerRecordId = job.id.trim(),
            canonicalUrl = CanonicalUrl.of(job.sourceUrl),
            company = SubmissionKey.normalise(job.company.ifBlank { job.title }),
            title = SubmissionKey.normalise(job.title),
            country = SubmissionKey.normalise(job.country),
        )
    }
}

/** How one posting's identity relates to another's. */
enum class IdentityRelation {
    /** Beyond reasonable doubt the same posting. */
    SAME,

    /** Close enough that merging or allowing could be wrong — the user must decide. */
    AMBIGUOUS,

    /** Confidently a different posting. */
    DISTINCT,
}

/**
 * Canonicalises a posting URL so the same page reached with tracking noise collapses to one
 * value, while genuinely different pages stay different.
 *
 * Scheme, a leading `www.`, the fragment, a trailing slash and known tracking parameters are
 * removed; the remaining query parameters are sorted so order does not matter. Nothing else
 * is guessed: two different hosts never canonicalise to the same value, so an Adzuna URL and
 * an employer URL stay distinct and are reconciled by the fingerprint instead.
 */
object CanonicalUrl {

    private val TRACKING_PARAMS = setOf(
        "utm_source", "utm_medium", "utm_campaign", "utm_term", "utm_content", "utm_id",
        "gclid", "fbclid", "msclkid", "mc_cid", "mc_eid", "ref", "referrer", "source",
        "gh_src", "lever-source", "jobboard", "job_id", "trackingid",
    )

    fun of(rawUrl: String): String {
        val trimmed = rawUrl.trim()
        if (trimmed.isEmpty()) return ""
        var rest = trimmed
        val schemeIdx = rest.indexOf("://")
        if (schemeIdx >= 0) rest = rest.substring(schemeIdx + 3)
        rest = rest.substringBefore('#')

        val path = rest.substringBefore('?')
        val query = rest.substringAfter('?', "")

        val host = path.substringBefore('/').lowercase().removePrefix("www.")
        var pathPart = path.substringAfter('/', "")
        if (pathPart.endsWith("/")) pathPart = pathPart.dropLast(1)

        val keptParams = query.split('&')
            .filter { it.isNotBlank() }
            .map { it.substringBefore('=') to it.substringAfter('=', "") }
            .filter { (name, _) -> name.lowercase() !in TRACKING_PARAMS }
            .sortedBy { it.first }
            .joinToString("&") { (name, value) -> if (value.isEmpty()) name else "$name=$value" }

        val base = if (pathPart.isEmpty()) host else "$host/$pathPart"
        return if (keptParams.isEmpty()) base else "$base?$keptParams"
    }
}

/**
 * Small, dependency-free string similarity for identity checks. Two measures are combined:
 * Jaro-Winkler (good on short strings with small edits, e.g. "Ltd." suffixes) and a token-set
 * ratio (good when word order changes, e.g. "Industrial Northline" vs "Northline Industrial").
 * Taking the stronger of the two keeps both cases working without over-matching.
 */
object TextSimilarity {

    fun similarity(a: String, b: String): Double {
        if (a.isEmpty() && b.isEmpty()) return 1.0
        if (a.isEmpty() || b.isEmpty()) return 0.0
        return maxOf(jaroWinkler(a, b), tokenSetRatio(a, b))
    }

    fun jaroWinkler(a: String, b: String): Double {
        val jaro = jaro(a, b)
        if (jaro < 0.7) return jaro
        var prefix = 0
        val maxPrefix = minOf(4, minOf(a.length, b.length))
        while (prefix < maxPrefix && a[prefix] == b[prefix]) prefix++
        return jaro + prefix * 0.1 * (1 - jaro)
    }

    fun jaro(a: String, b: String): Double {
        if (a == b) return 1.0
        val la = a.length
        val lb = b.length
        if (la == 0 || lb == 0) return 0.0

        val window = (maxOf(la, lb) / 2 - 1).coerceAtLeast(0)
        val aMatch = BooleanArray(la)
        val bMatch = BooleanArray(lb)
        var matches = 0

        for (i in 0 until la) {
            val start = maxOf(0, i - window)
            val end = minOf(i + window + 1, lb)
            for (j in start until end) {
                if (bMatch[j] || a[i] != b[j]) continue
                aMatch[i] = true
                bMatch[j] = true
                matches++
                break
            }
        }
        if (matches == 0) return 0.0

        var transpositions = 0.0
        var k = 0
        for (i in 0 until la) {
            if (!aMatch[i]) continue
            while (!bMatch[k]) k++
            if (a[i] != b[k]) transpositions += 0.5
            k++
        }

        val m = matches.toDouble()
        return (m / la + m / lb + (m - transpositions) / m) / 3.0
    }

    /** Dice coefficient over the word sets, so word order does not matter. */
    fun tokenSetRatio(a: String, b: String): Double {
        val ta = a.split(' ').filter { it.isNotBlank() }.toSet()
        val tb = b.split(' ').filter { it.isNotBlank() }.toSet()
        if (ta.isEmpty() || tb.isEmpty()) return 0.0
        val shared = ta.count { it in tb }.toDouble()
        return 2 * shared / (ta.size + tb.size)
    }
}
