package net.findip.shield

/** Event-name rules shared with the web SDK (spec §4, events.json). */
internal object EventName {
    /** Mirrors VALID_EVENTS in the web SDK and ALLOWED_EVENTS at ingest. */
    val known: Set<String> = setOf(
        "page_view", "session_start", "form_view", "form_submitted",
        "signup_view", "signup_attempt", "signup_success",
        "login_view", "login_attempt", "login_success", "login_failed",
        "password_reset_view", "password_reset_attempt",
        "checkout_view", "checkout_started", "payment_attempt", "payment_failed",
        "lead_form_view", "lead_submitted", "api_request", "custom",
    )

    /** Retried once on a network failure (spec §6). */
    val sensitive: Set<String> = setOf(
        "signup_attempt", "signup_success", "login_attempt", "login_failed",
        "payment_attempt", "checkout_started", "lead_submitted", "password_reset_attempt",
    )

    private val whitespace = Regex("\\s+")

    fun normalize(name: String): String {
        val joined = name.trim().lowercase().replace(whitespace, "_")
        return if (joined in known) joined else "custom"
    }
}
