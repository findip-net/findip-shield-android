package net.findip.shield

import kotlinx.coroutines.test.runTest
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.security.KeyPairGenerator
import java.security.PrivateKey
import java.util.Base64
import java.util.Date
import javax.crypto.Cipher

/** Answers requests from a lambda and records them. */
class StubTransport(var handler: (HttpRequest) -> Pair<Int, Any>) : HttpTransport {
    val requests = mutableListOf<HttpRequest>()

    override suspend fun send(request: HttpRequest): HttpResponse {
        synchronized(requests) { requests.add(request) }
        val (status, body) = handler(request)
        return HttpResponse(status, body.toString())
    }

    val tracked: List<JSONObject> get() = synchronized(requests) { requests.filter { it.method == "POST" }.map { JSONObject(String(it.body!!)) } }
    val trackedNames: List<String> get() = tracked.map { it.getJSONObject("event").getString("name") }
}

class TestClock {
    var now = Date(1_800_000_000_000)
    fun advance(seconds: Long) { now = Date(now.time + seconds * 1000) }
}

class ShieldClientTest {
    private val allow = JSONObject(
        """{"request_id":"req_1","session_id":"ignored","risk":{"status":"available","score":12,"level":"low",
        "recommendation":"allow","reasons":["login_attempt"]},"ip":{"country":"GB","asn":2856,"asn_name":"BT",
        "is_vpn":false,"is_proxy":false,"is_tor":false,"is_relay":false,"is_hosting":false,"is_malicious":false}}""",
    )

    private val device = DeviceInfo(
        "android", "14", "Pixel", "en-GB", "Europe/London",
        DeviceInfo.Screen(412, 915, 2.625), DeviceInfo.Signals(emulator = false, rooted = false, debugger = false),
    )

    private val quietLog = object : ShieldLog(false) {
        override fun problem(message: String) = Unit
    }

    private fun client(
        transport: StubTransport,
        options: ShieldOptions = ShieldOptions(),
        store: IdentifierStore = MemoryStore(),
        clock: TestClock = TestClock(),
    ) = ShieldClient(
        siteKey = "pub_test0000000000", options = options, store = store, transport = transport,
        appInfo = AppInfo("com.example.app", "4.2.1", "1021"), deviceProvider = { device },
        now = { clock.now }, retryDelay = {}, log = quietLog,
    )

    @Test
    fun deliveredVerdictAndSessionStartOncePerSession() = runTest {
        val transport = StubTransport { 200 to allow }
        val clock = TestClock()
        val client = client(transport, clock = clock)
        val verdict = client.track("login_attempt", null, "login")
        assertEquals(Transport.Delivered, verdict.transport)
        assertEquals(Recommendation.ALLOW, verdict.risk.recommendation)
        assertEquals(12, verdict.risk.score)
        assertEquals("GB", verdict.network?.country)
        client.track("login_failed", null, null)
        assertEquals(listOf("session_start", "login_attempt", "login_failed"), transport.trackedNames)

        clock.advance(31 * 60)
        client.track("login_attempt", null, null)
        assertEquals(listOf("session_start", "login_attempt"), transport.trackedNames.takeLast(2))
        val sessions = transport.tracked.map { it.getJSONObject("session").getString("session_id") }.toSet()
        assertEquals("a new session after the timeout", 2, sessions.size)
    }

    @Test
    fun idsHaveTheSpecFormatAndTheVisitorPersists() = runTest {
        val store = MemoryStore()
        val transport = StubTransport { 200 to allow }
        client(transport, store = store).track("login_attempt", null, null)
        client(transport, store = store).track("login_attempt", null, null)
        val pattern = Regex("^(sess|vis)_[A-Za-z0-9_-]{22}$")
        val sessions = transport.tracked.map { it.getJSONObject("session") }
        for (s in sessions) {
            assertTrue(pattern.matches(s.getString("session_id")))
            assertTrue(pattern.matches(s.getString("visitor_id")))
        }
        assertEquals("the visitor ID is kept across launches", 1, sessions.map { it.getString("visitor_id") }.toSet().size)
    }

    @Test
    fun strictModeSendsNoVisitorScreenSignalsOrFamily() = runTest {
        val transport = StubTransport { 200 to allow }
        client(transport, ShieldOptions(privacyMode = PrivacyMode.STRICT)).track("signup_attempt", null, null)
        val body = transport.tracked.last()
        assertFalse(body.getJSONObject("session").has("visitor_id"))
        val device = body.getJSONObject("device")
        assertFalse(device.has("screen"))
        assertFalse(device.has("signals"))
        assertFalse(device.has("model_family"))
        assertEquals("14", device.getString("os_version"))
    }

    @Test
    fun consentRequiredSendsNothingUntilGranted() = runTest {
        val transport = StubTransport { 200 to allow }
        val client = client(transport, ShieldOptions(consentRequired = true))
        val refused = client.track("login_attempt", null, null)
        assertEquals(Transport.Dropped(DropReason.CONSENT), refused.transport)
        assertEquals(Recommendation.MONITOR, refused.risk.recommendation)
        assertTrue(transport.requests.isEmpty())
        client.setConsent(true)
        assertEquals(Transport.Delivered, client.track("login_attempt", null, null).transport)
    }

    @Test
    fun refusalsNeverReadAsAllow() = runTest {
        val transport = StubTransport { 403 to JSONObject().put("error", "app_mismatch").put("detected_app", "com.example.app") }
        val verdict = client(transport).track("login_attempt", null, null)
        assertEquals(Transport.Dropped(DropReason.CONFIGURATION), verdict.transport)
        assertEquals(RiskStatus.UNKNOWN, verdict.risk.status)
        assertEquals(Recommendation.MONITOR, verdict.risk.recommendation)
        assertEquals(listOf("app_mismatch"), verdict.risk.reasons)
    }

    @Test
    fun anUnknownBodyNeverReadsAsAllow() {
        val verdict = ShieldClient.verdict("""{"risk":{"status":"unknown","level":"unknown","recommendation":"allow","reasons":[]}}""", "sess_x")
        assertEquals(Recommendation.MONITOR, verdict?.risk?.recommendation)
        assertNull(verdict?.network)
    }

    @Test
    fun quotaStartsACooldown() = runTest {
        val transport = StubTransport { 429 to JSONObject().put("error", "daily_quota_exceeded") }
        val clock = TestClock()
        val client = client(transport, clock = clock)
        assertEquals(Transport.Dropped(DropReason.LIMITED), client.track("login_attempt", null, null).transport)
        val sentBefore = transport.requests.size
        client.track("login_attempt", null, null)
        assertEquals("nothing is sent during the cooldown", sentBefore, transport.requests.size)
        clock.advance(61)
        transport.handler = { 200 to allow }
        assertEquals(Transport.Delivered, client.track("login_attempt", null, null).transport)
    }

    @Test
    fun networkFailureRetriesSensitiveEventsOnce() = runTest {
        val transport = StubTransport { throw java.io.IOException("offline") }
        val client = client(transport)
        val verdict = client.track("login_attempt", null, null)
        assertEquals(Transport.Dropped(DropReason.UNREACHABLE), verdict.transport)
        assertEquals(listOf("shield_unreachable"), verdict.risk.reasons)
        assertEquals(listOf("session_start", "login_attempt", "login_attempt"), transport.trackedNames)
        client.track("page_view", null, null)
        assertEquals("not retried", 1, transport.trackedNames.count { it == "page_view" })
    }

    @Test
    fun tooLargeDropsTheCustomContextAndRetries() = runTest {
        val transport = StubTransport { request ->
            val hasCustom = JSONObject(String(request.body!!)).optJSONObject("customer_context")?.has("custom") == true
            if (hasCustom) 413 to JSONObject().put("error", "payload_too_large") else 200 to allow
        }
        val verdict = client(transport).track("login_attempt", mapOf("method" to "password"), null)
        assertEquals(Transport.Delivered, verdict.transport)
        assertFalse(transport.tracked.last().optJSONObject("customer_context")?.has("custom") ?: false)
    }

    @Test
    fun contextIsFilteredOnDevice() = runTest {
        val transport = StubTransport { 200 to allow }
        client(transport).track("login_attempt", mapOf("method" to "password", "password" to "x", "contact" to "jane@example.com"), null)
        val custom = transport.tracked.last().getJSONObject("customer_context").getJSONObject("custom")
        assertEquals(mapOf("method" to "password"), FixtureTest.plain(custom))
    }

    @Test
    fun emptyEventNameThrows() = runTest {
        val error = runCatching { client(StubTransport { 200 to allow }).track("   ", null, null) }.exceptionOrNull()
        assertEquals(ShieldException.Reason.EMPTY_EVENT_NAME, (error as ShieldException).reason)
    }

    @Test
    fun screenNamesBecomeScreenPaths() {
        assertEquals("/screen/login", PayloadBuilder.screenPath("Login"))
        assertEquals("/screen/sign_up", PayloadBuilder.screenPath("Sign Up"))
        assertNull(PayloadBuilder.screenPath("checkout?step=2"))
    }

    @Test
    fun deviceFamiliesNeverCarryTheModel() {
        assertEquals("Pixel", DeviceInfo.family("Google", "Pixel 8 Pro"))
        assertEquals("Galaxy", DeviceInfo.family("samsung", "SM-S918B"))
        assertEquals("Xiaomi", DeviceInfo.family("Xiaomi", "2201123G"))
        assertEquals("14", DeviceInfo.osVersion("14"))
        assertNull(DeviceInfo.osVersion("UpsideDownCake"))
    }

    // Identity encryption

    private fun identityKey(): Pair<String, PrivateKey> {
        val pair = KeyPairGenerator.getInstance("RSA").apply { initialize(2048) }.generateKeyPair()
        // public.encoded is the X.509 SubjectPublicKeyInfo, as the dashboard publishes it.
        return "fk1.ik_0123456789abcdef.${Base64.getEncoder().encodeToString(pair.public.encoded)}" to pair.private
    }

    @Test
    fun identityIsHashedAndEncryptedForTheDashboardOnly() = runTest {
        val (formatted, privateKey) = identityKey()
        val transport = StubTransport { request ->
            if (request.method == "GET") {
                assertTrue(request.url.contains("/v1/shield/identity-key?site_key=pub_test0000000000"))
                200 to JSONObject().put("key", formatted)
            } else {
                200 to allow
            }
        }
        val client = client(transport)
        client.identify(IdentityInput(userId = "42", email = " Jane@Example.com ", plan = "free"))
        client.track("login_attempt", null, null)

        val context = transport.tracked.last().getJSONObject("customer_context")
        assertEquals("example.com", context.getString("email_domain"))
        assertEquals("free", context.getString("plan"))
        assertEquals(Identity.sha256Hex("jane@example.com"), context.getString("email_hash"))
        val raw = String(transport.requests.last { it.method == "POST" }.body!!)
        assertFalse("no raw email leaves the device", raw.contains("jane@example.com", ignoreCase = true))

        for ((field, plain) in listOf("email_enc" to "jane@example.com", "user_id_enc" to "42")) {
            val value = context.getString(field)
            assertTrue(value.startsWith("fk1.ik_0123456789abcdef."))
            val cipher = Cipher.getInstance("RSA/ECB/OAEPPadding").apply { init(Cipher.DECRYPT_MODE, privateKey, IdentityKey.OAEP_SHA256) }
            assertEquals(plain, String(cipher.doFinal(Base64.getDecoder().decode(value.split(".")[2]))))
        }
        assertEquals("the key is fetched once and cached", 1, transport.requests.count { it.method == "GET" })
    }

    @Test
    fun noIdentityKeyMeansHashesOnly() = runTest {
        val transport = StubTransport { request -> if (request.method == "GET") 404 to JSONObject().put("error", "no_identity_key") else 200 to allow }
        val client = client(transport)
        client.identify(IdentityInput(userId = "42"))
        client.track("login_attempt", null, null)
        val context = transport.tracked.last().getJSONObject("customer_context")
        assertNotNull(context.optString("user_id_hash").takeIf { it.isNotEmpty() })
        assertFalse(context.has("user_id_enc"))
    }

    @Test
    fun identityKeyUrlSitsNextToTheTrackEndpoint() {
        val client = client(StubTransport { 200 to allow })
        assertEquals("https://shield.findip.net/v1/shield/identity-key?site_key=pub_test0000000000", client.identityKeyUrl)
    }
}
