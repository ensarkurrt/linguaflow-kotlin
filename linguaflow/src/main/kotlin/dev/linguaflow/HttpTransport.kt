package dev.linguaflow

import java.net.HttpURLConnection
import java.net.URL
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

data class HttpResult(val status: Int, val body: String, val headers: Map<String, String>) {
  fun requireSuccess(): HttpResult {
    if (status !in 200..299) throw LinguaFlowException("LinguaFlow request failed", status)
    return this
  }

  fun header(name: String): String? =
    headers.entries.firstOrNull { it.key.equals(name, ignoreCase = true) }?.value
}

interface HttpTransport {
  suspend fun get(url: String, headers: Map<String, String> = emptyMap()): HttpResult
  suspend fun post(
    url: String,
    body: String,
    headers: Map<String, String> = emptyMap(),
  ): HttpResult
}

class UrlConnectionTransport : HttpTransport {
  override suspend fun get(url: String, headers: Map<String, String>): HttpResult =
    request(url, "GET", headers, null)

  override suspend fun post(
    url: String,
    body: String,
    headers: Map<String, String>,
  ): HttpResult = request(url, "POST", headers + ("Content-Type" to "application/json"), body)

  private suspend fun request(
    url: String,
    method: String,
    headers: Map<String, String>,
    body: String?,
  ): HttpResult = withContext(Dispatchers.IO) {
    val connection = URL(url).openConnection() as HttpURLConnection
    try {
      connection.requestMethod = method
      connection.connectTimeout = CONNECT_TIMEOUT_MILLISECONDS
      connection.readTimeout = READ_TIMEOUT_MILLISECONDS
      connection.instanceFollowRedirects = false
      headers.forEach(connection::setRequestProperty)
      if (body != null) {
        connection.doOutput = true
        connection.outputStream.use { it.write(body.encodeToByteArray()) }
      }
      val status = connection.responseCode
      val stream = if (status in 200..299) connection.inputStream else connection.errorStream
      HttpResult(
        status = status,
        body = stream?.bufferedReader()?.use { it.readText() } ?: "",
        headers = connection.headerFields
          .filterKeys { it != null }
          .mapValues { it.value.joinToString(",") },
      )
    } finally {
      connection.disconnect()
    }
  }

  companion object {
    private const val CONNECT_TIMEOUT_MILLISECONDS = 10_000
    private const val READ_TIMEOUT_MILLISECONDS = 30_000
  }
}
