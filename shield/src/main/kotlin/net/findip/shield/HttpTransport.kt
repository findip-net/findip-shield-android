package net.findip.shield

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.net.HttpURLConnection
import java.net.URL

internal data class HttpRequest(val method: String, val url: String, val body: ByteArray?, val timeoutMillis: Int)

internal data class HttpResponse(val status: Int, val body: String)

/** The network, behind an interface so tests can answer without one. */
internal fun interface HttpTransport {
    suspend fun send(request: HttpRequest): HttpResponse
}

/** HttpURLConnection on the IO dispatcher: no cookies, no cache, nothing kept. */
internal object UrlConnectionTransport : HttpTransport {
    override suspend fun send(request: HttpRequest): HttpResponse = withContext(Dispatchers.IO) {
        val connection = URL(request.url).openConnection() as HttpURLConnection
        try {
            connection.requestMethod = request.method
            connection.connectTimeout = request.timeoutMillis
            connection.readTimeout = request.timeoutMillis
            connection.useCaches = false
            connection.instanceFollowRedirects = false
            if (request.body != null) {
                connection.doOutput = true
                connection.setRequestProperty("Content-Type", "text/plain")
                connection.outputStream.use { it.write(request.body) }
            }
            val status = connection.responseCode
            val stream = if (status in 200..299) connection.inputStream else connection.errorStream
            HttpResponse(status, stream?.bufferedReader()?.use { it.readText() } ?: "")
        } finally {
            connection.disconnect()
        }
    }
}
