package net.findip.shield.demo

import android.app.Application
import android.util.Log
import net.findip.shield.FindIPShield
import net.findip.shield.ShieldException
import net.findip.shield.ShieldOptions

/** Configures Shield once, at launch. Nothing is sent until the first track. */
class DemoApp : Application() {
    override fun onCreate() {
        super.onCreate()
        if (BuildConfig.SHIELD_SITE_KEY.isBlank()) return
        try {
            FindIPShield.configure(this, BuildConfig.SHIELD_SITE_KEY, ShieldOptions(endpoint = BuildConfig.SHIELD_ENDPOINT, debug = BuildConfig.DEBUG))
            configured = true
        } catch (e: ShieldException) {
            Log.w("ShieldDemo", "Shield is off: ${e.message}")
        }
    }

    companion object {
        /** False without a site key: the screen says so, and the login still works. */
        var configured: Boolean = false
            private set
    }
}
