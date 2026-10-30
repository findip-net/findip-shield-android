package net.findip.shield

/** How much the SDK sends, as on the web SDK. [STRICT] sends no visitor ID and no screen size, device signals or device family. */
public enum class PrivacyMode(internal val wire: String) {
    STRICT("strict"),
    BALANCED("balanced"),
    ADVANCED("advanced"),
}

/** Options for [FindIPShield.configure]. Every option has the specification's default. */
public data class ShieldOptions @JvmOverloads constructor(
    val privacyMode: PrivacyMode = PrivacyMode.BALANCED,
    /** When true, nothing is sent until `FindIPShield.setConsent(true)`. */
    val consentRequired: Boolean = false,
    /** The track endpoint. The identity-key endpoint is derived from it. */
    val endpoint: String = DEFAULT_ENDPOINT,
    /** A session ends after this many minutes without an event or in the background. A shorter trip to the background keeps it. */
    val sessionTimeoutMinutes: Int = 30,
    /** Sends the emulator / root / debugger heuristics. Always off in [PrivacyMode.STRICT]. */
    val collectDeviceSignals: Boolean = true,
    /** Logs SDK activity with tag `FindIPShield`. */
    val debug: Boolean = false,
    /** The longest [FindIPShield.track] waits for Shield; after it, an UNKNOWN verdict (MONITOR). */
    val timeoutMillis: Long = DEFAULT_TIMEOUT_MILLIS,
) {
    public companion object {
        public const val DEFAULT_ENDPOINT: String = "https://shield.findip.net/v1/shield/track"
        public const val DEFAULT_TIMEOUT_MILLIS: Long = 5_000
    }
}

/** What Shield recommends. Advice for the app; the backend decides after verifying the session. */
public enum class Recommendation { ALLOW, MONITOR, CHALLENGE, BLOCK }

/** AVAILABLE: Shield scored the request. UNKNOWN: it could not; never read it as safe. */
public enum class RiskStatus { AVAILABLE, UNKNOWN }

/** Why an event did not reach Shield, or was refused by it. */
public enum class DropReason {
    /** Consent is required and was not given. */
    CONSENT,
    /** Shield could not read the request. */
    INVALID,
    /** The site key, the site or the app registration is wrong. */
    CONFIGURATION,
    /** The request was too large even without the custom context. */
    TOO_LARGE,
    /** Rate limit, daily quota, capacity, or too many requests in flight. */
    LIMITED,
    /** No network, a timeout or a server error. */
    UNREACHABLE,
}

/** Whether the event was delivered. */
public sealed class Transport {
    public object Delivered : Transport() {
        override fun toString(): String = "Delivered"
    }

    public data class Dropped(val reason: DropReason) : Transport()
}

/** Shield's verdict for one event. */
public data class Verdict(
    val requestId: String?,
    /** Send this to your backend with the protected request. */
    val sessionId: String,
    val risk: Risk,
    /** Nil when the risk is unknown: flags would otherwise claim knowledge. */
    val network: Network?,
    val transport: Transport,
) {
    public data class Risk(
        val status: RiskStatus,
        /** 0 to 100; null when [status] is UNKNOWN. */
        val score: Int?,
        /** `low`, `medium`, `high`, `critical` or `unknown`. */
        val level: String,
        val recommendation: Recommendation,
        val reasons: List<String>,
        /** Set when one of the site's custom rules decided. */
        val ruleName: String?,
    )

    public data class Network(
        val country: String,
        val asn: Int,
        val asnName: String,
        val vpn: Boolean,
        val proxy: Boolean,
        val tor: Boolean,
        val relay: Boolean,
        val hosting: Boolean,
        val malicious: Boolean,
    )

    internal companion object {
        /** The verdict returned when Shield gave no answer: unknown, never safe. */
        fun unknown(sessionId: String, transport: Transport, reason: String): Verdict = Verdict(
            requestId = null,
            sessionId = sessionId,
            risk = Risk(RiskStatus.UNKNOWN, null, "unknown", Recommendation.MONITOR, listOf(reason), null),
            network = null,
            transport = transport,
        )
    }
}

/** Programmer errors. Network and server problems never throw; they return an unknown verdict. */
public class ShieldException(public val reason: Reason) : IllegalStateException(reason.message) {
    public enum class Reason(internal val message: String) {
        NOT_CONFIGURED("FindIPShield.configure was not called"),
        INVALID_SITE_KEY("the site key must start with pub_"),
        EMPTY_EVENT_NAME("the event name is empty"),
    }
}

/** The event names Shield knows. Any other name is sent as `custom`. */
public object ShieldEvents {
    public const val PAGE_VIEW: String = "page_view"
    public const val SESSION_START: String = "session_start"
    public const val SIGNUP_VIEW: String = "signup_view"
    public const val SIGNUP_ATTEMPT: String = "signup_attempt"
    public const val SIGNUP_SUCCESS: String = "signup_success"
    public const val LOGIN_VIEW: String = "login_view"
    public const val LOGIN_ATTEMPT: String = "login_attempt"
    public const val LOGIN_SUCCESS: String = "login_success"
    public const val LOGIN_FAILED: String = "login_failed"
    public const val PASSWORD_RESET_VIEW: String = "password_reset_view"
    public const val PASSWORD_RESET_ATTEMPT: String = "password_reset_attempt"
    public const val CHECKOUT_VIEW: String = "checkout_view"
    public const val CHECKOUT_STARTED: String = "checkout_started"
    public const val PAYMENT_ATTEMPT: String = "payment_attempt"
    public const val PAYMENT_FAILED: String = "payment_failed"
    public const val LEAD_SUBMITTED: String = "lead_submitted"
    public const val API_REQUEST: String = "api_request"
    public const val CUSTOM: String = "custom"
}
