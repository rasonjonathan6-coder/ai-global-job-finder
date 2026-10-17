package com.aiglobal.jobfinder

import android.content.Intent
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.aiglobal.jobfinder.data.notifications.JobNotificationCenter
import com.aiglobal.jobfinder.ui.AiJobFinderRoot
import com.aiglobal.jobfinder.ui.theme.AiJobFinderTheme
import com.aiglobal.jobfinder.viewmodel.AppViewModel
import com.aiglobal.jobfinder.viewmodel.AppViewModelFactory

class MainActivity : ComponentActivity() {

    /**
     * The posting a notification asked to open, held as a one-shot signal. It is set from the
     * launch intent and from any later tap, and cleared once the UI has navigated to it, so a
     * configuration change does not re-open the same job.
     */
    private val pendingJobId = mutableStateOf<String?>(null)

    override fun onCreate(savedInstanceState: Bundle?) {
        enableEdgeToEdge()
        super.onCreate(savedInstanceState)

        pendingJobId.value = intent?.getStringExtra(JobNotificationCenter.EXTRA_JOB_ID)

        val container = (application as AiJobFinderApp).container
        val factory = AppViewModelFactory(container)

        setContent {
            val appViewModel: AppViewModel = viewModel(factory = factory)
            val themeMode by appViewModel.themeMode.collectAsStateWithLifecycle()

            AiJobFinderTheme(themeMode = themeMode) {
                AiJobFinderRoot(
                    container = container,
                    appViewModel = appViewModel,
                    pendingJobId = pendingJobId.value,
                    onPendingJobConsumed = { pendingJobId.value = null },
                )
            }
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        // A notification tapped while the app is already open must still open the posting.
        intent.getStringExtra(JobNotificationCenter.EXTRA_JOB_ID)?.let { pendingJobId.value = it }
    }
}
