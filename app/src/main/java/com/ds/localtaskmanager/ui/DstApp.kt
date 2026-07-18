package com.ds.localtaskmanager.ui

import androidx.compose.runtime.Composable
import com.ds.localtaskmanager.ui.navigation.DstNavigation
import com.ds.localtaskmanager.ui.today.TodayViewModel

@Composable
fun DstApp(todayViewModel: TodayViewModel) {
    DstNavigation(todayViewModel)
}
