package net.findip.shield

import org.json.JSONObject
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.TimeZone

/** Builds the JSON body of one event (spec §2, payloads.json). */
internal class PayloadBuilder(
    private val siteKey: String,
    private val privacyMode: PrivacyMode,
    private val collectDeviceSignals: Boolean,
    private val app: AppInfo,
    private val device: DeviceInfo,
    private val sdkName: String = FindIPShield.SDK_NAME,
    private val sdkVersion: String = FindIPShield.VERSION,
    private val integration: String = "android",
) {
    data class Event(
        val name: String,
        val timestamp: Date?,
        val screen: String?,
        val sessionId: String,
        val visitorId: String?,
        val customerContext: Map<String, Any>,
        val consentGranted: Boolean,
    )

    fun build(event: Event): JSONObject {
        val strict = privacyMode == PrivacyMode.STRICT
        val payload = JSONObject()
        payload.put("site_key", siteKey)
        payload.put("sdk", JSONObject().put("name", sdkName).put("version", sdkVersion).put("integration", integration))

        val appBlock = JSONObject().put("bundle_id", app.bundleId)
        app.version?.takeIf { it.isNotEmpty() }?.let { appBlock.put("version", it) }
        app.build?.takeIf { it.isNotEmpty() }?.let { appBlock.put("build", it) }
        payload.put("app", appBlock)

        val eventBlock = JSONObject().put("name", event.name).put("source", "manual")
        event.timestamp?.let { eventBlock.put("timestamp", timestamp(it)) }
        payload.put("event", eventBlock)

        event.screen?.let { screenPath(it) }?.let { payload.put("page", JSONObject().put("path", it)) }

        val session = JSONObject().put("session_id", event.sessionId).put("storage", "app")
        if (!strict && event.visitorId != null) {
            session.put("visitor_id", event.visitorId).put("visitor_storage", "app")
        }
        payload.put("session", session)

        val deviceBlock = JSONObject().put("platform", device.platform)
        device.osVersion?.let { deviceBlock.put("os_version", it) }
        if (!strict) device.modelFamily?.let { deviceBlock.put("model_family", it) }
        device.locale?.let { deviceBlock.put("locale", it) }
        device.timezone?.let { deviceBlock.put("timezone", it) }
        if (!strict) device.screen?.let {
            deviceBlock.put("screen", JSONObject().put("width", it.width).put("height", it.height).put("scale", it.scale))
        }
        if (!strict && collectDeviceSignals) device.signals?.let {
            deviceBlock.put(
                "signals",
                JSONObject()
                    .put("emulator", it.emulator ?: JSONObject.NULL)
                    .put("rooted", it.rooted ?: JSONObject.NULL)
                    .put("debugger", it.debugger ?: JSONObject.NULL),
            )
        }
        payload.put("device", deviceBlock)

        if (event.customerContext.isNotEmpty()) payload.put("customer_context", toJson(event.customerContext))
        payload.put(
            "privacy",
            JSONObject().put("mode", privacyMode.wire).put("consent", JSONObject().put("granted", event.consentGranted).put("source", "app")),
        )
        return payload
    }

    companion object {
        private val screenName = Regex("^[a-z0-9_-]{1,64}$")

        /** "Login" → "/screen/login". Names Shield would not store are left out. */
        fun screenPath(name: String): String? {
            val lower = name.trim().lowercase().replace(' ', '_')
            return if (screenName.matches(lower)) "/screen/$lower" else null
        }

        /** ISO 8601, UTC, milliseconds. */
        fun timestamp(date: Date): String =
            SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss.SSS'Z'", Locale.US).apply { timeZone = TimeZone.getTimeZone("UTC") }.format(date)

        @Suppress("UNCHECKED_CAST")
        fun toJson(map: Map<String, Any>): JSONObject {
            val json = JSONObject()
            for ((key, value) in map) {
                json.put(key, if (value is Map<*, *>) toJson(value as Map<String, Any>) else value)
            }
            return json
        }
    }
}
