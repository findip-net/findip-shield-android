# Shield demo for Android

A fake login screen that shows the whole Shield flow in a Compose app:

1. Shield is configured once in `DemoApp`.
2. On **Log in**, the app optionally identifies the user (only hashes and
   ciphertext leave the device), then tracks `login_attempt`.
3. The app sends the Shield session ID to its backend in an `X-Shield-Session`
   header. The password never goes to Shield.
4. The backend ([`server/server.mjs`](server/server.mjs)) verifies the session
   with the site's secret key and decides: allow, ask for a step-up, or refuse
   with a neutral message.

The screen shows both sides: the verdict the app received, and what the
backend decided.

## Run it

You need Android Studio (or the Android SDK and JDK 17) and Node 18 or later.

1. In the [Shield dashboard](https://www.findip.net/shield), create a **mobile
   app** site with the Android app id `net.findip.shield.demo`.
2. Add the site's public key to `local.properties` at the repository root:

   ```properties
   shield.siteKey=pub_xxxxxxxxx
   ```

3. Start the backend with the site's secret key:

   ```bash
   SHIELD_SECRET_KEY=sec_xxxxxxxxx node examples/shield-demo/server/server.mjs
   ```

4. Run the app on an emulator: open the repository in Android Studio and run
   the `shield-demo` configuration, or
   `./gradlew :shield-demo:installDebug`.

The emulator reaches the backend on your computer at `http://10.0.2.2:8787`.
On a real device, set `shield.demoBackend=http://<your computer's IP>:8787`
in `local.properties` and allow that host in
`src/main/res/xml/network_security_config.xml`.

Without a site key the app still runs: Shield stays off and the screen says
so, so you can read the code path first.

## Try a verdict

Custom rules in the dashboard change the verdict at once. For example, add a
rule **App version or platform → Android app, version below 2.0 → Block**:
this demo is version 1.0.0, so the next login is refused with a neutral
message, and the panel shows the rule's name.

## Tests

`src/androidTest` runs the whole flow on an emulator against a stand-in for
Shield (`ci/fake-shield.mjs`). CI runs it on every pull request.

## In your own app

The demo builds against this repository's source. Your app uses the published
package:

```kotlin
implementation("net.findip:shield-android:1.0.2")
```
