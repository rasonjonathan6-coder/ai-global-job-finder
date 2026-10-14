package com.aiglobal.jobfinder.data.scam

import com.aiglobal.jobfinder.data.model.Job
import com.aiglobal.jobfinder.data.model.MadagascarEligibility
import com.aiglobal.jobfinder.data.model.ScamReport
import com.aiglobal.jobfinder.data.model.ScamRisk
import com.aiglobal.jobfinder.data.model.ScamSignal
import com.aiglobal.jobfinder.data.model.ScamSignalTone
import com.aiglobal.jobfinder.data.model.ScamVerdict
import com.aiglobal.jobfinder.data.model.VisaSupport
import java.net.URI

/**
 * Scans a posting for the patterns that separate a real international job from a scam.
 *
 * The detector only ever reports what it can quote. Each danger signal carries the exact
 * phrase from the posting that triggered it, so the user can read the original and judge
 * for themselves. It advises; it never blocks an application, because a false positive
 * that hides a real job is as harmful as a missed scam.
 */
class ScamDetector(
    private val clock: () -> Long = System::currentTimeMillis,
) {

    fun scan(job: Job): ScamReport {
        val haystack = buildString {
            append(job.title).append('\n')
            append(job.company).append('\n')
            append(job.summary).append('\n')
            append(job.responsibilities.joinToString("\n")).append('\n')
            append(job.benefits.joinToString("\n")).append('\n')
            append(job.aiInsight)
        }
        val lower = haystack.lowercase()
        val signals = mutableListOf<ScamSignal>()

        // ---- Money flowing the wrong way ------------------------------------
        MONEY_PATTERNS.forEach { pattern ->
            pattern.find(lower)?.let { match ->
                signals += ScamSignal(
                    label = pattern.label,
                    detail = pattern.detail,
                    evidence = match.value.trim(),
                    tone = ScamSignalTone.DANGER,
                )
            }
        }

        // ---- Unrealistic pay -------------------------------------------------
        val salaryPerMonth = job.salaryMin.takeIf { it > 0 }?.let { min ->
            when (job.salaryPeriod) {
                com.aiglobal.jobfinder.data.model.SalaryPeriod.YEAR -> min / 12
                com.aiglobal.jobfinder.data.model.SalaryPeriod.MONTH -> min
                com.aiglobal.jobfinder.data.model.SalaryPeriod.DAY -> min * 21
                com.aiglobal.jobfinder.data.model.SalaryPeriod.HOUR -> min * 160
            }
        }
        val currencyUpper = job.currencyCode.uppercase()
        val highValueCurrencies = setOf("USD", "EUR", "GBP", "CAD", "AUD", "CHF")
        if (salaryPerMonth != null && currencyUpper in highValueCurrencies && salaryPerMonth > UNREALISTIC_MONTHLY) {
            signals += ScamSignal(
                label = "Unrealistic salary",
                detail = "The advertised pay is far above the normal range for this kind of role, which is a common lure.",
                evidence = "${job.currencyCode} ${job.salaryMin} per ${job.salaryPeriod.name.lowercase()}",
                tone = ScamSignalTone.DANGER,
            )
        }

        // ---- Communication channel ------------------------------------------
        MESSAGING_PATTERNS.forEach { pattern ->
            pattern.find(lower)?.let { match ->
                signals += ScamSignal(
                    label = pattern.label,
                    detail = pattern.detail,
                    evidence = match.value.trim(),
                    tone = ScamSignalTone.CAUTION,
                )
            }
        }

        // ---- Structural checks ----------------------------------------------
        if (job.company.isBlank() || job.company.equals("Unknown employer", ignoreCase = true)) {
            signals += ScamSignal(
                label = "No named employer",
                detail = "The posting does not name a real company, so there is nothing to verify.",
                evidence = "",
                tone = ScamSignalTone.DANGER,
            )
        }
        if (job.sourceUrl.isBlank() || !job.sourceUrl.startsWith("http")) {
            signals += ScamSignal(
                label = "No original posting link",
                detail = "There is no link back to the employer's own advert, so you cannot confirm it exists.",
                evidence = "",
                tone = ScamSignalTone.CAUTION,
            )
        } else if (!isReputableHost(job.sourceUrl)) {
            signals += ScamSignal(
                label = "Unfamiliar application site",
                detail = "The link does not point to a well-known job board or the employer's own domain.",
                evidence = hostOf(job.sourceUrl),
                tone = ScamSignalTone.CAUTION,
            )
        }
        if (job.title.isBlank() || job.title.equals("Untitled role", ignoreCase = true)) {
            signals += ScamSignal(
                label = "Vague job title",
                detail = "The title is too generic to tell what the work actually is.",
                evidence = "",
                tone = ScamSignalTone.CAUTION,
            )
        }
        if (job.summary.isBlank() && job.responsibilities.isEmpty()) {
            signals += ScamSignal(
                label = "No job description",
                detail = "A real posting describes the day-to-day work. This one says almost nothing.",
                evidence = "",
                tone = ScamSignalTone.CAUTION,
            )
        }

        // ---- Reassurances ----------------------------------------------------
        if (job.visaSupport == VisaSupport.FULL_SPONSORSHIP || job.visaSupport == VisaSupport.RELOCATION_PACKAGE) {
            signals += ScamSignal(
                label = "States sponsorship in writing",
                detail = "The employer put visa or relocation support in the advert itself, which is a good sign.",
                evidence = "",
                tone = ScamSignalTone.REASSURANCE,
            )
        }
        if (job.source.isNotBlank() && job.sourceUrl.startsWith("http")) {
            signals += ScamSignal(
                label = "Published on a job board",
                detail = "${job.source} published this posting with a link back to the original advert.",
                evidence = "",
                tone = ScamSignalTone.REASSURANCE,
            )
        }
        if (job.eligibility == MadagascarEligibility.RESTRICTED) {
            signals += ScamSignal(
                label = "Employer states local-only",
                detail = "The posting is explicit about who it is open to, which real employers usually are.",
                evidence = "",
                tone = ScamSignalTone.REASSURANCE,
            )
        }

        val dangers = signals.count { it.tone == ScamSignalTone.DANGER }
        val cautions = signals.count { it.tone == ScamSignalTone.CAUTION }
        val verdict = when {
            dangers > 0 -> ScamVerdict.HIGH_RISK
            cautions >= 2 -> ScamVerdict.NEEDS_CAUTION
            cautions == 1 -> ScamVerdict.NEEDS_CAUTION
            else -> ScamVerdict.LIKELY_SAFE
        }
        val risk = when (verdict) {
            ScamVerdict.HIGH_RISK -> ScamRisk.HIGH
            ScamVerdict.NEEDS_CAUTION -> ScamRisk.MEDIUM
            ScamVerdict.LIKELY_SAFE -> ScamRisk.LOW
        }

        return ScamReport(
            jobId = job.id,
            jobTitle = job.title,
            company = job.company,
            risk = risk,
            verdict = verdict,
            signals = signals,
            checkedAtEpochMillis = clock(),
        )
    }

    private fun hostOf(url: String): String = runCatching { URI(url).host ?: "" }.getOrDefault("")

    private fun isReputableHost(url: String): Boolean {
        val host = hostOf(url).lowercase()
        if (host.isBlank()) return false
        return REPUTABLE_HOSTS.any { host == it || host.endsWith(".$it") }
    }

    /** A named pattern with the exact wording that makes it dangerous. */
    private data class Pattern(
        val label: String,
        val detail: String,
        val regex: Regex,
    ) {
        fun find(text: String) = regex.find(text)
    }

    private companion object {
        /** Anything that asks the applicant to send money is a scam, with no exceptions. */
        val MONEY_PATTERNS = listOf(
            Pattern(
                label = "Asks you to pay a fee",
                detail = "A legitimate employer never charges you to apply, to be placed, or to be trained.",
                regex = Regex("(registration|processing|application|placement|agency|training|admin(istration)?)\\s+fee"),
            ),
            Pattern(
                label = "Asks for a deposit or payment",
                detail = "No real job requires you to deposit money or pay for your own paperwork up front.",
                regex = Regex("(pay|payment|deposit|transfer)\\s+(a\\s+)?(small\\s+)?(deposit|amount|fee|money|sum)"),
            ),
            Pattern(
                label = "Mentions Western Union or money transfer",
                detail = "Payment by wire or money transfer to an employer is a hallmark of advance-fee fraud.",
                regex = Regex("(western union|moneygram|money\\s*gram|bitcoin|usdt|crypto\\s*wallet)"),
            ),
            Pattern(
                label = "Charges for the visa",
                detail = "You should never pay a third party for a work visa; only official government fees apply, paid directly.",
                regex = Regex("(visa|work permit|permit)\\s+(fee|processing|payment)"),
            ),
        )

        /** Channels that real recruiters do not use for first contact. */
        val MESSAGING_PATTERNS = listOf(
            Pattern(
                label = "Contact only by chat app",
                detail = "Interviews arranged only on WhatsApp or Telegram, with no company channel, are a common scam pattern.",
                regex = Regex("(whatsapp|telegram|signal)\\s+(only|us|number|contact)"),
            ),
            Pattern(
                label = "Personal email address",
                detail = "A real employer writes from a company domain, not a free personal inbox.",
                regex = Regex("(gmail|yahoo|hotmail|outlook|mail)\\.com"),
            ),
        )

        val REPUTABLE_HOSTS = listOf(
            "arbeitnow.com", "remotive.com", "jobicy.com", "linkedin.com", "indeed.com",
            "eures.europa.eu", "europa.eu", "un.org", "reliefweb.int", "jobbank.gc.ca",
            "make-it-in-germany.com", "gulftalent.com", "bayt.com", "glassdoor.com",
        )

        /** Monthly pay above this in a strong currency is treated as a lure, not an offer. */
        const val UNREALISTIC_MONTHLY = 60_000
    }
}
