package com.aiglobal.jobfinder.data.repository

import android.content.Context
import android.net.Uri
import com.aiglobal.jobfinder.data.cv.CvAnalyzer
import com.aiglobal.jobfinder.data.cv.CvCompleteness
import com.aiglobal.jobfinder.data.cv.CvFileStore
import com.aiglobal.jobfinder.data.cv.CvParseException
import com.aiglobal.jobfinder.data.cv.CvParserRegistry
import com.aiglobal.jobfinder.data.cv.StoredCvFile
import com.aiglobal.jobfinder.data.model.CandidateField
import com.aiglobal.jobfinder.data.model.CandidateProfile
import com.aiglobal.jobfinder.data.model.CvRecord
import com.aiglobal.jobfinder.data.model.CvStatus
import com.aiglobal.jobfinder.data.model.ProfileCompleteness
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.io.File

/**
 * Owns the imported CV: the file in private storage, its analysis, and the user's manual
 * corrections. Persistence is a single JSON document written atomically, which is enough
 * for one record and avoids a database dependency.
 *
 * The file itself is never transmitted anywhere by this class.
 */
class CvRepository(
    private val context: Context,
    private val fileStore: CvFileStore,
    private val parserRegistry: CvParserRegistry,
    private val analyzer: CvAnalyzer,
    private val clock: () -> Long = System::currentTimeMillis,
) {

    private val _state = MutableStateFlow<CvRecord?>(null)
    val record: Flow<CvRecord?> = _state.asStateFlow()

    private val storeFile: File get() = File(context.filesDir, STORE_FILE)
    private val writeMutex = Mutex()

    init {
        _state.value = readPersisted()
    }

    val analyzerLabel: String get() = analyzer.label
    val analysisStaysOnDevice: Boolean get() = !analyzer.sendsDataOffDevice

    /** Copies a picked file in and records it. Does not analyse. */
    suspend fun importFile(uri: Uri): CvRecord {
        val stored = fileStore.import(uri)
        val previous = _state.value
        // Only one document is kept at a time, so drop the old copy once the new one lands.
        if (previous != null && previous.storedFileName != stored.storedName) {
            fileStore.delete(previous.storedFileName)
        }

        val record = CvRecord(
            fileName = stored.displayName,
            storedFileName = stored.storedName,
            fileType = stored.fileType,
            sizeBytes = stored.sizeBytes,
            importedAtEpochMillis = clock(),
            status = CvStatus.IMPORTED,
            profile = previous?.profile ?: CandidateProfile(),
            userEditedFields = previous?.userEditedFields ?: emptySet(),
        )
        persist(record)
        return record
    }

    /**
     * Parses and analyses the currently imported CV.
     *
     * @throws CvParseException when the document cannot be read.
     */
    suspend fun analyzeImported(): CvRecord {
        val current = _state.value ?: error("No CV has been imported yet.")
        val source = storedSource(current)
            ?: throw CvParseException.Unreadable()

        val parsed = parserRegistry.parse(source, current.fileType)

        // Nothing at all was recovered: fail honestly. A document that yielded a few
        // characters is kept — "partly read" is more truthful than "analysis failed", and it
        // lets the user see the fragment and its diagnostics without pretending it is whole.
        if (!parsed.hasAnythingExtractable) {
            val reason = CvParseException.NoText().message.orEmpty()
            val failed = current.copy(
                status = CvStatus.FAILED,
                pageCount = parsed.pageCount,
                extractedCharacters = parsed.characterCount,
                warnings = (current.warnings + parsed.warnings + reason).distinct(),
            )
            persist(failed)
            throw CvParseException.NoText()
        }

        val analysis = withContext(Dispatchers.Default) {
            analyzer.analyze(
                text = parsed.text,
                existing = current.profile,
                protectedFields = current.userEditedFields,
            )
        }

        val updated = current.copy(
            // Only call it "Analysed" when there was enough text to analyse.
            status = if (parsed.hasUsableText) CvStatus.ANALYZED else CvStatus.PARTIAL,
            analyzedAtEpochMillis = clock(),
            pageCount = parsed.pageCount,
            extractedCharacters = parsed.characterCount,
            profile = analysis.profile,
            engineId = analyzer.id,
            engineLabel = analyzer.label,
            warnings = (parsed.warnings + analysis.warnings).distinct(),
        )
        persist(updated)
        return updated
    }

    /** Applies a manual correction and locks the field against future re-analysis. */
    suspend fun updateField(field: CandidateField, value: String) {
        val current = _state.value ?: return
        val updated = current.copy(
            profile = applyEdit(current.profile, field, value),
            userEditedFields = current.userEditedFields + field,
        )
        persist(updated)
    }

    /** Replaces a list-valued field wholesale (skills, countries, …). */
    suspend fun updateList(field: CandidateField, values: List<String>) {
        val current = _state.value ?: return
        val cleaned = values.map { it.trim() }.filter { it.isNotEmpty() }
        val updated = current.copy(
            profile = when (field) {
                CandidateField.SKILLS -> current.profile.copy(skills = cleaned)
                CandidateField.PREFERRED_COUNTRIES -> current.profile.copy(preferredCountries = cleaned)
                CandidateField.PREFERRED_ROLES -> current.profile.copy(preferredRoles = cleaned)
                CandidateField.LANGUAGES -> current.profile.copy(
                    languages = cleaned.map { com.aiglobal.jobfinder.data.model.LanguageSkill(it) },
                )
                else -> current.profile
            },
            userEditedFields = current.userEditedFields + field,
        )
        persist(updated)
    }

    /** Forgets the CV entirely: file and analysis. */
    suspend fun remove() {
        val current = _state.value
        if (current != null) fileStore.delete(current.storedFileName)
        persist(null)
    }

    fun completeness(): ProfileCompleteness = CvCompleteness.of(_state.value?.profile ?: CandidateProfile())

    // ------------------------------------------------------------------ internals

    private fun storedSource(record: CvRecord): StoredCvFile? {
        if (!fileStore.exists(record.storedFileName)) return null
        return StoredCvFile(
            displayName = record.fileName,
            storedName = record.storedFileName,
            fileType = record.fileType,
            sizeBytes = record.sizeBytes,
            file = fileStore.open(record.storedFileName),
        )
    }

    private fun applyEdit(profile: CandidateProfile, field: CandidateField, value: String): CandidateProfile =
        when (field) {
            CandidateField.FULL_NAME -> profile.copy(fullName = value)
            CandidateField.EMAIL -> profile.copy(email = value)
            CandidateField.PHONE -> profile.copy(phone = value)
            CandidateField.LOCATION -> profile.copy(location = value)
            CandidateField.CURRENT_TITLE -> profile.copy(currentTitle = value)
            CandidateField.YEARS_OF_EXPERIENCE -> profile.copy(yearsOfExperience = value.toIntOrNull())
            CandidateField.SUMMARY -> profile.copy(summary = value)
            else -> profile
        }

    private suspend fun persist(record: CvRecord?) = writeMutex.withLock {
        withContext(Dispatchers.IO) {
            if (record == null) {
                runCatching { storeFile.delete() }
                _state.value = null
                return@withContext
            }
            val temp = File(storeFile.parentFile, "${storeFile.name}.tmp")
            temp.writeText(recordToJson(record).toString())
            if (!temp.renameTo(storeFile)) {
                temp.copyTo(storeFile, overwrite = true)
                temp.delete()
            }
            _state.value = record
        }
    }

    private fun readPersisted(): CvRecord? {
        if (!storeFile.isFile) return null
        return runCatching { cvRecordFromJson(JSONObject(storeFile.readText())) }
            .onFailure { runCatching { storeFile.delete() } }
            .getOrNull()
    }

    private companion object {
        const val STORE_FILE = "cv_record.json"
    }
}

// ---- serialisation. Hand-rolled so the persisted schema is explicit and versionable.

private const val SCHEMA_VERSION = 1

private fun recordToJson(record: CvRecord): JSONObject = JSONObject().apply {
    put("version", SCHEMA_VERSION)
    put("fileName", record.fileName)
    put("storedFileName", record.storedFileName)
    put("fileType", record.fileType.name)
    put("sizeBytes", record.sizeBytes)
    put("importedAt", record.importedAtEpochMillis)
    put("analyzedAt", record.analyzedAtEpochMillis ?: JSONObject.NULL)
    put("status", record.status.name)
    put("pageCount", record.pageCount ?: JSONObject.NULL)
    put("extractedCharacters", record.extractedCharacters)
    put("engineId", record.engineId ?: JSONObject.NULL)
    put("engineLabel", record.engineLabel ?: JSONObject.NULL)
    put("warnings", JSONArray(record.warnings))
    put("userEditedFields", JSONArray(record.userEditedFields.map { it.name }))
    put("profile", profileToJson(record.profile))
}

private fun profileToJson(profile: CandidateProfile): JSONObject = JSONObject().apply {
    put("fullName", profile.fullName)
    put("email", profile.email)
    put("phone", profile.phone)
    put("location", profile.location)
    put("currentTitle", profile.currentTitle)
    put("previousTitles", JSONArray(profile.previousTitles))
    put("yearsOfExperience", profile.yearsOfExperience ?: JSONObject.NULL)
    put("skills", JSONArray(profile.skills))
    put("industries", JSONArray(profile.industries))
    put(
        "languages",
        JSONArray(
            profile.languages.map {
                JSONObject().put("name", it.name).put("level", it.level?.name ?: JSONObject.NULL).put("evidence", it.evidence)
            },
        ),
    )
    put(
        "certifications",
        JSONArray(
            profile.certifications.map {
                JSONObject().put("name", it.name).put("issuer", it.issuer).put("year", it.year ?: JSONObject.NULL)
            },
        ),
    )
    put(
        "education",
        JSONArray(
            profile.education.map {
                JSONObject().put("degree", it.degree).put("institution", it.institution)
                    .put("country", it.country).put("year", it.year)
            },
        ),
    )
    put("preferredCountries", JSONArray(profile.preferredCountries))
    put("preferredRoles", JSONArray(profile.preferredRoles))
    put("salaryMin", profile.salaryExpectationMin ?: JSONObject.NULL)
    put("salaryMax", profile.salaryExpectationMax ?: JSONObject.NULL)
    put("salaryCurrency", profile.salaryCurrency ?: JSONObject.NULL)
    put("relocation", profile.relocationPreference.name)
    put("needsVisa", profile.needsVisaSponsorship ?: JSONObject.NULL)
    put("workPermitNotes", profile.workPermitNotes)
    put("summary", profile.summary)
}

private fun cvRecordFromJson(json: JSONObject): CvRecord = CvRecord(
    fileName = json.optString("fileName"),
    storedFileName = json.optString("storedFileName"),
    fileType = runCatching { com.aiglobal.jobfinder.data.model.CvFileType.valueOf(json.optString("fileType")) }
        .getOrDefault(com.aiglobal.jobfinder.data.model.CvFileType.PDF),
    sizeBytes = json.optLong("sizeBytes"),
    importedAtEpochMillis = json.optLong("importedAt"),
    analyzedAtEpochMillis = json.optLongOrNull("analyzedAt"),
    status = runCatching { CvStatus.valueOf(json.optString("status")) }.getOrDefault(CvStatus.IMPORTED),
    pageCount = json.optIntOrNull("pageCount"),
    extractedCharacters = json.optInt("extractedCharacters"),
    engineId = json.optStringOrNull("engineId"),
    engineLabel = json.optStringOrNull("engineLabel"),
    warnings = json.optJSONArray("warnings").toStringList(),
    userEditedFields = json.optJSONArray("userEditedFields").toStringList()
        .mapNotNull { runCatching { CandidateField.valueOf(it) }.getOrNull() }.toSet(),
    profile = json.optJSONObject("profile")?.let { candidateProfileFromJson(it) } ?: CandidateProfile(),
)

private fun candidateProfileFromJson(json: JSONObject): CandidateProfile = CandidateProfile(
    fullName = json.optString("fullName"),
    email = json.optString("email"),
    phone = json.optString("phone"),
    location = json.optString("location"),
    currentTitle = json.optString("currentTitle"),
    previousTitles = json.optJSONArray("previousTitles").toStringList(),
    yearsOfExperience = json.optIntOrNull("yearsOfExperience"),
    skills = json.optJSONArray("skills").toStringList(),
    industries = json.optJSONArray("industries").toStringList(),
    languages = json.optJSONArray("languages").toObjectList {
        com.aiglobal.jobfinder.data.model.LanguageSkill(
            name = it.optString("name"),
            level = it.optStringOrNull("level")?.let { raw ->
                runCatching { com.aiglobal.jobfinder.data.model.LanguageLevel.valueOf(raw) }.getOrNull()
            },
            evidence = it.optString("evidence"),
        )
    },
    certifications = json.optJSONArray("certifications").toObjectList {
        com.aiglobal.jobfinder.data.model.CertificationEntry(
            name = it.optString("name"),
            issuer = it.optString("issuer"),
            year = it.optIntOrNull("year"),
        )
    },
    education = json.optJSONArray("education").toObjectList {
        com.aiglobal.jobfinder.data.model.EducationEntry(
            degree = it.optString("degree"),
            institution = it.optString("institution"),
            country = it.optString("country"),
            year = it.optInt("year"),
        )
    },
    preferredCountries = json.optJSONArray("preferredCountries").toStringList(),
    preferredRoles = json.optJSONArray("preferredRoles").toStringList(),
    salaryExpectationMin = json.optIntOrNull("salaryMin"),
    salaryExpectationMax = json.optIntOrNull("salaryMax"),
    salaryCurrency = json.optStringOrNull("salaryCurrency"),
    relocationPreference = runCatching {
        com.aiglobal.jobfinder.data.model.RelocationPreference.valueOf(json.optString("relocation"))
    }.getOrDefault(com.aiglobal.jobfinder.data.model.RelocationPreference.UNKNOWN),
    needsVisaSponsorship = json.optBooleanOrNull("needsVisa"),
    workPermitNotes = json.optString("workPermitNotes"),
    summary = json.optString("summary"),
)

// ---- small JSON helpers, kept private to this file's persistence concern.

private fun JSONObject.optStringOrNull(key: String): String? =
    if (isNull(key)) null else optString(key).takeIf { it.isNotEmpty() }

private fun JSONObject.optIntOrNull(key: String): Int? =
    if (isNull(key) || !has(key)) null else optInt(key)

private fun JSONObject.optLongOrNull(key: String): Long? =
    if (isNull(key) || !has(key)) null else optLong(key)

private fun JSONObject.optBooleanOrNull(key: String): Boolean? =
    if (isNull(key) || !has(key)) null else optBoolean(key)

private fun JSONArray?.toStringList(): List<String> {
    if (this == null) return emptyList()
    return (0 until length()).mapNotNull { index ->
        if (isNull(index)) null else optString(index).takeIf { it.isNotEmpty() }
    }
}

private inline fun <T> JSONArray?.toObjectList(transform: (JSONObject) -> T): List<T> {
    if (this == null) return emptyList()
    return (0 until length()).mapNotNull { index -> optJSONObject(index)?.let(transform) }
}
