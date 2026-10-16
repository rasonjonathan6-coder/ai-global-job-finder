package com.aiglobal.jobfinder.data.model

import androidx.compose.runtime.Immutable

/** Supported CV formats. Anything else is rejected before a byte is read. */
enum class CvFileType(val label: String, val extensions: List<String>, val mimeTypes: List<String>) {
    PDF(
        label = "PDF",
        extensions = listOf("pdf"),
        mimeTypes = listOf("application/pdf"),
    ),
    DOC(
        label = "Word 97-2003",
        extensions = listOf("doc"),
        mimeTypes = listOf(
            "application/msword",
            "application/vnd.ms-word",
        ),
    ),
    DOCX(
        label = "Word document",
        extensions = listOf("docx"),
        mimeTypes = listOf(
            "application/vnd.openxmlformats-officedocument.wordprocessingml.document",
            "application/zip",
        ),
    ),
    TXT(
        label = "Plain text",
        extensions = listOf("txt"),
        mimeTypes = listOf("text/plain"),
    );

    companion object {
        /** Resolves a type from the picker's display name and/or reported MIME type. */
        fun resolve(displayName: String?, mimeType: String?): CvFileType? {
            val extension = displayName?.substringAfterLast('.', "")?.lowercase()
            entries.firstOrNull { it.extensions.contains(extension) }?.let { return it }
            val normalized = mimeType?.lowercase()
            return entries.firstOrNull { type -> type.mimeTypes.contains(normalized) }
        }
    }
}

/** Where a stored value came from. User edits always win over the analyser. */
enum class FieldOrigin { EXTRACTED, USER }

/** Editable sections of a [CandidateProfile]. Used to track user overrides. */
enum class CandidateField(val label: String) {
    FULL_NAME("Name"),
    EMAIL("Email"),
    PHONE("Phone"),
    LOCATION("Location"),
    CURRENT_TITLE("Professional title"),
    PREVIOUS_TITLES("Previous titles"),
    YEARS_OF_EXPERIENCE("Years of experience"),
    SKILLS("Skills"),
    INDUSTRIES("Industries"),
    EDUCATION("Education"),
    CERTIFICATIONS("Certifications"),
    LANGUAGES("Languages"),
    PREFERRED_COUNTRIES("Preferred countries"),
    PREFERRED_ROLES("Target roles"),
    SALARY_EXPECTATION("Salary expectation"),
    RELOCATION("Relocation preference"),
    VISA("Visa / work permit"),
    SUMMARY("Professional summary"),
}

/** Proficiency for a spoken language. Only ever set when the CV actually states it. */
enum class LanguageLevel(val label: String) {
    BEGINNER("Beginner"),
    INTERMEDIATE("Intermediate"),
    ADVANCED("Advanced"),
    FLUENT("Fluent"),
    NATIVE("Native");

    companion object {
        /**
         * Maps the many ways a CV writes proficiency onto a level. Returns null when the
         * document does not say, so we never guess a level the candidate did not claim.
         */
        fun fromToken(token: String?): LanguageLevel? {
            val t = token?.trim()?.lowercase() ?: return null
            if (t.isEmpty()) return null
            return when {
                t.startsWith("native") || t.startsWith("mother") || t.startsWith("langue maternelle") ||
                    t.startsWith("natif") || t.startsWith("bilingue") -> NATIVE
                t.startsWith("fluent") || t.startsWith("courant") || t.startsWith("maternelle") -> FLUENT
                t.startsWith("adv") || t.startsWith("c1") || t.startsWith("c2") || t.startsWith("avanc") -> ADVANCED
                t.startsWith("inter") || t.startsWith("b1") || t.startsWith("b2") || t.startsWith("moyen") ||
                    t.startsWith("intermed") -> INTERMEDIATE
                t.startsWith("beg") || t.startsWith("basic") || t.startsWith("a1") || t.startsWith("a2") ||
                    t.startsWith("debut") || t.startsWith("début") || t.startsWith("notion") -> BEGINNER
                else -> null
            }
        }
    }
}

@Immutable
data class LanguageSkill(
    val name: String,
    val level: LanguageLevel? = null,
    /** The phrase in the CV that produced this entry, shown so the user can verify it. */
    val evidence: String = "",
) {
    val displayLabel: String get() = if (level == null) name else "$name — ${level.label}"
}

@Immutable
data class CertificationEntry(
    val name: String,
    val issuer: String = "",
    val year: Int? = null,
)

/** How willing the candidate is to move country. Drives Stage 3 eligibility filtering. */
enum class RelocationPreference(val label: String) {
    UNKNOWN("Not specified"),
    PREFERS_RELOCATION("Actively wants to relocate"),
    OPEN_TO_RELOCATION("Open to relocating"),
    REMOTE_ONLY("Remote only"),
    NO_RELOCATION("Not willing to relocate"),
}

/**
 * The structured, matching-ready view of the candidate. Stage 3 compares this against
 * job descriptions, so every field is a plain value rather than a rendered string.
 *
 * Nothing here is invented: each value is either extracted from the CV text, entered by
 * the user, or left empty.
 */
@Immutable
data class CandidateProfile(
    val fullName: String = "",
    val email: String = "",
    val phone: String = "",
    val location: String = "",
    val currentTitle: String = "",
    val previousTitles: List<String> = emptyList(),
    val yearsOfExperience: Int? = null,
    val skills: List<String> = emptyList(),
    val industries: List<String> = emptyList(),
    val education: List<EducationEntry> = emptyList(),
    val certifications: List<CertificationEntry> = emptyList(),
    val languages: List<LanguageSkill> = emptyList(),
    val preferredCountries: List<String> = emptyList(),
    val preferredRoles: List<String> = emptyList(),
    val salaryExpectationMin: Int? = null,
    val salaryExpectationMax: Int? = null,
    val salaryCurrency: String? = null,
    val relocationPreference: RelocationPreference = RelocationPreference.UNKNOWN,
    val needsVisaSponsorship: Boolean? = null,
    val workPermitNotes: String = "",
    val summary: String = "",
) {
    val initials: String
        get() = fullName.trim().split(" ").filter { it.isNotBlank() }.take(2)
            .joinToString("") { it.first().uppercase() }
            .ifEmpty { "CV" }

    /** Job titles the candidate can be matched on, most recent first. */
    val allTitles: List<String>
        get() = (listOf(currentTitle) + previousTitles).filter { it.isNotBlank() }.distinct()
}

/** Lifecycle of the imported document. */
enum class CvStatus(val label: String) {
    IMPORTED("Imported — not analysed"),
    ANALYZED("Analysed"),
    /** Text was recovered but not enough to build a profile around. */
    PARTIAL("Partly read"),
    FAILED("Analysis failed"),
}

/**
 * Everything the app knows about the user's CV: the file reference plus the analysis
 * result. Persisted as JSON; the file itself lives in app-private storage.
 */
@Immutable
data class CvRecord(
    val fileName: String,
    val storedFileName: String,
    val fileType: CvFileType,
    val sizeBytes: Long,
    val importedAtEpochMillis: Long,
    val analyzedAtEpochMillis: Long? = null,
    val status: CvStatus = CvStatus.IMPORTED,
    val pageCount: Int? = null,
    val extractedCharacters: Int = 0,
    val profile: CandidateProfile = CandidateProfile(),
    /** Fields the user corrected; a later analysis must not overwrite these. */
    val userEditedFields: Set<CandidateField> = emptySet(),
    /** Identifier of the engine that produced the current values. */
    val engineId: String? = null,
    val engineLabel: String? = null,
    /** Non-fatal notes from parsing or analysis, shown as-is to the user. */
    val warnings: List<String> = emptyList(),
) {
    val originOf: (CandidateField) -> FieldOrigin
        get() = { field -> if (userEditedFields.contains(field)) FieldOrigin.USER else FieldOrigin.EXTRACTED }
}

/** A single gap the user could close, with the section it belongs to. */
@Immutable
data class CompletenessHint(
    val label: String,
    val field: CandidateField,
)

/**
 * Profile completeness derived from real data. [missing] is always non-empty unless the
 * profile is genuinely full, so the UI can never claim completion that is not there.
 */
@Immutable
data class ProfileCompleteness(
    val percent: Int,
    val missing: List<CompletenessHint>,
) {
    val isComplete: Boolean get() = percent >= 100
}
