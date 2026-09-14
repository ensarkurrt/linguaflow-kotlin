package dev.linguaflow

import java.net.HttpURLConnection
import java.net.URL
import java.io.ByteArrayOutputStream
import java.io.InputStream
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
      val declaredLength = connection.contentLengthLong
      if (declaredLength > MAX_RESPONSE_BYTES) {
        throw LinguaFlowException("LinguaFlow response is too large")
      }
      HttpResult(
        status = status,
        body = stream?.use { readBoundedUtf8(it, MAX_RESPONSE_BYTES) } ?: "",
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
    private const val MAX_RESPONSE_BYTES = 5 * 1024 * 1024

  }
}

internal fun readBoundedUtf8(input: InputStream, maximumBytes: Int): String {
  val output = ByteArrayOutputStream()
  val buffer = ByteArray(DEFAULT_BUFFER_SIZE)
  var total = 0
  while (true) {
    val count = input.read(buffer)
    if (count < 0) break
    total += count
    if (total > maximumBytes) {
      throw LinguaFlowException("LinguaFlow response is too large")
    }
    output.write(buffer, 0, count)
  }
  return output.toString(Charsets.UTF_8.name())
}
