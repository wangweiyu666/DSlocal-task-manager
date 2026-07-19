package com.ds.localtaskmanager

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.viewModels
import androidx.compose.runtime.Composable
import com.ds.localtaskmanager.ui.DstApp
import com.ds.localtaskmanager.ui.today.TodayViewModel
import com.ds.localtaskmanager.ui.today.TodayViewModelFactory
import com.ds.localtaskmanager.ui.theme.DstTheme

class MainActivity : ComponentActivity() {
    private val todayViewModel: TodayViewModel by viewModels {
        val application = application as DstApplication
        TodayViewModelFactory(
            application.taskRepository,
            application.importService,
            application.instanceGenerationService,
        )
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent { AppContent() }
    }

    override fun onResume() {
        super.onResume()
        todayViewModel.synchronizeInstances()
    }

    @Composable
    private fun AppContent() {
        DstTheme {
            DstApp(todayViewModel = todayViewModel)
        }
    }
}
