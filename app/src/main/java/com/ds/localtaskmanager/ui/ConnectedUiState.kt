package com.ds.localtaskmanager.ui

data class ConnectedUiState(
    val spaceName: String,
    val syncStatus: String,
    val syncing: Boolean,
    val notifications: List<AppNotificationUi>,
    val serviceMode: String = "NORMAL",
)

data class AppNotificationUi(
    val groupKey: String,
    val title: String,
    val createdAt: String,
    val notificationIds: List<String>,
    val unread: Boolean,
)
