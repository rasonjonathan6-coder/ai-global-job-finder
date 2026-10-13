package com.aiglobal.jobfinder

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.runtime.getValue
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.aiglobal.jobfinder.ui.AiJobFinderRoot
import com.aiglobal.jobfinder.ui.theme.AiJobFinderTheme
import com.aiglobal.jobfinder.viewmodel.AppViewModel
import com.aiglobal.jobfinder.viewmodel.AppViewModelFactory

class MainActivity : ComponentActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        enableEdgeToEdge()
        super.onCreate(savedInstanceState)

        val container = (application as AiJobFinderApp).container
        val factory = AppViewModelFactory(container)

        setContent {
            val appViewModel: AppViewModel = viewModel(factory = factory)
            val themeMode by appViewModel.themeMode.collectAsStateWithLifecycle()

            AiJobFinderTheme(themeMode = themeMode) {
                AiJobFinderRoot(
                    container = container,
                    appViewModel = appViewModel,
                )
            }
        }
    }
}
