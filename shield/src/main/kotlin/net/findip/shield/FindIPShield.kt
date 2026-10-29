package net.findip.shield

import android.content.Context
import android.os.Handler
import android.os.Looper
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch

/**
 * FindIP Shield for Android: a verdict on each sensitive action in your app.
 *
 * ```kotlin
 * FindIPShield.configure(context, "pub_…")
 * val verdict = FindIPShield.track(ShieldEvents.LOGIN_ATTEMPT)
 * // Send verdict.sessionId to your backend; it verifies before acting.
 * ```
 *
 * The verdict is advice. Your backend confirms it with
 * `POST https://shield.findip.net/v1/shield/sessions/verify` and the site's
 * secret key before it allows, challenges or blocks.
 */
public object FindIPShield {
    public const val VERSION: String = BuildConfig.SDK_VERSION
    internal const val SDK_NAME: String = "findip-shield-android"

    @Volatile
    private var client: ShieldClient? = null
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    internal val current: ShieldClient? get() = client

    /**
     * Configures the SDK. Returns at once: nothing is sent and no I/O runs until
     * the first [track]. Calling it again replaces the options; the visitor ID is kept.
     *
     * @throws ShieldException when the key does not start with `pub_`.
     */
    @JvmStatic
    @JvmOverloads
    public fun configure(context: Context, siteKey: String, options: ShieldOptions = ShieldOptions()) {
        val key = siteKey.trim()
        if (!key.startsWith("pub_")) throw ShieldException(ShieldException.Reason.INVALID_SITE_KEY)
        val app = context.applicationContext
        install(
            ShieldClient(
                siteKey = key,
                options = options,
                store = SharedPreferencesStore(app),
                transport = UrlConnectionTransport,
                appInfo = AppInfo.current(app),
                deviceProvider = { DeviceInfo.current(app) },
            ),
        )
    }

    internal fun install(newClient: ShieldClient) {
        client = newClient
    }

    /**
     * Reports one event and returns Shield's verdict. Never throws for network or
     * server problems: those return an UNKNOWN verdict, never ALLOW.
     *
     * @param event one of [ShieldEvents], or any name (sent as `custom`).
     * @param context up to 20 facts: strings, numbers, booleans. Sensitive keys and
     *   values that look like an email, a phone or a card number never leave the device.
     * @param screen optional screen name, sent as `/screen/<name>`.
     */
    @JvmSynthetic
    public suspend fun track(event: String, context: Map<String, Any?>? = null, screen: String? = null): Verdict {
        val client = client ?: throw ShieldException(ShieldException.Reason.NOT_CONFIGURED)
        return client.track(event, context, screen)
    }

    /** Receives the result of the callback form of [track], on the main thread. */
    public interface VerdictCallback {
        public fun onVerdict(verdict: Verdict)
        public fun onError(error: Throwable)
    }

    /** The callback form of [track], for Java and for code without coroutines. */
    @JvmStatic
    @JvmOverloads
    public fun track(event: String, context: Map<String, Any?>? = null, screen: String? = null, callback: VerdictCallback) {
        val main = Handler(Looper.getMainLooper())
        scope.launch {
            try {
                val verdict = track(event, context, screen)
                main.post { callback.onVerdict(verdict) }
            } catch (e: Throwable) {
                main.post { callback.onError(e) }
            }
        }
    }

    /**
     * Who the user is, for every following event until [reset]. Only SHA-256
     * hashes leave the device, plus ciphertext only your Shield dashboard can open.
     */
    @JvmStatic
    @JvmOverloads
    public fun identify(
        userId: String? = null,
        email: String? = null,
        plan: String? = null,
        salt: String? = null,
        custom: Map<String, Any?>? = null,
    ) {
        client?.identify(IdentityInput(userId, email, plan, salt, custom))
    }

    /** With `consentRequired`, nothing is sent before `setConsent(true)`. */
    @JvmStatic
    public fun setConsent(granted: Boolean) {
        client?.setConsent(granted)
    }

    /** The current Shield session ID, to send to your backend. Starts a session when none is open. Null before [configure]. */
    @JvmStatic
    public val sessionId: String?
        get() = client?.currentSessionId

    /** On logout: forgets the identity, keeps the session and visitor IDs. */
    @JvmStatic
    public fun reset() {
        client?.reset()
    }

    /** Rotates the visitor ID and ends the session. */
    @JvmStatic
    public fun resetVisitor() {
        client?.resetVisitor()
    }
}
