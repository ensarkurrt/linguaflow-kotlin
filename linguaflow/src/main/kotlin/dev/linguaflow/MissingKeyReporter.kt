package dev.linguaflow

import java.util.UUID
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

internal class MissingKeyReporter(
  private val config: LinguaFlowConfig,
  private val api: DeliveryApi,
  private val scope: CoroutineScope,
  private val currentManifest: () -> LocaleManifest?,
) {
  private val pending = linkedSetOf<String>()
  private var flushScheduled = false

  fun record(path: String) {
    if (!config.missingKeyTelemetryEnabled || currentManifest()?.missingKeyTelemetryEnabled != true) {
      return
    }
    synchronized(pending) {
      pending += path
      if (flushScheduled) return
      flushScheduled = true
    }
    scope.launch {
      delay(INITIAL_DELAY_MILLISECONDS)
      flush()
    }
  }

  suspend fun flush() {
    val manifest = currentManifest() ?: return
    val keys = takeBatch(manifest.missingKeyTelemetryMaxBatchSize.coerceAtMost(100))
    if (keys.isEmpty()) return
    val succeeded = runCatching {
      api.reportMissingKeys(
        MissingKeysReport(
          requestId = UUID.randomUUID().toString(),
          releaseId = manifest.releaseId,
          locale = manifest.resolvedLocale,
          appVersion = config.appVersion,
          keys = keys,
        ),
      )
    }.isSuccess
    if (!succeeded) synchronized(pending) { pending.addAll(keys) }
    scheduleRetryIfNeeded()
  }

  private fun takeBatch(maxSize: Int): List<String> = synchronized(pending) {
    flushScheduled = false
    pending.take(maxSize).also { pending.removeAll(it.toSet()) }
  }

  private fun scheduleRetryIfNeeded() {
    val shouldRetry = synchronized(pending) {
      if (pending.isEmpty() || flushScheduled) false else true.also { flushScheduled = true }
    }
    if (shouldRetry) scope.launch {
      delay(RETRY_DELAY_MILLISECONDS)
      flush()
    }
  }

  private companion object {
    const val INITIAL_DELAY_MILLISECONDS = 500L
    const val RETRY_DELAY_MILLISECONDS = 5_000L
  }
}
