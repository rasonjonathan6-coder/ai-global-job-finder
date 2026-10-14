package com.aiglobal.jobfinder.ui

import androidx.compose.animation.AnimatedContentTransitionScope
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideOutHorizontally
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.navigation.NavGraph.Companion.findStartDestination
import androidx.navigation.NavType
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.currentBackStackEntryAsState
import androidx.navigation.compose.rememberNavController
import androidx.navigation.navArgument
import com.aiglobal.jobfinder.AppContainer
import com.aiglobal.jobfinder.ui.navigation.BottomNavBar
import com.aiglobal.jobfinder.ui.navigation.Destination
import com.aiglobal.jobfinder.ui.screens.ai.AiScreen
import com.aiglobal.jobfinder.ui.screens.alerts.JobAlertsScreen
import com.aiglobal.jobfinder.ui.screens.applicationdetail.ApplicationDetailScreen
import com.aiglobal.jobfinder.ui.screens.applications.ApplicationsScreen
import com.aiglobal.jobfinder.ui.screens.autoapply.AutoApplyScreen
import com.aiglobal.jobfinder.ui.screens.coverletter.CoverLetterScreen
import com.aiglobal.jobfinder.ui.screens.cv.CvScreen
import com.aiglobal.jobfinder.ui.screens.diagnostics.LiveSearchValidationScreen
import com.aiglobal.jobfinder.ui.screens.expat.ExpatScreen
import com.aiglobal.jobfinder.ui.screens.home.HomeScreen
import com.aiglobal.jobfinder.ui.screens.interview.InterviewScreen
import com.aiglobal.jobfinder.ui.screens.jobs.JobsScreen
import com.aiglobal.jobfinder.ui.screens.profile.ProfileScreen
import com.aiglobal.jobfinder.ui.screens.scam.ScamShieldScreen
import com.aiglobal.jobfinder.ui.screens.shortlist.ShortlistScreen
import com.aiglobal.jobfinder.viewmodel.AppViewModel
import com.aiglobal.jobfinder.viewmodel.ApplicationDetailViewModel
import com.aiglobal.jobfinder.viewmodel.ApplicationDetailViewModelFactory
import com.aiglobal.jobfinder.viewmodel.ApplicationsViewModel
import com.aiglobal.jobfinder.viewmodel.ApplicationsViewModelFactory
import com.aiglobal.jobfinder.viewmodel.AutoApplyViewModel
import com.aiglobal.jobfinder.viewmodel.AutoApplyViewModelFactory
import com.aiglobal.jobfinder.viewmodel.CoverLetterViewModel
import com.aiglobal.jobfinder.viewmodel.CoverLetterViewModelFactory
import com.aiglobal.jobfinder.viewmodel.CvViewModel
import com.aiglobal.jobfinder.viewmodel.CvViewModelFactory
import com.aiglobal.jobfinder.viewmodel.ExpatGuideViewModel
import com.aiglobal.jobfinder.viewmodel.ExpatGuideViewModelFactory
import com.aiglobal.jobfinder.viewmodel.HomeViewModel
import com.aiglobal.jobfinder.viewmodel.HomeViewModelFactory
import com.aiglobal.jobfinder.viewmodel.InterviewViewModel
import com.aiglobal.jobfinder.viewmodel.InterviewViewModelFactory
import com.aiglobal.jobfinder.viewmodel.JobAlertsViewModel
import com.aiglobal.jobfinder.viewmodel.JobAlertsViewModelFactory
import com.aiglobal.jobfinder.viewmodel.JobsViewModel
import com.aiglobal.jobfinder.viewmodel.JobsViewModelFactory
import com.aiglobal.jobfinder.viewmodel.ProfileViewModel
import com.aiglobal.jobfinder.viewmodel.ProfileViewModelFactory
import com.aiglobal.jobfinder.viewmodel.ScamShieldViewModel
import com.aiglobal.jobfinder.viewmodel.ScamShieldViewModelFactory
import com.aiglobal.jobfinder.viewmodel.ShortlistViewModel
import com.aiglobal.jobfinder.viewmodel.ShortlistViewModelFactory
import com.aiglobal.jobfinder.viewmodel.SourceDiagnosticsViewModel
import com.aiglobal.jobfinder.viewmodel.SourceDiagnosticsViewModelFactory

private const val TRANSITION_MS = 320

/** Routes that are pushed on top of a tab rather than being tabs themselves. */
object SecondaryRoutes {
    const val CV = "cv"
    const val APPLICATION_DETAIL = "application/{applicationId}"
    const val COVER_LETTER = "coverletter/{jobId}"
    const val EXPAT = "expat/{country}"
    const val INTERVIEW = "interview/{jobId}"
    const val SCAM = "scam/{jobId}"
    const val ALERTS = "alerts"
    const val AUTO_APPLY = "autoapply"
    const val DIAGNOSTICS = "diagnostics"
    const val SHORTLIST = "shortlist"

    fun applicationDetail(applicationId: String) = "application/$applicationId"
    fun coverLetter(jobId: String) = "coverletter/$jobId"
    fun expat(country: String) = "expat/${android.net.Uri.encode(country)}"
    fun interview(jobId: String) = "interview/$jobId"
    fun scam(jobId: String) = "scam/$jobId"
}

/**
 * MIME types offered to the document picker. Some file providers report generic types
 * for Office documents, so text/plain and octet-stream are included as a fallback and
 * the real format is decided from the file extension in [com.aiglobal.jobfinder.data.model.CvFileType].
 */
private val CV_MIME_TYPES = arrayOf(
    "application/pdf",
    "application/msword",
    "application/vnd.openxmlformats-officedocument.wordprocessingml.document",
    "text/plain",
    "application/octet-stream",
)

@Composable
fun AiJobFinderRoot(
    container: AppContainer,
    appViewModel: AppViewModel,
) {
    val navController = rememberNavController()
    val context = LocalContext.current
    val backStackEntry by navController.currentBackStackEntryAsState()
    val currentRoute = backStackEntry?.destination?.route
    val current = Destination.fromRoute(currentRoute)
    // Secondary screens are pushed on top of a tab, so they hide the tab bar and offer
    // their own back affordance instead.
    val showBottomBar = Destination.entries.any { it.route == currentRoute }

    Box(modifier = Modifier.fillMaxSize().background(MaterialTheme.colorScheme.background)) {

        NavHost(
            navController = navController,
            startDestination = Destination.Home.route,
            modifier = Modifier.fillMaxSize(),
            // Forward navigation slides in from the right; going back reverses it.
            enterTransition = {
                slideInHorizontally(tween(TRANSITION_MS)) { it / 8 } + fadeIn(tween(TRANSITION_MS))
            },
            exitTransition = {
                slideOutHorizontally(tween(TRANSITION_MS)) { -it / 10 } + fadeOut(tween(TRANSITION_MS / 2))
            },
            popEnterTransition = {
                slideInHorizontally(tween(TRANSITION_MS)) { -it / 8 } + fadeIn(tween(TRANSITION_MS))
            },
            popExitTransition = {
                slideOutHorizontally(tween(TRANSITION_MS)) { it / 10 } + fadeOut(tween(TRANSITION_MS / 2))
            },
        ) {
            composable(Destination.Home.route) {
                val vm: HomeViewModel = viewModel(
                    factory = HomeViewModelFactory(container.jobRepository, container.applicationCenter),
                )
                HomeScreen(
                    viewModel = vm,
                    onOpenJobs = { navController.navigateToTab(Destination.Jobs) },
                    onOpenJob = { jobId -> navController.navigate(SecondaryRoutes.coverLetter(jobId)) },
                    onOpenLink = { url -> context.openExternally(url) },
                    onOpenShortlist = { navController.navigate(SecondaryRoutes.SHORTLIST) },
                )
            }
            composable(Destination.Jobs.route) {
                val vm: JobsViewModel = viewModel(
                    factory = JobsViewModelFactory(
                        repository = container.jobRepository,
                        expatPreferences = appViewModel.expatPreferences,
                    ),
                )
                JobsScreen(
                    viewModel = vm,
                    onOpenAi = { navController.navigateToTab(Destination.Ai) },
                    onOpenJob = { jobId -> navController.navigate(SecondaryRoutes.coverLetter(jobId)) },
                    onOpenLink = { url -> context.openExternally(url) },
                )
            }
            composable(Destination.Applications.route) {
                val vm: ApplicationsViewModel = viewModel(
                    factory = ApplicationsViewModelFactory(container.jobRepository, container.applicationCenter),
                )
                ApplicationsScreen(
                    viewModel = vm,
                    onBrowseJobs = { navController.navigateToTab(Destination.Jobs) },
                    onOpenApplication = { id -> navController.navigate(SecondaryRoutes.applicationDetail(id)) },
                )
            }
            composable(Destination.Ai.route) {
                AiScreen(
                    onBrowseJobs = { navController.navigateToTab(Destination.Jobs) },
                    onOpenCv = { navController.navigate(SecondaryRoutes.CV) },
                    onOpenInterview = { navController.navigate(SecondaryRoutes.interview("")) },
                    onOpenScamShield = { navController.navigate(SecondaryRoutes.scam("")) },
                    onOpenAlerts = { navController.navigate(SecondaryRoutes.ALERTS) },
                    onOpenAutoApply = { navController.navigate(SecondaryRoutes.AUTO_APPLY) },
                    onOpenShortlist = { navController.navigate(SecondaryRoutes.SHORTLIST) },
                )
            }
            composable(SecondaryRoutes.CV) {
                val vm: CvViewModel = viewModel(factory = CvViewModelFactory(container.cvRepository))
                val state by vm.uiState.collectAsStateWithLifecycle()
                val picker = rememberLauncherForActivityResult(
                    contract = ActivityResultContracts.OpenDocument(),
                ) { uri -> vm.onFilePicked(uri) }

                CvScreen(
                    state = state,
                    onBack = { navController.popBackStack() },
                    onPickFile = { picker.launch(CV_MIME_TYPES) },
                    onAnalyze = vm::analyze,
                    onRemove = vm::removeCv,
                    onEditField = vm::updateField,
                    onDismissMessage = vm::dismissMessage,
                )
            }
            composable(Destination.Profile.route) {
                val vm: ProfileViewModel = viewModel(factory = ProfileViewModelFactory(container.jobRepository))
                ProfileScreen(
                    viewModel = vm,
                    appViewModel = appViewModel,
                    onOpenAi = { navController.navigateToTab(Destination.Ai) },
                    onOpenExpat = { country -> navController.navigate(SecondaryRoutes.expat(country)) },
                    onOpenDiagnostics = { navController.navigate(SecondaryRoutes.DIAGNOSTICS) },
                )
            }
            composable(
                route = SecondaryRoutes.APPLICATION_DETAIL,
                arguments = listOf(navArgument("applicationId") { type = NavType.StringType }),
            ) { entry ->
                val id = entry.arguments?.getString("applicationId").orEmpty()
                val vm: ApplicationDetailViewModel = viewModel(
                    factory = ApplicationDetailViewModelFactory(id, container.applicationCenter),
                )
                ApplicationDetailScreen(
                    viewModel = vm,
                    onBack = { navController.popBackStack() },
                    onOpenCoverLetter = { jobId -> navController.navigate(SecondaryRoutes.coverLetter(jobId)) },
                    onOpenExpat = { country -> navController.navigate(SecondaryRoutes.expat(country)) },
                )
            }
            composable(
                route = SecondaryRoutes.COVER_LETTER,
                arguments = listOf(navArgument("jobId") { type = NavType.StringType }),
            ) { entry ->
                val jobId = entry.arguments?.getString("jobId").orEmpty()
                val vm: CoverLetterViewModel = viewModel(
                    factory = CoverLetterViewModelFactory(
                        jobId = jobId,
                        repository = container.jobRepository,
                        applicationCenter = container.applicationCenter,
                        cvRepository = container.cvRepository,
                    ),
                )
                CoverLetterScreen(viewModel = vm, onBack = { navController.popBackStack() })
            }
            composable(
                route = SecondaryRoutes.EXPAT,
                arguments = listOf(navArgument("country") { type = NavType.StringType }),
            ) { entry ->
                val country = entry.arguments?.getString("country").orEmpty()
                val vm: ExpatGuideViewModel = viewModel(
                    factory = ExpatGuideViewModelFactory(container.applicationCenter),
                )
                ExpatScreen(
                    viewModel = vm,
                    country = country,
                    onBack = { navController.popBackStack() },
                )
            }
            composable(
                route = SecondaryRoutes.INTERVIEW,
                arguments = listOf(navArgument("jobId") { type = NavType.StringType }),
            ) { entry ->
                val jobId = entry.arguments?.getString("jobId").orEmpty()
                val vm: InterviewViewModel = viewModel(
                    factory = InterviewViewModelFactory(
                        jobId = jobId,
                        repository = container.jobRepository,
                        cvRepository = container.cvRepository,
                        coach = container.interviewCoach,
                        store = container.interviewPrepStore,
                    ),
                )
                InterviewScreen(viewModel = vm, onBack = { navController.popBackStack() })
            }
            composable(
                route = SecondaryRoutes.SCAM,
                arguments = listOf(navArgument("jobId") { type = NavType.StringType }),
            ) { entry ->
                val jobId = entry.arguments?.getString("jobId").orEmpty()
                val vm: ScamShieldViewModel = viewModel(
                    factory = ScamShieldViewModelFactory(
                        jobId = jobId,
                        repository = container.jobRepository,
                        detector = container.scamDetector,
                    ),
                )
                ScamShieldScreen(viewModel = vm, onBack = { navController.popBackStack() })
            }
            composable(SecondaryRoutes.ALERTS) {
                val vm: JobAlertsViewModel = viewModel(
                    factory = JobAlertsViewModelFactory(container.jobAlertsCenter),
                )
                JobAlertsScreen(
                    viewModel = vm,
                    onBack = { navController.popBackStack() },
                    onOpenJob = { jobId -> navController.navigate(SecondaryRoutes.coverLetter(jobId)) },
                )
            }
            composable(SecondaryRoutes.AUTO_APPLY) {
                val vm: AutoApplyViewModel = viewModel(
                    factory = AutoApplyViewModelFactory(container.autoApplyCenter),
                )
                AutoApplyScreen(
                    viewModel = vm,
                    onBack = { navController.popBackStack() },
                    onOpenJob = { jobId -> navController.navigate(SecondaryRoutes.coverLetter(jobId)) },
                )
            }
            composable(SecondaryRoutes.DIAGNOSTICS) {
                val vm: SourceDiagnosticsViewModel = viewModel(
                    factory = SourceDiagnosticsViewModelFactory(container.jobRepository),
                )
                LiveSearchValidationScreen(
                    viewModel = vm,
                    onBack = { navController.popBackStack() },
                )
            }
            composable(SecondaryRoutes.SHORTLIST) {
                val vm: ShortlistViewModel = viewModel(
                    factory = ShortlistViewModelFactory(
                        repository = container.jobRepository,
                        cvRepository = container.cvRepository,
                        expatPreferences = appViewModel.expatPreferences,
                    ),
                )
                ShortlistScreen(
                    viewModel = vm,
                    onBack = { navController.popBackStack() },
                    onOpenJob = { jobId -> navController.navigate(SecondaryRoutes.coverLetter(jobId)) },
                    onApply = { jobId -> navController.navigate(SecondaryRoutes.coverLetter(jobId)) },
                    onOpenJobs = { navController.navigateToTab(Destination.Jobs) },
                )
            }
        }

        if (showBottomBar) {
            BottomNavBar(
                current = current,
                onSelect = { navController.navigateToTab(it) },
                modifier = Modifier.align(androidx.compose.ui.Alignment.BottomCenter),
            )
        }
    }
}

/** Switches tabs without stacking duplicates and preserves each tab's own scroll state. */
private fun androidx.navigation.NavHostController.navigateToTab(destination: Destination) {
    navigate(destination.route) {
        popUpTo(graph.findStartDestination().id) { saveState = true }
        launchSingleTop = true
        restoreState = true
    }
}

/**
 * Hands a posting URL to the system browser. Only http/https addresses are launched, so a
 * malformed link in a feed cannot open an unexpected intent; if nothing can handle it, the
 * failure is swallowed rather than crashing the app.
 */
private fun android.content.Context.openExternally(url: String) {
    if (url.isBlank()) return
    val uri = runCatching { android.net.Uri.parse(url) }.getOrNull() ?: return
    if (uri.scheme?.lowercase() !in setOf("http", "https")) return
    val intent = android.content.Intent(android.content.Intent.ACTION_VIEW, uri)
        .addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK)
    runCatching { startActivity(intent) }
}
