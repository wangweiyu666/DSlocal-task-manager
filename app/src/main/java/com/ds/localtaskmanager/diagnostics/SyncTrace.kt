package com.ds.localtaskmanager.diagnostics

import android.util.Log
import com.ds.localtaskmanager.BuildConfig
import java.security.MessageDigest

/** Opt-in local diagnostic APK only; never log task text, identities or credentials. */
internal object SyncTrace {
    fun id(value: String): String = MessageDigest.getInstance("SHA-256")
        .digest(value.toByteArray(Charsets.UTF_8)).take(8).joinToString("") { "%02x".format(it) }

    fun event(stage: String, reference: String = "", detail: String = "") {
        if (!BuildConfig.CONNECTED_BUILD || !BuildConfig.SYNC_DIAGNOSTICS) return
        // Diagnostics must not change synchronization outcomes, even if Android logging fails.
        runCatching {
            require(stage.matches(Regex("[A-Z_]{1,48}")))
            require(detail.matches(Regex("[A-Za-z0-9_:.,=-]{0,120}")))
            Log.i("DST-SyncTrace", "stage=$stage ref=${if (reference.isEmpty()) "none" else id(reference)} $detail")
        }
    }
}
