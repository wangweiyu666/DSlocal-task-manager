package com.ds.localtaskmanager

import com.ds.localtaskmanager.connected.ConnectedSyncEngine

/** Starts connected recovery once per process and records committed local mutations asynchronously. */
internal fun startConnectivitySync(application: DstApplication) {
    ConnectedSyncEngine.get(application).recoverAfterStartup()
}

internal fun notifyConnectivityLocalMutation(application: DstApplication) {
    ConnectedSyncEngine.get(application).onLocalMutationCommitted()
}
