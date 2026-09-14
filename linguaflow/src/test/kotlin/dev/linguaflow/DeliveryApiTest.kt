package dev.linguaflow

import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertInstanceOf
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Test
import java.io.ByteArrayInputStream
import java.io.IOException
import java.io.InputStream
import org.json.JSONObject

class DeliveryApiTest {
  @Test
  fun `rejects unknown runtime contract version`() {
    assertThrows(IllegalArgumentException::class.java) {
      LocaleManifest.fromJson(org.json.JSONObject(manifestResponse().body).put("version", 1))
    }
  }

  @Test
  fun `parses typed manifest and sends installation header`() = runTest {
    val transport = RecordingTransport(manifestResponse())
    val api = DeliveryApi(
      LinguaFlowConfig("br_live_test"),
      "installation-123",
      null,
      transport,
    )

    val manifest = api.manifest("tr-TR", explicit = false)

    assertEquals(LocaleResolutionReason.DEVICE, manifest.reason)
    assertEquals("installation-123", transport.lastHeaders["X-LinguaFlow-Installation-Id"])
    assertEquals("tr-TR", transport.lastHeaders["X-LinguaFlow-Device-Locale"])
    assertEquals("2", transport.lastHeaders["X-LinguaFlow-Contract-Version"])
  }

  @Test
  fun `rejects non-string translation leaves`() = runTest {
    val transport = RecordingTransport(HttpResult(200, """{"home":{"title":42}}""", emptyMap()))
    val api = DeliveryApi(LinguaFlowConfig("br_live_test"), "installation-123", null, transport)

    assertThrows(BundlePayloadException::class.java) {
      kotlinx.coroutines.runBlocking { api.bundle("en", null) }
    }
  }

  @Test
  fun `models not modified without parsing an empty body`() = runTest {
    val transport = RecordingTransport(HttpResult(304, "", emptyMap()))
    val api = DeliveryApi(
      LinguaFlowConfig("br_live_test"),
      "installation-123",
      null,
      transport,
    )

    assertInstanceOf(BundleDelivery.NotModified::class.java, api.bundle("tr", "etag-r1"))
    assertEquals("etag-r1", transport.lastHeaders["If-None-Match"])
  }

  @Test
  fun `propagates truncated stream failures and rejects oversized bodies`() {
    val truncated = object : InputStream() {
      private var emitted = false
      override fun read(): Int = if (!emitted) {
        emitted = true
        '{'.code
      } else {
        throw IOException("connection reset")
      }
    }
    assertThrows(IOException::class.java) { readBoundedUtf8(truncated, 10) }
    assertThrows(LinguaFlowException::class.java) {
      readBoundedUtf8(ByteArrayInputStream("123".encodeToByteArray()), 2)
    }
  }

  @Test
  fun `rejects corrupted offline cache payload`() {
    assertThrows(BundlePayloadException::class.java) {
      decodeStoredBundle(JSONObject("""{"releaseId":"r1","data":{"home":42}}"""))
    }
  }

  private fun manifestResponse() = HttpResult(
    200,
    """{
      "version":2,
      "releaseId":"release-1",
      "sequence":1,
      "requestedLocale":"tr-TR",
      "resolvedLocale":"tr",
      "reason":"device",
      "supportedLocales":["en","tr"],
      "translatedLocales":["en","tr"],
      "fallbackLocale":"en",
      "localeMappings":{}
      ,"rollout":{"candidateReleaseId":"release-1","percentage":100,"selection":"stable"}
      ,"bundlePath":"releases/release-1/tr.json"
      ,"overlays":[]
      ,"overlay":null
      ,"missingKeyTelemetry":{"enabled":false,"maxBatchSize":100}
    }""".trimIndent(),
    emptyMap(),
  )
}

private class RecordingTransport(private val response: HttpResult) : HttpTransport {
  var lastHeaders: Map<String, String> = emptyMap()

  override suspend fun get(url: String, headers: Map<String, String>): HttpResult {
    lastHeaders = headers
    return response
  }

  override suspend fun post(
    url: String,
    body: String,
    headers: Map<String, String>,
  ): HttpResult {
    lastHeaders = headers
    return response
  }
}
