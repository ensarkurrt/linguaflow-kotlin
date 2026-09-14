package dev.linguaflow

import android.content.Context
import java.util.UUID
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.CancellationException

private data class MissingKeyContext(
  val releaseId: String,
  val locale: String,
  val token: String,
  val maxBatchSize: Int,
)

private data class MissingKeyBatch(
  val requestId: String,
  val context: MissingKeyContext,
  val keys: List<String>,
  val attempts: Int = 0,
)

internal class MissingKeyReporter(
  private val config: LinguaFlowConfig,
  context: Context,
  private val api: DeliveryApi,
  private val scope: CoroutineScope,
  private val currentManifest: () -> LocaleManifest?,
) {
  private val appVersion = config.appVersion.ifBlank { hostApplicationVersion(context) }
  private val pending = mutableMapOf<MissingKeyContext, MutableSet<String>>()
  private val retries = ArrayDeque<MissingKeyBatch>()
  private var scheduled = false

  fun record(path: String) {
    if (path.length > MAX_KEY_LENGTH) return
    val manifest = currentManifest()
    val token = manifest?.runtimeTelemetryToken
    if (!config.missingKeyTelemetryEnabled || manifest == null ||
      !manifest.missingKeyTelemetryEnabled || token == null
    ) return
    synchronized(pending) {
      pending.getOrPut(
        MissingKeyContext(
          manifest.releaseId,
          manifest.resolvedLocale,
          token,
          manifest.missingKeyTelemetryMaxBatchSize.coerceAtMost(100),
        ),
      ) { linkedSetOf() }.add(path)
      scheduleLocked(INITIAL_DELAY_MILLISECONDS)
    }
  }

  suspend fun flush() {
    val batches = synchronized(pending) {
      scheduled = false
      buildList {
        while (retries.isNotEmpty()) add(retries.removeFirst())
        for ((context, keys) in pending) {
          val selected = keys.take(context.maxBatchSize)
          if (selected.isNotEmpty()) {
            keys.removeAll(selected.toSet())
            add(MissingKeyBatch(UUID.randomUUID().toString(), context, selected))
          }
        }
        pending.entries.removeAll { it.value.isEmpty() }
      }
    }
    for (batch in batches) {
      val succeeded = try {
        api.reportMissingKeys(
          MissingKeysReport(
            requestId = batch.requestId,
            locale = batch.context.locale,
            appVersion = appVersion,
            telemetryToken = batch.context.token,
            keys = batch.keys,
          ),
        )
        true
      } catch (error: CancellationException) {
        throw error
      } catch (error: Exception) {
        false
      }
      if (!succeeded && batch.attempts < 3) synchronized(pending) {
        retries.addLast(batch.copy(attempts = batch.attempts + 1))
      }
    }
    synchronized(pending) {
      if (pending.isNotEmpty() || retries.isNotEmpty()) scheduleLocked(RETRY_DELAY_MILLISECONDS)
    }
  }

  private fun scheduleLocked(delayMilliseconds: Long) {
    if (scheduled) return
    scheduled = true
    scope.launch { delay(delayMilliseconds); flush() }
  }

  private companion object {
    const val INITIAL_DELAY_MILLISECONDS = 500L
    const val RETRY_DELAY_MILLISECONDS = 5_000L
    const val MAX_KEY_LENGTH = 512
  }
}
