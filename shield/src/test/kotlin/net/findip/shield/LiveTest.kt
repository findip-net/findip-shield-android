package net.findip.shield

import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assume.assumeTrue
import org.junit.Test
import java.io.File

/**
 * Against a real Shield ingest, only when pointed at one (otherwise skipped):
 *   SHIELD_LIVE_ENDPOINT   e.g. http://localhost:3998/v1/shield/track
 *   SHIELD_LIVE_SITE_KEY   a mobile app site's public key
 *   SHIELD_LIVE_BUNDLE_ID  an Android app id registered on that site
 *   SHIELD_LIVE_OUT        optional file that receives the session ID
 *
 * Uses the real HttpURLConnection transport, so the request bytes are the
 * ones an app sends. The Shield repository's live-check workflow then reads
 * the session back from the dashboard (identity decrypted) and verifies it.
 */
class LiveTest {
    private val env = System.getenv()

    @Test
    fun sendsAnIdentifiedLoginAndGetsAVerdict() = runBlocking {
        val endpoint = env["SHIELD_LIVE_ENDPOINT"]
        assumeTrue("SHIELD_LIVE_ENDPOINT not set: live test skipped", !endpoint.isNullOrEmpty())
        val siteKey = env.getValue("SHIELD_LIVE_SITE_KEY")
        val bundleId = env.getValue("SHIELD_LIVE_BUNDLE_ID")
        val device = DeviceInfo(
            platform = "android", osVersion = "14", modelFamily = "Pixel", locale = "en-GB", timezone = "Europe/London",
            screen = DeviceInfo.Screen(412, 915, 2.625), signals = DeviceInfo.Signals(emulator = false, rooted = false, debugger = false),
        )

        val client = ShieldClient(
            siteKey = siteKey,
            options = ShieldOptions(endpoint = endpoint!!, debug = true, timeoutMillis = 20_000),
            store = MemoryStore(),
            transport = UrlConnectionTransport,
            appInfo = AppInfo(bundleId, "1.0.0", "1"),
            deviceProvider = { device },
        )
        client.identify(IdentityInput(userId = "live-android-1", email = "Live.Android@Example.com", plan = "trial"))
        val verdict = client.track(ShieldEvents.LOGIN_ATTEMPT, mapOf("method" to "password"), "login")
        assertEquals(Transport.Delivered, verdict.transport)
        assertEquals(RiskStatus.AVAILABLE, verdict.risk.status)
        env["SHIELD_LIVE_OUT"]?.let { File(it).writeText(verdict.sessionId) }

        // An unregistered id is refused and named.
        val imposter = ShieldClient(
            siteKey = siteKey,
            options = ShieldOptions(endpoint = endpoint, timeoutMillis = 20_000),
            store = MemoryStore(),
            transport = UrlConnectionTransport,
            appInfo = AppInfo("com.imposter.android"),
            deviceProvider = { device },
            retryDelay = {},
        )
        val refused = imposter.track(ShieldEvents.LOGIN_ATTEMPT, null, null)
        assertEquals(Transport.Dropped(DropReason.CONFIGURATION), refused.transport)
        assertEquals(listOf("app_mismatch"), refused.risk.reasons)
    }
}
