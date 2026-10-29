package net.findip.shield

import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Test

/**
 * The shared contract (findip-sdk docs/shield/mobile-sdk-fixtures, copied into
 * test resources). The same files run against ingest, the web SDK and the iOS SDK.
 */
class FixtureTest {
    private fun fixture(name: String): JSONObject =
        JSONObject(javaClass.getResource("/fixtures/$name.json")!!.readText())

    private fun cases(name: String): List<JSONObject> {
        val array = fixture(name).getJSONArray("cases")
        return List(array.length()) { array.getJSONObject(it) }
    }

    @Test
    fun eventNames() {
        for (c in cases("events")) {
            assertEquals(c.getString("input"), c.getString("expected"), EventName.normalize(c.getString("input")))
        }
    }

    @Test
    fun contextFilter() {
        for (c in cases("context")) {
            val produced = ContextSanitizer.sanitize(plain(c.getJSONObject("input")) as Map<String, Any?>)
            assertEquals(c.getString("name"), plain(c.getJSONObject("expected")), normalize(produced))
        }
    }

    @Test
    fun identityHashes() {
        for (c in cases("identity")) {
            val input = c.getJSONObject("input")
            val identity = IdentityInput(
                userId = if (input.has("userId")) input.get("userId").toString() else null,
                email = input.optString("email").takeIf { input.has("email") },
                plan = input.optString("plan").takeIf { input.has("plan") },
                salt = input.optString("salt").takeIf { input.has("salt") },
            )
            assertEquals(c.getString("name"), plain(c.getJSONObject("expected")), normalize(Identity.context(identity)))
        }
    }

    /** The cases an SDK builds itself: the same inputs must give the same body. */
    @Test
    fun payloadsTheSdkBuilds() {
        val built = cases("payloads").filter {
            it.optBoolean("sdk_builds") && it.getJSONObject("request").getJSONObject("sdk").getString("integration") == "android"
        }
        assertFalse("the fixture has Android cases to build", built.isEmpty())
        for (c in built) {
            val request = c.getJSONObject("request")
            val sdk = request.getJSONObject("sdk")
            val app = request.getJSONObject("app")
            val session = request.getJSONObject("session")
            val device = request.getJSONObject("device")
            val screen = device.optJSONObject("screen")
            val signals = device.optJSONObject("signals")
            fun nullable(o: JSONObject, k: String): Boolean? = if (o.isNull(k)) null else o.getBoolean(k)
            val builder = PayloadBuilder(
                siteKey = request.getString("site_key"),
                privacyMode = PrivacyMode.values().first { it.wire == request.getJSONObject("privacy").getString("mode") },
                collectDeviceSignals = signals != null,
                app = AppInfo(app.getString("bundle_id"), app.optString("version").takeIf { app.has("version") }, app.optString("build").takeIf { app.has("build") }),
                device = DeviceInfo(
                    platform = device.getString("platform"),
                    osVersion = device.optString("os_version").takeIf { device.has("os_version") },
                    modelFamily = device.optString("model_family").takeIf { device.has("model_family") },
                    locale = device.optString("locale").takeIf { device.has("locale") },
                    timezone = device.optString("timezone").takeIf { device.has("timezone") },
                    screen = screen?.let { DeviceInfo.Screen(it.getInt("width"), it.getInt("height"), it.getDouble("scale")) },
                    signals = signals?.let { DeviceInfo.Signals(nullable(it, "emulator"), nullable(it, "rooted"), nullable(it, "debugger")) },
                ),
                sdkName = sdk.getString("name"),
                sdkVersion = sdk.getString("version"),
                integration = "android",
            )
            val path = request.optJSONObject("page")?.optString("path")
            @Suppress("UNCHECKED_CAST")
            val body = builder.build(
                PayloadBuilder.Event(
                    name = request.getJSONObject("event").getString("name"),
                    timestamp = null,
                    screen = path?.removePrefix("/screen/"),
                    sessionId = session.getString("session_id"),
                    visitorId = session.optString("visitor_id").takeIf { session.has("visitor_id") },
                    customerContext = (plain(request.optJSONObject("customer_context") ?: JSONObject()) as Map<String, Any>),
                    consentGranted = true,
                ),
            )
            assertEquals(c.getString("name"), plain(request), plain(body))
        }
    }

    companion object {
        /** JSON as plain Kotlin values, every number a Double, so 3 and 3.0 compare equal. */
        fun plain(value: Any?): Any? = when (value) {
            is JSONObject -> value.keys().asSequence().associateWith { plain(value.get(it)) }
            is JSONArray -> List(value.length()) { plain(value.get(it)) }
            JSONObject.NULL -> null
            is Number -> value.toDouble()
            else -> value
        }

        fun normalize(value: Any?): Any? = when (value) {
            is Map<*, *> -> value.entries.associate { (k, v) -> k as String to normalize(v) }
            is Number -> value.toDouble()
            else -> value
        }
    }
}
