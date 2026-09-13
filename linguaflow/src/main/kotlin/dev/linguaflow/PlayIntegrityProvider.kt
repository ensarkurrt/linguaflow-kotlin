package dev.linguaflow

import android.content.Context
import dev.linguaflow.generated.IntegrityGrantResponseDto
import dev.linguaflow.generated.PlayIntegrityChallengeResponseDto
import com.google.android.play.core.integrity.IntegrityManagerFactory
import com.google.android.play.core.integrity.StandardIntegrityManager
import java.time.Instant
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.tasks.await
import org.json.JSONObject

/** Google Play Integrity Standard adapter backed by short-lived LinguaFlow delivery grants. */
class PlayIntegrityProvider internal constructor(
  private val packageName: String,
  private val exchange: PlayIntegrityExchange = HttpPlayIntegrityExchange(),
  private val platform: PlayIntegrityPlatform,
) : DeviceIntegrityProvider {
  constructor(
    context: Context,
    packageName: String = context.packageName,
    exchange: PlayIntegrityExchange = HttpPlayIntegrityExchange(),
  ) : this(packageName, exchange, GooglePlayIntegrityPlatform(context))

  private val mutex = Mutex()
  private val grants = mutableMapOf<String, IntegrityGrant>()

  override suspend fun obtainGrant(branchKey: String): String = mutex.withLock {
    grants[branchKey]
      ?.takeIf { it.expiresAt.isAfter(Instant.now().plusSeconds(GRANT_SAFETY_WINDOW_SECONDS)) }
      ?.let { return it.token }

    val challenge = exchange.createChallenge(branchKey, packageName)
    val integrityToken = platform.requestToken(
      challenge.cloudProjectNumber,
      challenge.requestHash,
    )
    exchange.exchangeToken(branchKey, packageName, challenge.challengeId, integrityToken).also {
      grants[branchKey] = it
    }.token
  }

  companion object { private const val GRANT_SAFETY_WINDOW_SECONDS = 30L }
}

interface PlayIntegrityPlatform {
  suspend fun requestToken(cloudProjectNumber: Long, requestHash: String): String
}

class GooglePlayIntegrityPlatform(context: Context) : PlayIntegrityPlatform {
  private val manager = IntegrityManagerFactory.createStandard(context.applicationContext)
  private val providers = mutableMapOf<Long, StandardIntegrityManager.StandardIntegrityTokenProvider>()
  private val mutex = Mutex()

  override suspend fun requestToken(cloudProjectNumber: Long, requestHash: String): String {
    val provider = mutex.withLock {
      providers[cloudProjectNumber] ?: manager.prepareIntegrityToken(
        StandardIntegrityManager.PrepareIntegrityTokenRequest.builder()
          .setCloudProjectNumber(cloudProjectNumber)
          .build(),
      ).await().also { providers[cloudProjectNumber] = it }
    }
    return provider.request(
      StandardIntegrityManager.StandardIntegrityTokenRequest.builder()
        .setRequestHash(requestHash)
        .build(),
    ).await().token()
  }
}

data class PlayIntegrityChallenge(
  val challengeId: String,
  val requestHash: String,
  val cloudProjectNumber: Long,
  val expiresAt: Instant,
)

data class IntegrityGrant(val token: String, val expiresAt: Instant)

interface PlayIntegrityExchange {
  suspend fun createChallenge(branchKey: String, packageName: String): PlayIntegrityChallenge
  suspend fun exchangeToken(
    branchKey: String,
    packageName: String,
    challengeId: String,
    integrityToken: String,
  ): IntegrityGrant
}

class HttpPlayIntegrityExchange(
  private val transport: HttpTransport = UrlConnectionTransport(),
) : PlayIntegrityExchange {
  override suspend fun createChallenge(
    branchKey: String,
    packageName: String,
  ): PlayIntegrityChallenge {
    val response = transport.post(
      "$API_ORIGIN/v1/bundles/$branchKey/attestation/android/challenges",
      JSONObject().put("packageName", packageName).toString(),
      runtimeHeaders(),
    ).requireSuccess()
    val body = RuntimeContractJson.gson.fromJson(
      response.body,
      PlayIntegrityChallengeResponseDto::class.java,
    )
    return PlayIntegrityChallenge(
      challengeId = body.challengeId.toString(),
      requestHash = body.requestHash,
      cloudProjectNumber = body.cloudProjectNumber.toLong(),
      expiresAt = body.expiresAt.toInstant(),
    )
  }

  override suspend fun exchangeToken(
    branchKey: String,
    packageName: String,
    challengeId: String,
    integrityToken: String,
  ): IntegrityGrant {
    val response = transport.post(
      "$API_ORIGIN/v1/bundles/$branchKey/attestation/android/tokens",
      JSONObject()
        .put("packageName", packageName)
        .put("challengeId", challengeId)
        .put("integrityToken", integrityToken)
        .toString(),
      runtimeHeaders(),
    ).requireSuccess()
    val body = RuntimeContractJson.gson.fromJson(response.body, IntegrityGrantResponseDto::class.java)
    return IntegrityGrant(body.token, body.expiresAt.toInstant())
  }

  private fun runtimeHeaders() = mapOf(
    "X-LinguaFlow-SDK" to "android",
    "X-LinguaFlow-SDK-Version" to LINGUAFLOW_ANDROID_SDK_VERSION,
    "X-LinguaFlow-Contract-Version" to LINGUAFLOW_RUNTIME_CONTRACT_VERSION.toString(),
  )

  companion object { private const val API_ORIGIN = "https://api.linguaflow.dev" }
}
