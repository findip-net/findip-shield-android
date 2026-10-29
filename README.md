# FindIP Shield for Android

A verdict on each sensitive action in your app: signups, logins, password
resets, checkouts. [FindIP Shield](https://www.findip.net/shield) scores the
network behind the request (VPN, proxy, Tor, hosting, known-malicious) and
recommends `ALLOW`, `MONITOR`, `CHALLENGE` or `BLOCK`. Your backend confirms
the verdict before it acts.

- On Maven Central as `net.findip:shield-android`. One dependency (kotlinx-coroutines), permission `INTERNET` only.
- No advertising ID, no `ANDROID_ID`, no fingerprinting.
- Emails and user IDs are hashed and encrypted on the device; only your
  Shield dashboard can open them.
- Free Preview: 10,000 events a day per app site.

## Install

Android 8 (API 26) or later. From Maven Central:

```kotlin
dependencies {
    implementation("net.findip:shield-android:1.0.0")
}
```

Without Maven Central, download `shield-android-1.0.0.aar` from the
[1.0.0 release](https://github.com/findip-net/findip-shield-android/releases/tag/v1.0.0)
(SHA-256 alongside), put it in `libs/`, and use
`implementation(files("libs/shield-android-1.0.0.aar"))` plus
`implementation("org.jetbrains.kotlinx:kotlinx-coroutines-core:1.9.0")`.

Releases are signed with key `8B47 044F 2F53 9A61 850A  5799 80BD BB22 DF49 39D5`.

## Set up

1. In the [Shield dashboard](https://www.findip.net/shield), create a **mobile
   app** site and register your `applicationId`.
2. Configure once, in `Application.onCreate`, with the site's public key:

```kotlin
class App : Application() {
    override fun onCreate() {
        super.onCreate()
        FindIPShield.configure(this, "pub_xxxxxxxxx")
    }
}
```

3. Call Shield before each sensitive action, and send the session ID with the
   request to your backend:

```kotlin
val verdict = FindIPShield.track(ShieldEvents.LOGIN_ATTEMPT, screen = "login")
api.login(email, password, shieldSession = verdict.sessionId)
```

4. Your backend verifies that session with the site's **secret** key before it
   allows, challenges or blocks
   ([server verification](https://www.findip.net/docs/shield/server-verification)):

```bash
curl -X POST https://shield.findip.net/v1/shield/sessions/verify \
  -H "Authorization: Bearer sec_xxxxxxxxx" \
  -d '{"session_id":"sess_xxx"}'
```

The key in the app is public, like any key in an APK. It identifies the app and
proves nothing; only your server's answer counts.

From Java, or code without coroutines:

```java
FindIPShield.track(ShieldEvents.LOGIN_ATTEMPT, new FindIPShield.VerdictCallback() {
    @Override public void onVerdict(Verdict verdict) { /* main thread */ }
    @Override public void onError(Throwable error) { }
});
```

## The verdict

| `verdict.risk.recommendation` | In the app | In your backend |
|---|---|---|
| `ALLOW` | Proceed. | Proceed. |
| `MONITOR` | Proceed. | Proceed; log it if you keep a review queue. |
| `CHALLENGE` | Proceed to your backend. | Ask for your own step-up: a code, a re-login, an email link. |
| `BLOCK` | Show a neutral "could not complete". | Refuse after verifying the session. |

`track` never throws for network or server problems. Without an answer from
Shield it returns `RiskStatus.UNKNOWN` with `MONITOR`, never `ALLOW`, and
`verdict.transport` says why (`Dropped(UNREACHABLE)`, `Dropped(CONFIGURATION)`, …).

## Identify the user

```kotlin
FindIPShield.identify(userId = user.id, email = user.email, plan = "free")
// on logout
FindIPShield.reset()
```

The SDK sends SHA-256 hashes, the email domain, and RSA-OAEP ciphertext made
with your site's public identity key. The raw values never leave the device.

## Options

```kotlin
FindIPShield.configure(this, "pub_xxxxxxxxx", ShieldOptions(
    privacyMode = PrivacyMode.BALANCED,  // STRICT sends no visitor ID, screen, signals or device family
    consentRequired = false,             // true: nothing is sent until FindIPShield.setConsent(true)
    sessionTimeoutMinutes = 30,
    collectDeviceSignals = true,         // emulator / root / debugger heuristics
    debug = false,                       // logs with tag FindIPShield
))
```

Context facts travel with an event: `track(ShieldEvents.CHECKOUT_STARTED, mapOf("items" to 3, "coupon" to true))`.
Up to 20 keys; keys like `password` or `card`, and values that look like an
email, a phone or a card number, never leave the device.

## What is sent

| Sent | Never sent |
|---|---|
| applicationId, versionName and versionCode | Anything the user typed |
| Android version | Advertising ID, `ANDROID_ID`, any hardware ID |
| Device family (Pixel, Galaxy, the maker), never the model | Contacts, location, photos |
| Locale, time zone, screen size | A device fingerprint |
| A session ID per session and a visitor ID in app-private storage | Plain emails or user IDs |
| The event name | |

The visitor ID lives in the app's private SharedPreferences and is cleared on
uninstall. Whether it is included in backups follows your app's
`android:allowBackup`.

**Google Play Data safety answers** for this SDK: Device or other IDs (the
visitor ID) and App interactions (event names), collected, not shared, for
fraud prevention and security, encrypted in transit, not used for advertising.
If you call `identify`, also declare the identifiers you pass.

## Events

`ShieldEvents` lists the names Shield understands: `SIGNUP_ATTEMPT`,
`SIGNUP_SUCCESS`, `LOGIN_ATTEMPT`, `LOGIN_SUCCESS`, `LOGIN_FAILED`,
`PASSWORD_RESET_ATTEMPT`, `CHECKOUT_STARTED`, `PAYMENT_ATTEMPT`,
`PAYMENT_FAILED`, `LEAD_SUBMITTED` and more. Any other name is stored as
`custom`. Sensitive actions are retried once when the network fails.

## Demo app

[`examples/shield-demo`](examples/shield-demo) is a Compose login screen with
a small Node backend that verifies the session: the whole flow from `track`
to the backend's decision, in one runnable project.

## License

MIT. Report vulnerabilities privately: [SECURITY.md](SECURITY.md).
