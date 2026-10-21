package com.aiglobal.jobfinder.data.apply

import androidx.compose.runtime.Immutable
import com.aiglobal.jobfinder.data.model.Job
import java.net.URI

/**
 * Where an "Apply" tap would actually take the candidate. This is deliberately explicit:
 * the app never opens a link it has not first described, because an application button
 * that silently leaves the app for an unknown host is exactly how a candidate ends up on
 * a phishing page.
 */
enum class ApplicationDestination(val label: String) {
    /** The employer's own posting page. */
    EMPLOYER_SITE("Employer posting"),
    /** The job board or aggregator that published the listing. */
    JOB_BOARD("Job board"),
    /** A known government or official immigration portal. */
    GOVERNMENT_PORTAL("Official government source"),
    /** A valid link, but the app cannot vouch for the host. */
    UNKNOWN_HOST("Unverified host"),
    /** Not a usable web address. */
    INVALID("Invalid link"),
}

/**
 * The verdict for one posting's application link. [canOpen] is false only when opening the
 * link would be pointless or unsafe, so the UI can disable the button and explain why
 * instead of launching a browser at nothing.
 */
@Immutable
data class ApplicationLinkInspection(
    val url: String,
    val host: String?,
    val destination: ApplicationDestination,
    val canOpen: Boolean,
    val message: String,
    val warnings: List<String> = emptyList(),
)

/**
 * Inspects the URL behind an application. It answers three questions the candidate cares
 * about before leaving the app: is this a real link, whose site is it, and is there
 * anything about it worth flagging?
 */
class ApplicationUrlInspector {

    fun inspect(job: Job): ApplicationLinkInspection = inspect(job.sourceUrl, job.source)

    fun inspect(url: String, sourceName: String = ""): ApplicationLinkInspection {
        val trimmed = url.trim()
        if (trimmed.isEmpty()) {
            return ApplicationLinkInspection(
                url = "",
                host = null,
                destination = ApplicationDestination.INVALID,
                canOpen = false,
                message = "This posting did not include an application link. Search the employer's own careers page.",
            )
        }

        val uri = runCatching { URI(trimmed) }.getOrNull()
        val scheme = uri?.scheme?.lowercase()
        if (uri == null || scheme !in setOf("http", "https")) {
            return ApplicationLinkInspection(
                url = trimmed,
                host = null,
                destination = ApplicationDestination.INVALID,
                canOpen = false,
                message = "This link is not a web address the app can open safely.",
                warnings = listOf("Only http and https links are opened."),
            )
        }

        val host = uri.host?.lowercase()?.removePrefix("www.") ?: ""
        if (host.isBlank() || !host.contains('.')) {
            return ApplicationLinkInspection(
                url = trimmed,
                host = host.ifBlank { null },
                destination = ApplicationDestination.INVALID,
                canOpen = false,
                message = "This link has no usable web address.",
            )
        }

        val warnings = mutableListOf<String>()
        if (scheme == "http") {
            warnings += "This page is not served over a secure connection (http)."
        }
        if (host.endsWith(".tk") || host.endsWith(".ml") || host.endsWith(".ga") || host.endsWith(".cf") || host.endsWith(".gq")) {
            warnings += "Free-domain hosts are commonly used by recruitment scams. Verify the employer independently."
        }
        if (host.startsWith("xn--")) {
            warnings += "This address uses non-Latin characters that can imitate a real company's site."
        }
        val isIpAddress = host.matches(Regex("\\d{1,3}(\\.\\d{1,3}){3}"))
        if (isIpAddress) {
            warnings += "The link points at a raw IP address rather than a company domain."
        }
        if (trimmed.length > 500) {
            warnings += "This is an unusually long link, which can hide a redirect."
        }

        val destination = classify(host, sourceName)
        val message = when (destination) {
            ApplicationDestination.GOVERNMENT_PORTAL ->
                "This link goes to $host, an official source. Applications here follow the government's own process."
            ApplicationDestination.EMPLOYER_SITE ->
                "This link goes to $host. Read the posting there and apply through the employer's own form."
            ApplicationDestination.JOB_BOARD ->
                "This link goes to $host, where the listing is published. Confirm the employer's details before applying."
            ApplicationDestination.UNKNOWN_HOST ->
                "This link goes to $host. The app cannot verify this host, so treat it with care."
            ApplicationDestination.INVALID ->
                "This link is not usable."
        }
        return ApplicationLinkInspection(
            url = trimmed,
            host = host,
            destination = destination,
            canOpen = true,
            message = message,
            warnings = warnings,
        )
    }

    private fun classify(host: String, sourceName: String): ApplicationDestination {
        if (GOVERNMENT_SUFFIXES.any { host == it || host.endsWith(".$it") }) {
            return ApplicationDestination.GOVERNMENT_PORTAL
        }
        if (GOVERNMENT_HOSTS.any { host == it || host.endsWith(".$it") }) {
            return ApplicationDestination.GOVERNMENT_PORTAL
        }
        if (JOB_BOARD_HOSTS.any { host == it || host.endsWith(".$it") }) {
            return ApplicationDestination.JOB_BOARD
        }
        // An applicant-tracking host serves the employer's own posting, so the link is the
        // employer's own application page — not an unverifiable host. Most real vacancies are
        // reached through these (Workday, Greenhouse, Lever, Ashby, SmartRecruiters), so
        // calling them unverified would mislabel the majority of genuine employer links.
        if (EMPLOYER_HOST_SUFFIXES.any { host == it || host.endsWith(".$it") }) {
            return ApplicationDestination.EMPLOYER_SITE
        }
        // A posting whose host matches the source name is the board that published it.
        val sourceSlug = sourceName.lowercase().replace(Regex("[^a-z0-9]"), "")
        if (sourceSlug.length >= 4 && host.replace(Regex("[^a-z0-9]"), "").contains(sourceSlug)) {
            return ApplicationDestination.JOB_BOARD
        }
        return ApplicationDestination.UNKNOWN_HOST
    }

    private companion object {
        val GOVERNMENT_SUFFIXES = listOf("gov", "gouv.fr", "gov.uk", "gov.au", "govt.nz", "gc.ca", "europa.eu", "gov.ae", "gov.qa")
        val GOVERNMENT_HOSTS = listOf(
            "jobbank.gc.ca", "canada.ca", "make-it-in-germany.com", "eures.europa.eu",
            "gov.uk", "immi.homeaffairs.gov.au", "immigration.govt.nz", "ind.nl",
            "mohre.gov.ae", "mol.gov.qa", "un.org", "ilo.org",
        )
        val JOB_BOARD_HOSTS = listOf(
            "adzuna.com", "jooble.org", "himalayas.app", "remotive.com", "jobicy.com",
            "arbeitnow.com", "linkedin.com", "indeed.com", "bayt.com", "naukrigulf.com",
        )

        /**
         * Applicant-tracking systems the app's own sources link to. A posting on one of these
         * is the employer's own application page: the employer runs their careers site there,
         * so the candidate applies through the employer rather than to a third party. Matched
         * as a suffix so a Workday tenant host (`sulzer.wd502.myworkdayjobs.com`) is covered.
         */
        val EMPLOYER_HOST_SUFFIXES = listOf(
            "myworkdayjobs.com", "myworkdaysite.com", "workday.com",
            "greenhouse.io", "lever.co", "ashbyhq.com", "smartrecruiters.com",
        )
    }
}
