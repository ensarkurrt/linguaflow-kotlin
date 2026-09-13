package dev.linguaflow

import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertInstanceOf
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Test

class DeliveryApiTest {
  @Test
  fun `rejects unknown runtime contract version`() {
    assertThrows(IllegalArgumentException::class.java) {
      LocaleManifest.fromJson(org.json.JSONObject(manifestResponse().body).put("version", 2))
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

  private fun manifestResponse() = HttpResult(
    200,
    """{
      "version":1,
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
