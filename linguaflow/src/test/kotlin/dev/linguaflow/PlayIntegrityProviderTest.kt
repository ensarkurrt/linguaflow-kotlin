package dev.linguaflow

import java.time.Instant
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test

class PlayIntegrityProviderTest {
  @Test
  fun `binds challenge to platform token and caches short lived grant`() = runTest {
    val exchange = RecordingExchange()
    val platform = RecordingPlatform()
    val provider = PlayIntegrityProvider(
      packageName = "dev.linguaflow.example",
      exchange = exchange,
      platform = platform,
    )

    val first = provider.obtainGrant("br_live_test")
    val second = provider.obtainGrant("br_live_test")

    assertEquals("delivery-grant", first)
    assertEquals(first, second)
    assertEquals(1, platform.requests)
    assertEquals("request-hash", platform.lastRequestHash)
    assertEquals(1, exchange.exchanges)
  }
}

private class RecordingPlatform : PlayIntegrityPlatform {
  var requests = 0
  var lastRequestHash: String? = null

  override suspend fun requestToken(cloudProjectNumber: Long, requestHash: String): String {
    requests++
    lastRequestHash = requestHash
    assertEquals(123L, cloudProjectNumber)
    return "google-token"
  }
}

private class RecordingExchange : PlayIntegrityExchange {
  var exchanges = 0

  override suspend fun createChallenge(
    branchKey: String,
    packageName: String,
  ) = PlayIntegrityChallenge(
    challengeId = "challenge-id",
    requestHash = "request-hash",
    cloudProjectNumber = 123,
    expiresAt = Instant.now().plusSeconds(300),
  )

  override suspend fun exchangeToken(
    branchKey: String,
    packageName: String,
    challengeId: String,
    integrityToken: String,
  ): IntegrityGrant {
    exchanges++
    assertEquals("challenge-id", challengeId)
    assertEquals("google-token", integrityToken)
    return IntegrityGrant("delivery-grant", Instant.now().plusSeconds(300))
  }
}
