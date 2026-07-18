package com.ds.localtaskmanager.ui.navigation

import androidx.compose.material3.ExtendedFloatingActionButton
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.foundation.layout.padding
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.navigation.NavGraph.Companion.findStartDestination
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.currentBackStackEntryAsState
import androidx.navigation.compose.rememberNavController
import com.ds.localtaskmanager.ui.history.HistoryScreen
import com.ds.localtaskmanager.ui.profile.ProfileScreen
import com.ds.localtaskmanager.ui.today.ImportDialog
import com.ds.localtaskmanager.ui.today.TodayScreen
import com.ds.localtaskmanager.ui.today.TodayViewModel

private enum class Destination(
    val route: String,
    val label: String,
    val marker: String,
) {
    History("history", "历史", "◷"),
    Today("today", "今日", "●"),
    Profile("profile", "我的", "○"),
}

@Composable
fun DstNavigation(todayViewModel: TodayViewModel) {
    val navController = rememberNavController()
    val entry by navController.currentBackStackEntryAsState()
    val currentRoute = entry?.destination?.route
    val importState by todayViewModel.importState.collectAsStateWithLifecycle()

    Scaffold(
        bottomBar = {
            NavigationBar {
                Destination.entries.forEach { destination ->
                    NavigationBarItem(
                        selected = currentRoute == destination.route,
                        onClick = {
                            navController.navigate(destination.route) {
                                popUpTo(navController.graph.findStartDestination().id) {
                                    saveState = true
                                }
                                launchSingleTop = true
                                restoreState = true
                            }
                        },
                        icon = { Text(destination.marker) },
                        label = { Text(destination.label) },
                    )
                }
            }
        },
        floatingActionButton = {
            if (currentRoute == Destination.Today.route) {
                ExtendedFloatingActionButton(
                    onClick = todayViewModel::openImport,
                    text = { Text("导入任务") },
                    icon = { Text("+") },
                )
            }
        },
    ) { contentPadding ->
        NavHost(
            navController = navController,
            startDestination = Destination.Today.route,
            modifier = Modifier.padding(contentPadding),
        ) {
            composable(Destination.History.route) { HistoryScreen() }
            composable(Destination.Today.route) { TodayScreen(todayViewModel) }
            composable(Destination.Profile.route) { ProfileScreen() }
        }
    }

    if (importState.visible) {
        ImportDialog(
            state = importState,
            onInputChange = todayViewModel::updateImportInput,
            onPreview = todayViewModel::previewImport,
            onConfirm = todayViewModel::confirmImport,
            onDismiss = todayViewModel::closeImport,
        )
    }
}
