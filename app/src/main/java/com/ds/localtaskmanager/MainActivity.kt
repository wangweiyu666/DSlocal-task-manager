package com.ds.localtaskmanager

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.runtime.Composable
import androidx.lifecycle.viewmodel.compose.viewModel
import com.ds.localtaskmanager.ui.DstApp
import com.ds.localtaskmanager.ui.TodayViewModel
import com.ds.localtaskmanager.ui.TodayViewModelFactory
import com.ds.localtaskmanager.ui.theme.DstTheme

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent { AppContent() }
    }

    @Composable
    private fun AppContent() {
        val application = application as DstApplication
        val todayViewModel: TodayViewModel = viewModel(
            factory = TodayViewModelFactory(application.taskRepository, application.importService),
        )
        DstTheme {
            DstApp(todayViewModel = todayViewModel)
        }
    }
}
