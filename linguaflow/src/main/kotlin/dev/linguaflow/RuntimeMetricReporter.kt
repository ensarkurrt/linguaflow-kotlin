package dev.linguaflow

import android.content.Context
import java.util.UUID
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.CancellationException
import dev.linguaflow.generated.RuntimeMetricItemDto

internal typealias RuntimeMetricKind = RuntimeMetricItemDto.Kind
internal typealias RuntimeMetricOutcome = RuntimeMetricItemDto.Outcome
private data class TelemetryContext(val releaseId: String, val token: String)
private data class RuntimeMetricBatch(
  val requestId: String,
  val context: TelemetryContext,
  val metrics: List<RuntimeMetricItemDto>,
  val attempts: Int = 0,
)

internal class RuntimeMetricReporter(
  config: LinguaFlowConfig,
  context: Context,
  private val api: DeliveryApi,
  private val scope: CoroutineScope,
  private val currentManifest: () -> LocaleManifest?,
  private val enabled: Boolean,
) {
  private val appVersion = config.appVersion.ifBlank { hostApplicationVersion(context) }
  private val pending = mutableMapOf<
    TelemetryContext,
    MutableMap<Pair<RuntimeMetricKind, RuntimeMetricOutcome>, Int>,
  >()
  private val retries = ArrayDeque<RuntimeMetricBatch>()
  private var scheduled = false

  fun record(kind: RuntimeMetricKind, outcome: RuntimeMetricOutcome) {
    val manifest = currentManifest()
    val token = manifest?.runtimeTelemetryToken
    if (!enabled || manifest == null || token == null) return
    synchronized(pending) {
      val metrics = pending.getOrPut(TelemetryContext(manifest.releaseId, token)) { mutableMapOf() }
      val key = kind to outcome
      metrics[key] = (metrics[key] ?: 0) + 1
      scheduleLocked()
    }
  }

  suspend fun flush() {
    val batches = synchronized(pending) {
      scheduled = false
      buildList {
        while (retries.isNotEmpty()) add(retries.removeFirst())
        for ((context, metrics) in pending) takeBatch(context, metrics)?.let(::add)
        pending.entries.removeAll { it.value.isEmpty() }
      }
    }
    for (batch in batches) {
      val succeeded = try {
        api.reportRuntimeMetrics(
          batch.requestId,
          batch.context.token,
          appVersion,
          batch.metrics,
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
      if (pending.isNotEmpty() || retries.isNotEmpty()) scheduleLocked()
    }
  }

  private fun scheduleLocked() {
    if (scheduled) return
    scheduled = true
    scope.launch { delay(5_000); flush() }
  }
}

private fun takeBatch(
  context: TelemetryContext,
  pending: MutableMap<Pair<RuntimeMetricKind, RuntimeMetricOutcome>, Int>,
): RuntimeMetricBatch? {
  var remaining = 20_000
  val metrics = buildList {
    for ((key, count) in pending.toMap()) {
      if (size == 16 || remaining == 0) break
      val included = minOf(count, 10_000, remaining)
      add(RuntimeMetricItemDto(key.first, key.second, included))
      val leftover = count - included
      if (leftover == 0) pending.remove(key) else pending[key] = leftover
      remaining -= included
    }
  }
  return metrics.takeIf { it.isNotEmpty() }?.let {
    RuntimeMetricBatch(UUID.randomUUID().toString(), context, it)
  }
}

@Suppress("DEPRECATION")
internal fun hostApplicationVersion(context: Context): String = runCatching {
  val info = if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.TIRAMISU) {
    context.packageManager.getPackageInfo(
      context.packageName,
      android.content.pm.PackageManager.PackageInfoFlags.of(0),
    )
  } else context.packageManager.getPackageInfo(context.packageName, 0)
  val code = if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.P) {
    info.longVersionCode
  } else info.versionCode.toLong()
  info.versionName?.takeIf(String::isNotBlank)?.let { "$it ($code)" } ?: code.toString()
}.getOrDefault("")
