package net.findip.shield

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull
import org.json.JSONObject
import java.net.URI
import java.net.URLEncoder
import java.util.Date
import kotlin.random.Random

/**
 * One configured SDK instance: its state and the send logic of spec §3 and §6.
 * Every [track] runs in [scope] (Dispatchers.Default), never on the caller's thread,
 * and answers within [ShieldOptions.timeoutMillis].
 */
internal class ShieldClient(
    val siteKey: String,
    val options: ShieldOptions,
    private val store: IdentifierStore,
    private val transport: HttpTransport,
    private val appInfo: AppInfo,
    private val deviceProvider: () -> DeviceInfo,
    private val now: () -> Date = { Date() },
    private val retryDelay: suspend () -> Unit = { delay(Random.nextLong(1_600, 2_400)) },
    private val log: ShieldLog = ShieldLog(options.debug),
    private val scope: CoroutineScope = CoroutineScope(SupervisorJob() + Dispatchers.Default),
) {
    companion object {
        const val MAX_IN_FLIGHT = 50
        const val MAX_PAYLOAD_BYTES = 32_768
        const val REQUEST_TIMEOUT_MS = 5_000
        const val IDENTITY_KEY_TIMEOUT_MS = 3_000
        const val IDENTITY_KEY_TTL_MS = 300_000L
        const val COOLDOWN_MS = 60_000L

        fun errorCode(body: String): String? = try {
            JSONObject(body).optString("error").takeIf { it.isNotEmpty() }
        } catch (e: Exception) {
            null
        }

        fun verdict(body: String, fallbackSessionId: String): Verdict? {
            val json = try { JSONObject(body) } catch (e: Exception) { return null }
            val risk = json.optJSONObject("risk") ?: return null
            val status = if (risk.optString("status") == "available") RiskStatus.AVAILABLE else RiskStatus.UNKNOWN
            var recommendation = when (risk.optString("recommendation")) {
                "allow" -> Recommendation.ALLOW
                "challenge" -> Recommendation.CHALLENGE
                "block" -> Recommendation.BLOCK
                else -> Recommendation.MONITOR
            }
            // Unknown never reads as allow, whatever the body says.
            if (status == RiskStatus.UNKNOWN && recommendation == Recommendation.ALLOW) recommendation = Recommendation.MONITOR
            val reasons = risk.optJSONArray("reasons")?.let { a -> List(a.length()) { a.optString(it) } } ?: emptyList()
            val ip = json.optJSONObject("ip")
            val network = if (status == RiskStatus.AVAILABLE && ip != null) {
                Verdict.Network(
                    country = ip.optString("country"),
                    asn = ip.optInt("asn"),
                    asnName = ip.optString("asn_name"),
                    vpn = ip.optBoolean("is_vpn"),
                    proxy = ip.optBoolean("is_proxy"),
                    tor = ip.optBoolean("is_tor"),
                    relay = ip.optBoolean("is_relay"),
                    hosting = ip.optBoolean("is_hosting"),
                    malicious = ip.optBoolean("is_malicious"),
                )
            } else null
            return Verdict(
                requestId = json.optString("request_id").takeIf { it.isNotEmpty() },
                sessionId = json.optString("session_id").takeIf { it.isNotEmpty() } ?: fallbackSessionId,
                risk = Verdict.Risk(
                    status = status,
                    score = if (status == RiskStatus.AVAILABLE && risk.has("score")) risk.optInt("score") else null,
                    level = risk.optString("level", "unknown"),
                    recommendation = recommendation,
                    reasons = reasons,
                    ruleName = risk.optJSONObject("rule")?.optString("name")?.takeIf { it.isNotEmpty() },
                ),
                network = network,
                transport = Transport.Delivered,
            )
        }
    }

    private val lock = Any()
    private var consentGranted = !options.consentRequired
    private var identity: IdentityInput? = null
    private var sessionId: String? = null
    private var lastEventAt: Long? = null
    private var sessionStartPending = false
    private var backgroundedAt: Long? = null
    private var visitorId: String? = null
    private var device: DeviceInfo? = null
    private var cooldownUntil: Long? = null
    private var inFlight = 0
    private var cachedKey: Pair<IdentityKey?, Long>? = null
    private var reportedConfigurationProblem = false

    private val timeoutMillis: Long get() = options.sessionTimeoutMinutes * 60_000L

    // State

    fun setConsent(granted: Boolean) = synchronized(lock) { consentGranted = granted }
    fun identify(input: IdentityInput) = synchronized(lock) { identity = input }
    fun reset() = synchronized(lock) { identity = null }

    fun resetVisitor() = synchronized(lock) {
        store.delete(siteKey)
        visitorId = null
        sessionId = null
        lastEventAt = null
    }

    /**
     * The app went to the background. The session stays open: a user who leaves
     * to read a code from their mail is still in the same login.
     */
    fun didEnterBackground() {
        synchronized(lock) { backgroundedAt = now().time }
        log.debug { "app in the background" }
    }

    /** The app is back. The session ends if it was away longer than the timeout. */
    fun willEnterForeground() {
        val ended = synchronized(lock) {
            val expired = expiredInBackground(now().time)
            if (expired) {
                sessionId = null
                lastEventAt = null
            }
            backgroundedAt = null
            expired
        }
        log.debug { if (ended) "app in the foreground: session ended" else "app in the foreground: session kept" }
    }

    /** Call with the lock held. */
    private fun expiredInBackground(at: Long): Boolean = backgroundedAt?.let { at - it > timeoutMillis } ?: false

    /** The current session ID, starting a session when none is open. */
    val currentSessionId: String get() = synchronized(lock) { touchSession(now().time, recordActivity = false) }

    /**
     * Starts a new session when none is open, the last event is older than the timeout,
     * or the app has been in the background longer than the timeout. Call with the lock held.
     */
    private fun touchSession(at: Long, recordActivity: Boolean): String {
        val last = lastEventAt
        if (sessionId == null || expiredInBackground(at) || (last != null && at - last > timeoutMillis)) {
            sessionId = Identifiers.make("sess_")
            // Only an event sent in this session can carry its session_start.
            sessionStartPending = true
            lastEventAt = at
            // A background event opened this session: its timeout starts now.
            if (backgroundedAt != null) backgroundedAt = at
        }
        if (recordActivity || lastEventAt == null) lastEventAt = at
        return sessionId!!
    }

    /** Call with the lock held. */
    private fun currentVisitorId(): String? {
        if (options.privacyMode == PrivacyMode.STRICT) return null
        visitorId?.let { return it }
        store.load(siteKey)?.let { visitorId = it; return it }
        return Identifiers.make("vis_").also { store.save(it, siteKey); visitorId = it }
    }

    // Track

    suspend fun track(rawName: String, context: Map<String, Any?>?, screen: String?): Verdict {
        val trimmed = rawName.trim()
        if (trimmed.isEmpty()) throw ShieldException(ShieldException.Reason.EMPTY_EVENT_NAME)
        val name = EventName.normalize(trimmed)

        // The request keeps running after the deadline (HttpURLConnection cannot be
        // interrupted); the caller gets its answer on time all the same.
        val work = scope.async { trackNow(name, context, screen) }
        return withTimeoutOrNull(options.timeoutMillis) { work.await() }
            ?: Verdict.unknown(currentSessionId, Transport.Dropped(DropReason.UNREACHABLE), "shield_unreachable")
    }

    private suspend fun trackNow(name: String, context: Map<String, Any?>?, screen: String?): Verdict {
        val (granted, session, needsStart, visitor, identity) = synchronized(lock) {
            val session = touchSession(now().time, recordActivity = true)
            val needsStart = consentGranted && sessionStartPending
            if (needsStart) sessionStartPending = false
            TrackState(consentGranted, session, needsStart, currentVisitorId(), this.identity)
        }
        if (!granted) {
            log.debug { "consent not given: $name not sent" }
            return Verdict.unknown(session, Transport.Dropped(DropReason.CONSENT), "consent_required")
        }
        if (needsStart && name != "session_start") {
            // Not awaited: the event does not wait for a second round trip. Ingest
            // stores both in either order. Undispatched, so session_start goes out first.
            scope.launch(start = CoroutineStart.UNDISPATCHED) {
                send("session_start", null, null, session, visitor, identity)
            }
        }
        return send(name, context, screen, session, visitor, identity)
    }

    private data class TrackState(
        val granted: Boolean,
        val session: String,
        val needsStart: Boolean,
        val visitorId: String?,
        val identity: IdentityInput?,
    )

    private suspend fun send(
        name: String, context: Map<String, Any?>?, screen: String?,
        sessionId: String, visitorId: String?, identity: IdentityInput?,
    ): Verdict {
        val sensitive = name in EventName.sensitive
        val refused = synchronized(lock) {
            val until = cooldownUntil
            when {
                until != null && now().time < until -> DropReason.LIMITED
                inFlight >= MAX_IN_FLIGHT && !sensitive -> DropReason.LIMITED
                else -> { inFlight += 1; null }
            }
        }
        if (refused != null) return Verdict.unknown(sessionId, Transport.Dropped(refused), "shield_rate_limited")
        try {
            val builder = PayloadBuilder(siteKey, options.privacyMode, options.collectDeviceSignals, appInfo, cachedDevice())
            val payload = builder.build(
                PayloadBuilder.Event(name, now(), screen, sessionId, visitorId, customerContext(identity, context), true),
            )
            var retriedNetwork = false
            var retriedSize = false
            while (true) {
                var body = payload.toString().toByteArray(Charsets.UTF_8)
                if (body.size > MAX_PAYLOAD_BYTES && !retriedSize && dropCustom(payload)) {
                    retriedSize = true
                    body = payload.toString().toByteArray(Charsets.UTF_8)
                }
                val response = try {
                    transport.send(HttpRequest("POST", options.endpoint, body, REQUEST_TIMEOUT_MS))
                } catch (e: Exception) {
                    if (sensitive && !retriedNetwork) {
                        retriedNetwork = true
                        retryDelay()
                        continue
                    }
                    log.debug { "$name not delivered: ${e.javaClass.simpleName}" }
                    return Verdict.unknown(sessionId, Transport.Dropped(DropReason.UNREACHABLE), "shield_unreachable")
                }
                val code = errorCode(response.body)
                when {
                    response.status == 200 -> return verdict(response.body, sessionId)
                        ?: Verdict.unknown(sessionId, Transport.Dropped(DropReason.INVALID), "invalid_response")
                    response.status == 413 && !retriedSize -> {
                        retriedSize = true
                        dropCustom(payload)
                    }
                    response.status == 413 ->
                        return Verdict.unknown(sessionId, Transport.Dropped(DropReason.TOO_LARGE), "payload_too_large")
                    response.status == 429 -> {
                        synchronized(lock) { cooldownUntil = now().time + COOLDOWN_MS }
                        return Verdict.unknown(sessionId, Transport.Dropped(DropReason.LIMITED), code ?: "rate_limited")
                    }
                    response.status == 400 ->
                        return Verdict.unknown(sessionId, Transport.Dropped(DropReason.INVALID), code ?: "invalid_json")
                    response.status == 401 || response.status == 403 -> {
                        reportConfigurationProblem(code)
                        return Verdict.unknown(sessionId, Transport.Dropped(DropReason.CONFIGURATION), code ?: "forbidden")
                    }
                    sensitive && !retriedNetwork -> {
                        retriedNetwork = true
                        retryDelay()
                    }
                    else -> return Verdict.unknown(sessionId, Transport.Dropped(DropReason.UNREACHABLE), "shield_unreachable")
                }
            }
        } finally {
            synchronized(lock) { inFlight -= 1 }
        }
    }

    /** Removes the custom context; false when there was none to remove. */
    private fun dropCustom(payload: JSONObject): Boolean {
        val context = payload.optJSONObject("customer_context") ?: return false
        return context.remove("custom") != null
    }

    private fun reportConfigurationProblem(code: String?) {
        val first = synchronized(lock) { (!reportedConfigurationProblem).also { reportedConfigurationProblem = true } }
        if (!first) return
        val hint = when (code) {
            "app_mismatch" -> "Shield has no app registered as ${appInfo.bundleId}. Add it under Settings → Apps & keys."
            "integration_not_allowed" -> "This site key belongs to a website site. Create a mobile app site for the app."
            else -> "Check the site key and that the site is enabled."
        }
        log.problem("FindIP Shield refused events (${code ?: "forbidden"}). $hint")
    }

    private fun cachedDevice(): DeviceInfo {
        synchronized(lock) { device?.let { return it } }
        val fetched = deviceProvider()
        synchronized(lock) { device = fetched }
        return fetched
    }

    // Identity

    private suspend fun customerContext(identity: IdentityInput?, context: Map<String, Any?>?): Map<String, Any> {
        val result = LinkedHashMap<String, Any>()
        val custom = LinkedHashMap<String, Any?>()
        if (identity != null) {
            result.putAll(Identity.context(identity))
            identity.custom?.let { custom.putAll(it) }
            val values = Identity.encryptable(identity)
            if (values.isNotEmpty()) {
                identityKey()?.let { key ->
                    for ((field, value) in values) key.encrypt(value)?.let { result[field] = it }
                }
            }
        }
        context?.let { custom.putAll(it) }
        val sanitized = ContextSanitizer.sanitize(custom)
        if (sanitized.isNotEmpty()) result["custom"] = sanitized
        return result
    }

    val identityKeyUrl: String?
        get() {
            val uri = try { URI(options.endpoint) } catch (e: Exception) { return null }
            val path = uri.path ?: return null
            if (!path.endsWith("/track")) return null
            val keyPath = path.removeSuffix("/track") + "/identity-key"
            val port = if (uri.port == -1) "" else ":${uri.port}"
            return "${uri.scheme}://${uri.host}$port$keyPath?site_key=${URLEncoder.encode(siteKey, "UTF-8")}"
        }

    /** The site's identity key, cached 5 minutes; null (hash only) when the site has none. */
    private suspend fun identityKey(): IdentityKey? {
        synchronized(lock) {
            cachedKey?.let { (key, at) -> if (now().time - at < IDENTITY_KEY_TTL_MS) return key }
        }
        val url = identityKeyUrl ?: return null
        val key = try {
            val response = transport.send(HttpRequest("GET", url, null, IDENTITY_KEY_TIMEOUT_MS))
            if (response.status == 200) IdentityKey.parse(JSONObject(response.body).optString("key")) else null
        } catch (e: Exception) {
            null
        }
        synchronized(lock) { cachedKey = key to now().time }
        return key
    }
}
