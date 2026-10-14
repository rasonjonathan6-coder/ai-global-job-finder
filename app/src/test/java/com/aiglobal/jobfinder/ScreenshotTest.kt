package com.aiglobal.jobfinder

import android.graphics.Bitmap
import android.graphics.Canvas
import android.os.Looper
import androidx.activity.ComponentActivity
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.test.core.app.ApplicationProvider
import com.aiglobal.jobfinder.data.model.ApplicationStatus
import com.aiglobal.jobfinder.data.model.BenefitEvidence
import com.aiglobal.jobfinder.data.model.BenefitStatus
import com.aiglobal.jobfinder.data.model.CandidateField
import com.aiglobal.jobfinder.data.model.CandidateProfile
import com.aiglobal.jobfinder.data.model.ExpatPreferences
import com.aiglobal.jobfinder.data.model.Job
import com.aiglobal.jobfinder.data.model.JobBenefitTypes
import com.aiglobal.jobfinder.data.model.MadagascarEligibility
import com.aiglobal.jobfinder.data.model.ShortlistState
import com.aiglobal.jobfinder.data.model.CertificationEntry
import com.aiglobal.jobfinder.data.model.CvFileType
import com.aiglobal.jobfinder.data.model.CvRecord
import com.aiglobal.jobfinder.data.model.CvStatus
import com.aiglobal.jobfinder.data.model.EducationEntry
import com.aiglobal.jobfinder.data.model.LanguageLevel
import com.aiglobal.jobfinder.data.model.LetterLanguage
import com.aiglobal.jobfinder.data.model.LanguageSkill
import com.aiglobal.jobfinder.data.model.ProfileCompleteness
import com.aiglobal.jobfinder.data.model.CompletenessHint
import com.aiglobal.jobfinder.data.model.CoverLetter
import com.aiglobal.jobfinder.data.model.ScamRisk
import com.aiglobal.jobfinder.data.expat.ExpatBenefitsCatalog
import com.aiglobal.jobfinder.data.expat.ShortlistEngine
import com.aiglobal.jobfinder.data.interview.InterviewCoach
import com.aiglobal.jobfinder.data.model.AutoApplyItem
import com.aiglobal.jobfinder.data.model.AutoApplyState
import com.aiglobal.jobfinder.data.model.AutoApplyStatus
import com.aiglobal.jobfinder.data.model.JobAlert
import com.aiglobal.jobfinder.data.model.JobAlertRule
import com.aiglobal.jobfinder.data.model.JobAlertsState
import com.aiglobal.jobfinder.data.repository.SampleData
import com.aiglobal.jobfinder.data.repository.SampleJobRepository
import com.aiglobal.jobfinder.data.jobs.provider.AdzunaCredentials
import com.aiglobal.jobfinder.data.apply.ApplicationUrlInspector
import com.aiglobal.jobfinder.data.model.ProviderDiagnostic
import com.aiglobal.jobfinder.data.model.ProviderDiagnostics
import com.aiglobal.jobfinder.data.model.ProviderStatus
import com.aiglobal.jobfinder.data.scam.ScamDetector
import com.aiglobal.jobfinder.ui.components.JobCard
import com.aiglobal.jobfinder.ui.navigation.BottomNavBar
import com.aiglobal.jobfinder.ui.navigation.Destination
import com.aiglobal.jobfinder.ui.screens.ai.AiScreen
import com.aiglobal.jobfinder.ui.screens.alerts.JobAlertsContent
import com.aiglobal.jobfinder.ui.screens.applicationdetail.ApplicationDetailContent
import com.aiglobal.jobfinder.ui.screens.applications.ApplicationsScreen
import com.aiglobal.jobfinder.ui.screens.autoapply.AutoApplyContent
import com.aiglobal.jobfinder.ui.screens.coverletter.CoverLetterContent
import com.aiglobal.jobfinder.ui.screens.cv.CvScreen
import com.aiglobal.jobfinder.ui.screens.diagnostics.LiveSearchValidationContent
import com.aiglobal.jobfinder.ui.screens.expat.ExpatContent
import com.aiglobal.jobfinder.ui.screens.home.HomeContent
import com.aiglobal.jobfinder.ui.screens.interview.InterviewContent
import com.aiglobal.jobfinder.ui.screens.jobs.ApplyLinkSheetBody
import com.aiglobal.jobfinder.ui.screens.jobs.JobsContent
import com.aiglobal.jobfinder.ui.screens.profile.JobSourcesCard
import com.aiglobal.jobfinder.ui.screens.profile.ProfileScreen
import com.aiglobal.jobfinder.ui.screens.scam.ScamShieldContent
import com.aiglobal.jobfinder.ui.screens.shortlist.ShortlistContent
import com.aiglobal.jobfinder.ui.theme.AiJobFinderTheme
import com.aiglobal.jobfinder.ui.theme.ThemeMode
import com.aiglobal.jobfinder.viewmodel.AppViewModel
import com.aiglobal.jobfinder.viewmodel.ApplicationDetailUiState
import com.aiglobal.jobfinder.viewmodel.ApplicationsViewModel
import com.aiglobal.jobfinder.viewmodel.CoverLetterUiState
import com.aiglobal.jobfinder.viewmodel.CvUiState
import com.aiglobal.jobfinder.viewmodel.ExpatUiState
import com.aiglobal.jobfinder.viewmodel.HomeUiState
import com.aiglobal.jobfinder.viewmodel.InterviewUiState
import com.aiglobal.jobfinder.viewmodel.JobsUiState
import com.aiglobal.jobfinder.viewmodel.ProfileViewModel
import com.aiglobal.jobfinder.viewmodel.ScamUiState
import com.aiglobal.jobfinder.viewmodel.SourceDiagnosticsUiState
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import java.io.File

/**
 * Renders every Stage 1 screen at a real phone size on the JVM and writes PNGs to
 * app/build/screenshots. This is the layout verification path; no emulator needed.
 *
 * The Compose test rule owns the frame clock, advanced by hand so the infinite shimmer
 * animations cannot stall the run while finite entry animations still finish before
 * the frame is captured.
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [33], qualifiers = "w411dp-h891dp-xhdpi")
class ScreenshotTest {

    @get:Rule
    val composeRule = createAndroidComposeRule<ComponentActivity>()

    private val repository = SampleJobRepository(refreshDelayMillis = 0)

    /** The real container, used for the Stage 4 screens that need the application pipeline. */
    private val container by lazy { AppContainer(ApplicationProvider.getApplicationContext()) }

    private val homeState: HomeUiState by lazy {
        val jobs = runBlocking { repository.jobs.first() }
        HomeUiState(
            isLoading = false,
            profile = runBlocking { repository.profile.first() },
            stats = runBlocking { repository.stats.first() },
            bestMatches = jobs.sortedByDescending { it.matchScore }.take(4),
            newOpportunities = jobs.filter { it.isNew },
        )
    }

    private val jobsState: JobsUiState by lazy {
        JobsUiState(isLoading = false, jobs = runBlocking { repository.jobs.first() })
    }

    /** A realistic analysed CV so the result sections are exercised in the screenshot run. */
    private val sampleCvRecord = CvRecord(
        fileName = "Jonathan_Rakoto_CV.pdf",
        storedFileName = "sample.pdf",
        fileType = CvFileType.PDF,
        sizeBytes = 248_320,
        importedAtEpochMillis = 1_760_000_000_000,
        analyzedAtEpochMillis = 1_760_000_120_000,
        status = CvStatus.ANALYZED,
        pageCount = 2,
        extractedCharacters = 4_812,
        engineId = "on-device-heuristic",
        engineLabel = "On-device analysis",
        userEditedFields = setOf(CandidateField.CURRENT_TITLE),
        profile = CandidateProfile(
            fullName = "Jonathan Rakoto",
            email = "jonathan.rakoto@example.mg",
            phone = "+261 34 12 345 67",
            location = "Antananarivo, Madagascar",
            currentTitle = "Maintenance Technician",
            previousTitles = listOf("Electromechanical Technician"),
            yearsOfExperience = 6,
            skills = listOf("Preventive maintenance", "Hydraulics", "PLC", "Electrical wiring", "Welding", "Team leadership"),
            languages = listOf(
                LanguageSkill("French", LanguageLevel.FLUENT),
                LanguageSkill("English", LanguageLevel.INTERMEDIATE),
                LanguageSkill("Malagasy", LanguageLevel.NATIVE),
            ),
            education = listOf(EducationEntry("BTS Electrotechnics", "IST Antananarivo", "Madagascar", 2018)),
            certifications = listOf(CertificationEntry("Industrial Safety Level 2", year = 2021)),
            summary = "Maintenance technician with six years in food-processing plants.",
        ),
    )

    @Test
    fun homeScreen() = renderBothModes("home") {
        HomeContent(state = homeState, onOpenJobs = {}, onOpenJob = {})
    }

    @Test
    fun jobCard() {
        val job = runBlocking { repository.jobs.first() }.first { it.matchScore == 95 }
        renderBothModes("job-card") {
            Box(Modifier.fillMaxSize().background(MaterialTheme.colorScheme.background)) {
                JobCard(
                    job = job,
                    onViewJob = {},
                    onApply = {},
                    onToggleSave = {},
                    expatScore = 100,
                    expatEvidenceCoverage = 67,
                    expatLowEvidence = false,
                )
            }
        }
    }

    @Test
    fun jobsList() = renderBothModes("jobs") {
        JobsContent(
            state = jobsState,
            onQueryChange = {},
            onToggleSaved = {},
            onToggleEligible = {},
            onToggleSponsored = {},
            onCountryChange = {},
            onSourceChange = {},
            onSortChange = {},
            onClearFilters = {},
            onRefresh = {},
            onOpenAi = {},
            onToggleSaveJob = {},
        )
    }

    @Test
    fun jobsEmptyState() = renderBothModes("jobs-empty") {
        JobsContent(
            state = JobsUiState(isLoading = false, jobs = emptyList(), query = "zzz"),
            onQueryChange = {},
            onToggleSaved = {},
            onToggleEligible = {},
            onToggleSponsored = {},
            onCountryChange = {},
            onSourceChange = {},
            onSortChange = {},
            onClearFilters = {},
            onRefresh = {},
            onOpenAi = {},
            onToggleSaveJob = {},
        )
    }

    @Test
    fun aiScreen() = renderBothModes("ai") { AiScreen(onBrowseJobs = {}, onOpenCv = {}, autoRun = false) }

    @Test
    fun cvImportEmpty() = renderBothModes("cv-empty") {
        CvScreen(
            state = CvUiState(isLoading = false),
            onBack = {},
            onPickFile = {},
            onAnalyze = {},
            onRemove = {},
            onEditField = { _, _ -> },
            onDismissMessage = {},
        )
    }

    @Test
    fun cvAnalysisResult() = renderBothModes("cv-analysis") {
        CvScreen(
            state = CvUiState(
                isLoading = false,
                record = sampleCvRecord,
                completeness = ProfileCompleteness(
                    percent = 82,
                    missing = listOf(CompletenessHint("Preferred countries", CandidateField.PREFERRED_COUNTRIES)),
                ),
            ),
            onBack = {},
            onPickFile = {},
            onAnalyze = {},
            onRemove = {},
            onEditField = { _, _ -> },
            onDismissMessage = {},
        )
    }

    @Test
    fun applicationsScreen() = renderBothModes("applications") {
        ApplicationsScreen(
            viewModel = ApplicationsViewModel(repository, container.applicationCenter),
            onBrowseJobs = {},
        )
    }

    @Test
    fun applicationDetail() {
        val record = runBlocking { SampleData.applications(System.currentTimeMillis()) }
            .first { it.status == ApplicationStatus.INTERVIEW }
        renderBothModes("application-detail") {
            ApplicationDetailContent(
                state = ApplicationDetailUiState(
                    isLoading = false,
                    record = record,
                    nextAction = "Prepare for the interview and confirm the time zone.",
                ),
                onBack = {},
                onOpenCoverLetter = {},
                onOpenExpat = {},
                onSetStatus = {},
                onSetFollowUpAt = {},
                onArchive = {},
                onDelete = {},
            )
        }
    }

    @Test
    fun coverLetter() = renderBothModes("cover-letter") {
        CoverLetterContent(
            state = CoverLetterUiState(
                isLoading = false,
                job = runBlocking { repository.jobs.first() }.first(),
                language = LetterLanguage.FRENCH,
                letter = CoverLetter(
                    id = "letter-1",
                    jobId = "ca-mtc-001",
                    language = LetterLanguage.FRENCH,
                    subject = "Candidature — Maintenance Technician",
                    salutation = "Madame, Monsieur,",
                    paragraphs = listOf(
                        "Je vous adresse ma candidature au poste de Maintenance Technician.",
                        "Technicien de maintenance avec six ans d'expérience en industrie agroalimentaire.",
                    ),
                    closing = "Je vous prie d'agréer mes salutations distinguées.",
                    signature = "Jonathan Rakoto",
                    usedFacts = listOf("6 ans d'expérience", "Compétences : hydraulique, PLC"),
                    missingFacts = listOf("Certification linguistique"),
                    generatedAtEpochMillis = System.currentTimeMillis(),
                ),
            ),
            onBack = {},
            onSelectLanguage = {},
            onGenerate = {},
        )
    }

    @Test
    fun expatGuide() = renderBothModes("expat") {
        ExpatContent(
            state = ExpatUiState(
                isLoading = false,
                guide = ExpatBenefitsCatalog.forCountry("Canada"),
            ),
            country = "Canada",
            onBack = {},
        )
    }

    @Test
    fun profileScreen() {
        renderBothModes("profile") {
            ProfileScreen(
                viewModel = ProfileViewModel(repository),
                appViewModel = AppViewModel(container),
                onOpenAi = {},
            )
        }
    }

    @Test
    fun jobSourcesSettings() = renderBothModes("job-sources") {
        JobSourcesCard(
            credentials = AdzunaCredentials(appId = "abc123", appKey = "def456"),
            joobleKey = "jooble-key",
            diagnostics = ProviderDiagnostics(
                providers = listOf(
                    diagnostic("arbeitnow", "Arbeitnow", ProviderStatus.SUCCESS_WITH_RESULTS),
                    diagnostic("remotive", "Remotive", ProviderStatus.SUCCESS_EMPTY, message = "The source answered and had no matching jobs."),
                    diagnostic("adzuna", "Adzuna", ProviderStatus.PARTIAL_SUCCESS, message = "2 records did not pass validation."),
                    diagnostic("jooble", "Jooble", ProviderStatus.NETWORK_ERROR, message = "The source could not be reached."),
                    diagnostic("jobicy", "Jobicy", ProviderStatus.NOT_CONFIGURED, message = "This source needs a key before it can be searched."),
                    diagnostic("himalayas", "Himalayas", ProviderStatus.DISABLED, enabled = false, message = "You switched this source off."),
                ),
                lastAttemptAtEpochMillis = 1_700_000_000_000L,
            ),
            onSaveAdzuna = { _, _ -> },
            onSaveJooble = {},
        )
    }

    @Test
    fun liveSearchValidation() = renderBothModes("live-search-validation") {
        LiveSearchValidationContent(
            state = SourceDiagnosticsUiState(
                diagnostics = ProviderDiagnostics(
                    providers = listOf(
                        diagnostic("arbeitnow", "Arbeitnow", ProviderStatus.SUCCESS_WITH_RESULTS, validJobCount = 18),
                        diagnostic("adzuna", "Adzuna", ProviderStatus.RATE_LIMITED, message = "The source is rate limiting this device. Try again later."),
                        diagnostic("jooble", "Jooble", ProviderStatus.NOT_CONFIGURED),
                    ),
                    lastAttemptAtEpochMillis = 1_700_000_000_000L,
                    showingCachedOnly = true,
                ),
                note = "1 source(s) answered, 1 failed. Kept the good results.",
            ),
            onBack = {},
        )
    }

    @Test
    fun applyLinkSheetContent() = renderBothModes("apply-link") {
        val job = runBlocking { repository.jobs.first() }.first()
        ApplyLinkSheetBody(
            job = job,
            inspection = ApplicationUrlInspector().inspect("http://job-boards.freehost.tk/apply/12345"),
            onDismiss = {},
            onOpen = {},
        )
    }

    private fun diagnostic(
        id: String,
        name: String,
        status: ProviderStatus,
        enabled: Boolean = true,
        validJobCount: Int = 0,
        message: String? = null,
    ) = ProviderDiagnostic(
        providerId = id,
        name = name,
        homepage = "https://example.com",
        documentationUrl = "https://example.com/docs",
        status = status,
        enabled = enabled,
        validJobCount = validJobCount,
        lastAttemptAtEpochMillis = 1_700_000_000_000L,
        lastDurationMillis = 412,
        message = message,
    )

    @Test
    fun bottomNavigation() = renderBothModes("bottom-nav") {
        Column(
            modifier = Modifier.fillMaxSize().background(MaterialTheme.colorScheme.background),
            verticalArrangement = Arrangement.Bottom,
        ) {
            BottomNavBar(current = Destination.Jobs, onSelect = {})
        }
    }

    // ---- Stage 11 screen ---------------------------------------------------

    private val shortlistState: ShortlistState by lazy {
        val profile = CandidateProfile(
            fullName = "Jonathan Rakoto",
            currentTitle = "Maintenance Technician",
            yearsOfExperience = 6,
            skills = listOf("Preventive maintenance", "Hydraulics", "PLC"),
        )
        val engine = ShortlistEngine(clock = { stage5Now })
        val strongBenefits = listOf(
            BenefitEvidence(JobBenefitTypes.VISA_SPONSORSHIP, BenefitStatus.CONFIRMED, "Visa sponsorship provided."),
            BenefitEvidence(JobBenefitTypes.RELOCATION_SUPPORT, BenefitStatus.CONFIRMED, "Relocation support is included."),
            BenefitEvidence(JobBenefitTypes.ACCOMMODATION, BenefitStatus.CONFIRMED, "Accommodation is provided."),
            BenefitEvidence(JobBenefitTypes.HEALTH_INSURANCE, BenefitStatus.CONFIRMED, "Private health insurance."),
        )
        fun entry(job: Job, completeness: Int) =
            engine.assess(job, profile, ExpatPreferences.defaults(), completeness, 60000)

        val ready = entry(
            runBlocking { repository.jobs.first() }.first { it.matchScore == 95 }.copy(benefitEvidence = strongBenefits),
            completeness = 100,
        )
        val verify = entry(
            runBlocking { repository.jobs.first() }
                .first { it.country == "Germany" }
                .copy(
                    matchScore = 72,
                    eligibility = MadagascarEligibility.UNCLEAR,
                    eligibilityReasons = emptyList(),
                    benefitEvidence = emptyList(),
                    missingSkills = listOf("German B2", "SPS/IPC drives"),
                ),
            completeness = 60,
        )
        ShortlistState(
            isLoading = false,
            entries = listOf(ready, verify),
            totalJobs = 214,
            sourceNote = "Live from 3 sources.",
        )
    }

    // ---- Stage 5 screens ---------------------------------------------------

    private val stage5Now = 1_700_000_000_000L

    @Test
    fun interviewCoach() = renderBothModes("interview") {
        val job = runBlocking { repository.jobs.first() }.first { it.matchScore == 95 }
        InterviewContent(
            state = InterviewUiState(
                isLoading = false,
                job = job,
                prep = InterviewCoach(clock = { stage5Now }).prep(
                    job,
                    CandidateProfile(
                        fullName = "Jonathan Rakoto",
                        currentTitle = "Maintenance Technician",
                        yearsOfExperience = 6,
                        skills = listOf("Preventive maintenance", "Hydraulics", "PLC"),
                    ),
                ),
            ),
            onBack = {},
        )
    }

    @Test
    fun scamShield() = renderBothModes("scam-shield") {
        val job = runBlocking { repository.jobs.first() }.first { it.scamRisk == ScamRisk.HIGH }
        ScamShieldContent(
            state = ScamUiState(isLoading = false, job = job, report = ScamDetector(clock = { stage5Now }).scan(job)),
            onBack = {},
        )
    }

    @Test
    fun jobAlerts() = renderBothModes("job-alerts") {
        JobAlertsContent(
            state = JobAlertsState(
                rules = listOf(
                    JobAlertRule(
                        id = "r1",
                        name = "Visa-sponsored roles",
                        countries = listOf("Canada"),
                        minMatchScore = 60,
                        createdAtEpochMillis = stage5Now,
                    ),
                ),
                alerts = listOf(
                    JobAlert(
                        ruleId = "r1",
                        ruleName = "Visa-sponsored roles",
                        jobId = "ca-mtc-001",
                        jobTitle = "Maintenance Technician",
                        company = "Northline Industrial",
                        country = "Canada",
                        countryFlag = "🇨🇦",
                        matchScore = 95,
                        isVisaFriendly = true,
                        detectedAtEpochMillis = stage5Now,
                    ),
                ),
            ),
            onBack = {},
        )
    }

    @Test
    fun autoApply() = renderBothModes("auto-apply") {
        AutoApplyContent(
            state = AutoApplyState(
                enabled = true,
                threshold = 70,
                items = listOf(
                    AutoApplyItem(
                        jobId = "ca-mtc-001",
                        jobTitle = "Maintenance Technician",
                        company = "Northline Industrial",
                        country = "Canada",
                        countryFlag = "🇨🇦",
                        matchScore = 95,
                        sourceUrl = "https://www.jobbank.gc.ca",
                        status = AutoApplyStatus.DRAFT_READY,
                        reason = "Match 95% and open to Madagascar applicants. Draft ready for your approval.",
                        preparedAtEpochMillis = stage5Now,
                    ),
                ),
            ),
            onBack = {},
        )
    }

    @Test
    fun shortlist() = renderBothModes("shortlist") {
        ShortlistContent(state = shortlistState, onBack = {}, onRefresh = {})
    }

    /**
     * One setContent per test (the rule forbids more), then the theme is flipped through
     * state so both colour schemes are captured from the same, already-settled tree.
     */
    private fun renderBothModes(name: String, content: @Composable () -> Unit) {
        val mode = mutableStateOf(ThemeMode.Light)
        composeRule.mainClock.autoAdvance = false
        composeRule.setContent {
            AiJobFinderTheme(themeMode = mode.value) {
                Box(Modifier.fillMaxSize().background(MaterialTheme.colorScheme.background)) {
                    content()
                }
            }
        }

        settle()
        capture("$name-light")

        mode.value = ThemeMode.Dark
        settle()
        capture("$name-dark")
    }

    /** Advance virtual time in steps, interleaved with looper work so frames are produced. */
    private fun settle() {
        val mainLooper = shadowOf(Looper.getMainLooper())
        repeat(30) {
            composeRule.mainClock.advanceTimeByFrame()
            composeRule.mainClock.advanceTimeBy(60)
            mainLooper.idle()
        }
    }

    private fun capture(name: String) {
        val view = composeRule.activity.window.decorView
        val width = view.width
        val height = view.height
        require(width > 0 && height > 0) { "$name laid out at ${width}x$height" }

        val bitmap = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
        view.draw(Canvas(bitmap))

        val dir = File("build/screenshots").apply { mkdirs() }
        File(dir, "$name.png").outputStream().use { out ->
            bitmap.compress(Bitmap.CompressFormat.PNG, 100, out)
        }
    }
}
