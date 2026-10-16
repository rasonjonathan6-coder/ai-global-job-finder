package com.aiglobal.jobfinder.data.cv

import com.aiglobal.jobfinder.data.ai.NvidiaAiClient
import com.aiglobal.jobfinder.data.model.CandidateProfile
import com.aiglobal.jobfinder.data.model.CertificationEntry
import com.aiglobal.jobfinder.data.model.CvAiStatus
import com.aiglobal.jobfinder.data.model.EducationEntry
import com.aiglobal.jobfinder.data.model.LanguageLevel
import com.aiglobal.jobfinder.data.model.LanguageSkill
import com.aiglobal.jobfinder.data.model.NvidiaConfig
import com.aiglobal.jobfinder.data.model.NvidiaResult
import com.aiglobal.jobfinder.data.model.NvidiaStatus
import com.aiglobal.jobfinder.data.model.RelocationPreference
import org.json.JSONArray
import org.json.JSONObject

/**
 * Reads a CV with the configured NVIDIA model instead of the on-device heuristics.
 *
 * This is the analyser the user gets when they have saved a key *and* granted consent: the CV
 * text is sent to NVIDIA and the model returns a structured profile. Two rules make the result
 * safe to trust:
 *
 * 1. **The model is not allowed to invent.** The prompt demands a fixed JSON schema and a
 *    `null` for any field it cannot find, and the parser drops (never guesses) anything that
 *    does not fit. A blank field stays blank.
 * 2. **There is always a coherent answer.** The deterministic [HeuristicCvAnalyzer] runs first
 *    and its profile is used as the fallback. When the model is off, unconfigured, rate-limited,
 *    unparseable or returns invalid JSON, the user still gets a real analysis — clearly labelled
 *    as on-device — plus one short line saying why the AI was not used.
 *
 * The CV never leaves the device unless [NvidiaConfig.isReady] is true, which is the same
 * consent gate the drafting assistant uses.
 */
class CvAiAnalyzer internal constructor(
    /** Read at analysis time so a key added in Profile takes effect without a restart. */
    private val configProvider: () -> NvidiaConfig,
    /** The one seam the tests replace: given the config and a prompt, return the model's text. */
    private val complete: suspend (NvidiaConfig, String, String, Int) -> NvidiaResult,
    private val maxChunkChars: Int,
) : CvAnalyzer {

    constructor(
        configProvider: () -> NvidiaConfig,
        client: NvidiaAiClient = NvidiaAiClient(),
        maxChunkChars: Int = MAX_CHUNK_CHARS,
    ) : this(
        configProvider = configProvider,
        complete = { config, system, user, max ->
            client.complete(config.apiKey, config.modelId, system, user, max)
        },
        maxChunkChars = maxChunkChars,
    )

    override val id: String = "nvidia-ai"
    override val label: String = "AI analysis (NVIDIA)"
    override val sendsDataOffDevice: Boolean = true

    override suspend fun analyze(
        text: String,
        existing: CandidateProfile,
        protectedFields: Set<com.aiglobal.jobfinder.data.model.CandidateField>,
    ): CvAnalysisResult {
        // The offline pass is both the fallback and a safety net: its reliable contact regexes
        // fill anything the model left out.
        val heuristic: CvAnalysisResult = HeuristicCvAnalyzer().analyze(text, existing, protectedFields)

        if (text.count { !it.isWhitespace() } < CvParseResult.MIN_USABLE_CHARACTERS) {
            return heuristic.copy(aiStatus = CvAiStatus.NONE)
        }

        val config = configProvider()
        val refused = when {
            !config.consentGranted -> CvAiStatus.CONSENT_REQUIRED
            !config.isConfigured -> CvAiStatus.NOT_CONFIGURED
            else -> null
        }
        if (refused != null) {
            return heuristic.copy(
                aiStatus = refused,
                warnings = heuristic.warnings + listOfNotNull(refused.explanation()),
            )
        }

        val chunks = chunk(text)
        val extractions = ArrayList<CandidateProfile>(chunks.size)
        var failure: NvidiaResult? = null
        for ((index, chunk) in chunks.withIndex()) {
            val result = complete(config, SYSTEM_PROMPT, promptFor(chunk, index, chunks.size), MAX_OUTPUT_TOKENS)
            if (!result.isSuccess) {
                failure = result
                break
            }
            val parsed = parseProfileJson(result.text)
            if (parsed == null) {
                failure = NvidiaResult(
                    status = NvidiaStatus.BAD_RESPONSE,
                    message = "The model's answer was not a readable profile. The on-device analysis was used instead.",
                )
                break
            }
            extractions += parsed
        }

        if (failure != null || extractions.isEmpty()) {
            return heuristic.copy(
                aiStatus = CvAiStatus.FAILED,
                warnings = heuristic.warnings + failureWarning(failure, config),
            )
        }

        // One call per chunk; union them, then let the AI result win over the heuristic where
        // it found a value and the heuristic fill the rest.
        val aiProfile = extractions.reduce { a, b -> union(a, b) }
        val profile = CvProfileMerger.merge(
            extracted = aiProfile,
            existing = heuristic.profile,
            protectedFields = protectedFields,
        )

        return CvAnalysisResult(
            profile = profile,
            warnings = heuristic.warnings + extraWarnings(profile),
            confidence = confidenceOf(profile),
            evidence = evidenceOf(profile),
            aiStatus = CvAiStatus.SUCCESS,
        )
    }

    // ------------------------------------------------------------------ chunking

    /** Splits long text on blank lines so each request stays a sensible size. */
    internal fun chunk(text: String): List<String> {
        if (text.length <= maxChunkChars) return listOf(text)
        val out = ArrayList<String>()
        val builder = StringBuilder()
        for (paragraph in text.split("\n\n")) {
            if (builder.length + paragraph.length + 2 > maxChunkChars && builder.isNotEmpty()) {
                out += builder.toString()
                builder.clear()
            }
            if (builder.isNotEmpty()) builder.append("\n\n")
            builder.append(paragraph)
        }
        if (builder.isNotEmpty()) out += builder.toString()
        return out.ifEmpty { listOf(text) }
    }

    // ------------------------------------------------------------------ parsing

    /**
     * Parses the model's JSON into a [CandidateProfile]. Tolerant of code fences and of any
     * surrounding prose, but strict about types: an absent or ill-typed value is left empty
     * rather than coerced into a wrong one. Returns null when there is no JSON object at all.
     */
    internal fun parseProfileJson(raw: String): CandidateProfile? {
        val start = raw.indexOf('{')
        val end = raw.lastIndexOf('}')
        if (start < 0 || end <= start) return null
        val json = runCatching { JSONObject(raw.substring(start, end + 1)) }.getOrNull() ?: return null

        return CandidateProfile(
            fullName = json.text("full_name"),
            email = json.text("email").takeIf { it.contains('@') }.orEmpty(),
            phone = json.text("phone"),
            location = json.text("location"),
            currentTitle = json.text("current_title"),
            previousTitles = json.stringList("previous_titles"),
            yearsOfExperience = json.intOrNull("years_of_experience"),
            skills = json.stringList("skills"),
            industries = json.stringList("industries"),
            education = json.objList("education") { obj ->
                EducationEntry(
                    degree = obj.text("degree"),
                    institution = obj.text("institution"),
                    country = obj.text("country"),
                    year = obj.intOrNull("year") ?: 0,
                )
            }.filter { it.degree.isNotBlank() || it.institution.isNotBlank() },
            certifications = json.objList("certifications") { obj ->
                CertificationEntry(
                    name = obj.text("name"),
                    issuer = obj.text("issuer"),
                    year = obj.intOrNull("year"),
                )
            }.filter { it.name.isNotBlank() },
            languages = json.objList("languages") { obj ->
                val name = obj.text("name")
                LanguageSkill(
                    name = name,
                    level = LanguageLevel.fromToken(obj.text("level").ifBlank { null }),
                )
            }.filter { it.name.isNotBlank() },
            preferredCountries = json.stringList("preferred_countries"),
            preferredRoles = json.stringList("preferred_roles"),
            salaryExpectationMin = json.optJSONObject("salary_expectation")?.intOrNull("min"),
            salaryExpectationMax = json.optJSONObject("salary_expectation")?.intOrNull("max"),
            salaryCurrency = json.optJSONObject("salary_expectation")?.text("currency")?.takeIf { it.isNotBlank() },
            relocationPreference = relocationFrom(json.text("relocation_preference")),
            needsVisaSponsorship = json.optBooleanOrNull("needs_visa_sponsorship"),
            workPermitNotes = json.text("work_permit_notes"),
            summary = json.text("summary"),
        )
    }

    private fun relocationFrom(token: String): RelocationPreference {
        val t = token.lowercase()
        return when {
            t.contains("actively") || t.contains("prefers") -> RelocationPreference.PREFERS_RELOCATION
            t.contains("open") -> RelocationPreference.OPEN_TO_RELOCATION
            t.contains("remote") -> RelocationPreference.REMOTE_ONLY
            t.contains("not") || t.contains("no reloc") || t.contains("unwilling") -> RelocationPreference.NO_RELOCATION
            else -> RelocationPreference.UNKNOWN
        }
    }

    // ------------------------------------------------------------------ merging

    /** Combines two partial extractions, keeping every present value from either. */
    private fun union(a: CandidateProfile, b: CandidateProfile): CandidateProfile = CandidateProfile(
        fullName = a.fullName.orElse(b.fullName),
        email = a.email.orElse(b.email),
        phone = a.phone.orElse(b.phone),
        location = a.location.orElse(b.location),
        currentTitle = a.currentTitle.orElse(b.currentTitle),
        previousTitles = (a.previousTitles + b.previousTitles).distinct(),
        yearsOfExperience = a.yearsOfExperience ?: b.yearsOfExperience,
        skills = (a.skills + b.skills).distinct(),
        industries = (a.industries + b.industries).distinct(),
        education = (a.education + b.education).distinctBy { it.degree.lowercase() + it.institution.lowercase() },
        certifications = (a.certifications + b.certifications).distinctBy { it.name.lowercase() },
        languages = (a.languages + b.languages).distinctBy { it.name.lowercase() },
        preferredCountries = (a.preferredCountries + b.preferredCountries).distinct(),
        preferredRoles = (a.preferredRoles + b.preferredRoles).distinct(),
        salaryExpectationMin = a.salaryExpectationMin ?: b.salaryExpectationMin,
        salaryExpectationMax = a.salaryExpectationMax ?: b.salaryExpectationMax,
        salaryCurrency = a.salaryCurrency?.takeIf { it.isNotBlank() } ?: b.salaryCurrency,
        relocationPreference = if (a.relocationPreference != RelocationPreference.UNKNOWN) {
            a.relocationPreference
        } else {
            b.relocationPreference
        },
        needsVisaSponsorship = a.needsVisaSponsorship ?: b.needsVisaSponsorship,
        workPermitNotes = a.workPermitNotes.orElse(b.workPermitNotes),
        summary = a.summary.orElse(b.summary),
    )

    private fun String.orElse(other: String): String = if (isNotBlank()) this else other

    // ------------------------------------------------------------------ reporting

    private fun confidenceOf(profile: CandidateProfile): Int {
        val checks = listOf(
            profile.fullName.isNotBlank(),
            profile.email.isNotBlank(),
            profile.phone.isNotBlank(),
            profile.location.isNotBlank(),
            profile.currentTitle.isNotBlank(),
            profile.yearsOfExperience != null,
            profile.skills.size >= 3,
            profile.education.isNotEmpty(),
            profile.languages.isNotEmpty(),
        )
        return checks.count { it } * 100 / checks.size
    }

    private fun evidenceOf(profile: CandidateProfile): Map<com.aiglobal.jobfinder.data.model.CandidateField, String> =
        buildMap {
            if (profile.fullName.isNotBlank()) put(com.aiglobal.jobfinder.data.model.CandidateField.FULL_NAME, profile.fullName)
            if (profile.email.isNotBlank()) put(com.aiglobal.jobfinder.data.model.CandidateField.EMAIL, profile.email)
            if (profile.currentTitle.isNotBlank()) put(com.aiglobal.jobfinder.data.model.CandidateField.CURRENT_TITLE, profile.currentTitle)
            if (profile.education.isNotEmpty()) put(com.aiglobal.jobfinder.data.model.CandidateField.EDUCATION, profile.education.first().degree)
        }

    private fun extraWarnings(profile: CandidateProfile): List<String> = buildList {
        if (profile.fullName.isBlank()) add("The AI could not find a name. Add yours by hand.")
        if (profile.education.isEmpty()) add("The AI could not find an education or training entry.")
    }

    private fun failureWarning(failure: NvidiaResult?, config: NvidiaConfig): String {
        val detail = failure?.message?.takeIf { it.isNotBlank() }
            ?: "The AI service did not answer."
        return "$detail The on-device analysis was used instead."
    }

    // ------------------------------------------------------------------ prompt

    internal fun promptFor(chunk: String, index: Int, total: Int): String = buildString {
        if (total > 1) appendLine("This is part ${index + 1} of $total of one CV. Extract only what this part contains.")
        appendLine("Return one JSON object describing this CV. Use this exact schema:")
        appendLine(SCHEMA)
        appendLine()
        appendLine("CV:")
        appendLine("\"\"\"")
        appendLine(chunk)
        appendLine("\"\"\"")
    }

    private companion object {
        const val MAX_CHUNK_CHARS = 12_000
        const val MAX_OUTPUT_TOKENS = 1_200

        val SYSTEM_PROMPT =
            "You extract structured data from a CV. Reply with a single JSON object and nothing " +
                "else. Copy values faithfully from the CV. If a field is not present, use null or " +
                "an empty array — never guess, never invent an employer, date, degree or skill."

        val SCHEMA = """
            {
              "full_name": string|null,
              "email": string|null,
              "phone": string|null,
              "location": string|null,
              "current_title": string|null,
              "previous_titles": [string],
              "years_of_experience": integer|null,
              "skills": [string],
              "industries": [string],
              "education": [{"degree": string, "institution": string, "country": string, "year": integer|null}],
              "certifications": [{"name": string, "issuer": string, "year": integer|null}],
              "languages": [{"name": string, "level": string|null}],
              "preferred_countries": [string],
              "preferred_roles": [string],
              "salary_expectation": {"min": integer|null, "max": integer|null, "currency": string|null},
              "relocation_preference": "actively_wants_relocation"|"open_to_relocation"|"remote_only"|"no_relocation"|null,
              "needs_visa_sponsorship": boolean|null,
              "work_permit_notes": string|null,
              "summary": string|null
            }
        """.trimIndent()
    }
}

// ---- small typed readers, so a wrong type is dropped rather than coerced.

private fun JSONObject.text(key: String): String =
    if (isNull(key)) "" else optString(key).trim()

private fun JSONObject.intOrNull(key: String): Int? {
    if (isNull(key) || !has(key)) return null
    return when (val value = opt(key)) {
        is Number -> value.toInt()
        // A numeric string ("2018") is accepted; anything else is dropped, never defaulted to 0.
        is String -> value.trim().toIntOrNull()
        else -> null
    }
}

private fun JSONObject.optBooleanOrNull(key: String): Boolean? {
    if (isNull(key) || !has(key)) return null
    return when (val value = opt(key)) {
        is Boolean -> value
        is String -> when (value.trim().lowercase()) {
            "true" -> true
            "false" -> false
            else -> null
        }
        else -> null
    }
}

private fun JSONObject.stringList(key: String): List<String> {
    val array = optJSONArray(key) ?: return emptyList()
    return (0 until array.length()).mapNotNull { index ->
        if (array.isNull(index)) null else array.optString(index).trim().takeIf { it.isNotEmpty() }
    }
}

private fun <T> JSONObject.objList(key: String, transform: (JSONObject) -> T): List<T> {
    val array: JSONArray = optJSONArray(key) ?: return emptyList()
    return (0 until array.length()).mapNotNull { index -> array.optJSONObject(index)?.let(transform) }
}
