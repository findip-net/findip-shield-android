package net.findip.shield.demo

import java.net.HttpURLConnection
import java.net.URL
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONObject

/** What the demo backend (server/server.mjs) decided after verifying the session. */
data class BackendAnswer(
    /** allow, step_up or refuse; null when the backend could not be reached. */
    val decision: String?,
    val message: String?,
    val note: String?,
    /** What Shield told the backend. Shown by the demo only; a real backend keeps it. */
    val shieldRecommendation: String?,
)

object DemoBackend {
    /** POST /login with the Shield session in a header. The password never goes to Shield. */
    suspend fun login(email: String, shieldSessionId: String?): BackendAnswer = withContext(Dispatchers.IO) {
        try {
            val connection = URL("${BuildConfig.DEMO_BACKEND}/login").openConnection() as HttpURLConnection
            connection.requestMethod = "POST"
            connection.connectTimeout = 3_000
            connection.readTimeout = 5_000
            connection.doOutput = true
            connection.setRequestProperty("Content-Type", "application/json")
            shieldSessionId?.let { connection.setRequestProperty("X-Shield-Session", it) }
            connection.outputStream.use { it.write(JSONObject().put("email", email).toString().toByteArray()) }
            val stream = if (connection.responseCode < 400) connection.inputStream else connection.errorStream
            val body = JSONObject(stream.bufferedReader().use { it.readText() })
            BackendAnswer(
                decision = body.optString("decision").ifEmpty { null },
                message = body.optString("message").ifEmpty { null },
                note = body.optString("note").ifEmpty { null },
                shieldRecommendation = body.optJSONObject("shield")?.optString("recommendation")?.ifEmpty { null },
            )
        } catch (e: Exception) {
            BackendAnswer(decision = null, message = null, note = "Backend not reachable at ${BuildConfig.DEMO_BACKEND}", shieldRecommendation = null)
        }
    }
}
