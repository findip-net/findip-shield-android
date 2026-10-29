package net.findip.shield.demo

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.launch
import net.findip.shield.FindIPShield
import net.findip.shield.Recommendation
import net.findip.shield.ShieldEvents
import net.findip.shield.Transport
import net.findip.shield.Verdict

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent {
            MaterialTheme {
                Surface(modifier = Modifier.fillMaxSize()) { LoginScreen() }
            }
        }
    }
}

/**
 * A fake login that shows the whole flow: identify (optional), track the
 * attempt, send the session to the backend, and let the backend decide.
 */
@Composable
fun LoginScreen() {
    var email by remember { mutableStateOf("") }
    var password by remember { mutableStateOf("") }
    var identifyUser by remember { mutableStateOf(true) }
    var busy by remember { mutableStateOf(false) }
    var verdict by remember { mutableStateOf<Verdict?>(null) }
    var backend by remember { mutableStateOf<BackendAnswer?>(null) }
    var signedIn by remember { mutableStateOf(false) }
    val scope = rememberCoroutineScope()

    fun logIn() {
        busy = true
        verdict = null
        backend = null
        scope.launch {
            if (DemoApp.configured) {
                // Only hashes and ciphertext of the email leave the device.
                if (identifyUser && email.isNotBlank()) FindIPShield.identify(email = email.trim(), plan = "demo")
                // Never throws for network or server problems: no answer is "unknown", never "allow".
                verdict = FindIPShield.track(ShieldEvents.LOGIN_ATTEMPT, mapOf("method" to "password"), screen = "login")
            }
            val answer = DemoBackend.login(email.trim(), verdict?.sessionId ?: FindIPShield.sessionId)
            backend = answer
            signedIn = answer.decision == "allow"
            if (signedIn && DemoApp.configured) FindIPShield.track(ShieldEvents.LOGIN_SUCCESS, screen = "login")
            busy = false
        }
    }

    Column(
        modifier = Modifier
            .safeDrawingPadding()
            .verticalScroll(rememberScrollState())
            .padding(20.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Text("Shield demo", style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.SemiBold)
        if (!DemoApp.configured) {
            Note("Shield is off. Add shield.siteKey=pub_… to local.properties and rebuild. The login below still works without it.")
        }

        if (signedIn) {
            Text("Signed in as ${email.ifBlank { "demo user" }}.", style = MaterialTheme.typography.titleMedium)
            OutlinedButton(onClick = {
                // On logout: forget the identity, keep the session and visitor.
                FindIPShield.reset()
                signedIn = false
                password = ""
            }) { Text("Log out") }
        } else {
            OutlinedTextField(
                value = email, onValueChange = { email = it }, label = { Text("Email") }, singleLine = true,
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Email), modifier = Modifier.fillMaxWidth(),
            )
            OutlinedTextField(
                value = password, onValueChange = { password = it }, label = { Text("Password (never sent to Shield)") }, singleLine = true,
                visualTransformation = PasswordVisualTransformation(),
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password), modifier = Modifier.fillMaxWidth(),
            )
            Row(verticalAlignment = Alignment.CenterVertically) {
                Switch(checked = identifyUser, onCheckedChange = { identifyUser = it })
                Spacer(Modifier.width(8.dp))
                Text("Identify the user to Shield")
            }
            Button(onClick = { logIn() }, enabled = !busy, modifier = Modifier.fillMaxWidth()) {
                Text(if (busy) "Checking…" else "Log in")
            }
        }

        // What the user sees: neutral on a refusal, never the reason.
        backend?.let { answer ->
            when (answer.decision) {
                "refuse" -> Note(answer.message ?: "Could not complete the login. Try again later.")
                "step_up" -> Note(answer.message ?: "We need to confirm it is you.")
                null -> Note(answer.note ?: "Backend not reachable.")
                else -> {}
            }
        }

        verdict?.let { VerdictPanel(it) }
        backend?.let { BackendPanel(it) }
    }
}

/** The verdict as the app received it: advice for the app, not the decision. */
@Composable
fun VerdictPanel(verdict: Verdict) {
    Card(modifier = Modifier.fillMaxWidth()) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Text("Shield, in the app", style = MaterialTheme.typography.titleMedium)
            Fact("Recommendation", verdict.risk.recommendation.name.lowercase() + adviceFor(verdict.risk.recommendation))
            Fact("Risk", verdict.risk.score?.let { "$it / 100, ${verdict.risk.level}" } ?: "unknown (never read as safe)")
            if (verdict.risk.reasons.isNotEmpty()) Fact("Reasons", verdict.risk.reasons.joinToString(", "))
            verdict.risk.ruleName?.let { Fact("Decided by rule", it) }
            verdict.network?.let { n -> Fact("Network", "${n.country} · AS${n.asn} ${n.asnName}".trim()) }
            Fact("Delivery", when (val t = verdict.transport) {
                Transport.Delivered -> "delivered"
                is Transport.Dropped -> "dropped: ${t.reason.name.lowercase()}"
            })
            Fact("Session", verdict.sessionId, mono = true)
        }
    }
}

@Composable
fun BackendPanel(answer: BackendAnswer) {
    Card(modifier = Modifier.fillMaxWidth()) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Text("Your backend", style = MaterialTheme.typography.titleMedium)
            Fact("Decision", answer.decision ?: "no answer")
            answer.shieldRecommendation?.let { Fact("Shield told it", it) }
            answer.note?.let { Fact("Note", it) }
        }
    }
}

private fun adviceFor(r: Recommendation): String = when (r) {
    Recommendation.ALLOW, Recommendation.MONITOR -> " (proceed)"
    Recommendation.CHALLENGE -> " (your backend decides)"
    Recommendation.BLOCK -> " (show a neutral message)"
}

@Composable
private fun Fact(label: String, value: String, mono: Boolean = false) {
    Row {
        Text("$label: ", fontWeight = FontWeight.Medium)
        Text(value, fontFamily = if (mono) FontFamily.Monospace else null)
    }
}

@Composable
private fun Note(text: String) {
    Card(modifier = Modifier.fillMaxWidth()) { Text(text, Modifier.padding(16.dp)) }
}
