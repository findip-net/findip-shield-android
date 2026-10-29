package net.findip.shield

import android.util.Log

/** Debug logging. Never logs identity values, a full site key or a response body. */
internal open class ShieldLog(private val enabled: Boolean) {
    open fun debug(message: () -> String) {
        if (enabled) Log.d(TAG, message())
    }

    /** Configuration problems are logged once whatever `debug` says: they mean every event is lost. */
    open fun problem(message: String) {
        Log.w(TAG, message)
    }

    companion object {
        const val TAG = "FindIPShield"
    }
}
