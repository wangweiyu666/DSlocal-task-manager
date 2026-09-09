package com.ds.localtaskmanager.connected

import com.ds.localtaskmanager.DstApplication

/** Foreground facade over the Application-scoped synchronization engine. */
class ConnectedRuntime(private val engine: ConnectedSyncEngine) {
    constructor(application: DstApplication) : this(ConnectedSyncEngine.get(application))
    val state get() = engine.state
    suspend fun runForegroundLifecycle(): Unit = engine.runForegroundLifecycle()
    fun restore() = engine.restore()
    fun requestCode(email: String) = engine.requestCode(email)
    fun verifyCode(email: String, code: String) = engine.verifyCode(email, code)
    fun acceptInvitation(id: String) = engine.acceptInvitation(id)
    fun enterExistingSpace() = engine.enterExistingSpace()
    fun retryEntry() = engine.retryEntry()
    fun synchronize() = engine.synchronize()
    fun synchronizeIfStale() = engine.synchronizeIfStale()
    fun logout() = engine.logout()
    fun acknowledgePrivacy(version: Int) = engine.acknowledgePrivacy(version)
    fun cancelDeletion() = engine.cancelDeletion()
    fun beginSensitiveAction(action: String) = engine.beginSensitiveAction(action)
    fun requestSensitiveCode(email: String) = engine.requestSensitiveCode(email)
    fun verifySensitiveCode(email: String, code: String) = engine.verifySensitiveCode(email, code)
    fun leaveSensitiveAction() = engine.leaveSensitiveAction()
    fun markNotificationsRead(ids: List<String>) = engine.markNotificationsRead(ids)
}
