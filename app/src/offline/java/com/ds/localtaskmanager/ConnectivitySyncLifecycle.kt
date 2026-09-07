package com.ds.localtaskmanager

/** Offline builds have no network sync lifecycle. */
internal fun startConnectivitySync(application: DstApplication) = Unit

internal fun notifyConnectivityLocalMutation(application: DstApplication) = Unit
