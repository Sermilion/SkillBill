package skillbill.infrastructure.launcher.process.launch

import skillbill.error.core.failureCodeLabel
import skillbill.ports.agentrun.model.AgentRunOutputSink
import skillbill.ports.agentrun.model.AgentRunOutputStream
import java.util.logging.Logger

internal enum class ProcessRunDegradationKind {
  PROBE_ABSENCE,
  PROBE_FAILURE,
  STDIN_DELIVERY_FAILURE,
  CLEANUP_FAILURE,
}

internal data class ProcessRunDegradationRecord(
  val kind: ProcessRunDegradationKind,
  val seam: String,
  val detail: String,
)

internal class ProcessRunDegradationRecorder(
  private val maxRecords: Int = MAX_RECORDS_PER_RUN,
  private val maxPerSeam: Int = MAX_RECORDS_PER_SEAM,
) {
  private val records = ArrayList<ProcessRunDegradationRecord>(maxRecords)
  private val countsBySeam = HashMap<String, Int>()

  fun snapshot(): List<ProcessRunDegradationRecord> = records.toList()

  fun appendToStderr(existing: String): String {
    if (records.isEmpty()) return existing
    val lines =
      records.joinToString("\n") { record ->
        "skill-bill: run degradation [${record.kind.name.lowercase()}] ${record.seam}: ${record.detail}"
      }
    return if (existing.isBlank()) lines else "$existing\n$lines"
  }

  fun recordProbeAbsence(seam: String) {
    record(ProcessRunDegradationKind.PROBE_ABSENCE, seam, "expected absence")
  }

  fun recordProbeFailure(
    seam: String,
    failure: Throwable,
  ) {
    record(
      ProcessRunDegradationKind.PROBE_FAILURE,
      seam,
      failure.message.orEmpty().ifBlank {
        failure.failureCodeLabel() ?: failure::class.simpleName.orEmpty()
      },
    )
  }

  fun recordStdinDeliveryFailure(failure: Throwable) {
    record(
      ProcessRunDegradationKind.STDIN_DELIVERY_FAILURE,
      "stdin",
      failure.message.orEmpty().ifBlank {
        failure.failureCodeLabel() ?: failure::class.simpleName.orEmpty()
      },
    )
  }

  fun recordCleanupFailure(
    seam: String,
    failure: Throwable,
  ) {
    record(
      ProcessRunDegradationKind.CLEANUP_FAILURE,
      seam,
      failure.message.orEmpty().ifBlank {
        failure.failureCodeLabel() ?: failure::class.simpleName.orEmpty()
      },
    )
  }

  fun recordLifecyclePublicationFailure(failure: Throwable) {
    recordCleanupFailure("progress_lifecycle_emit", failure)
  }

  private fun record(
    kind: ProcessRunDegradationKind,
    seam: String,
    detail: String,
  ) {
    if (records.size >= maxRecords) return
    val seamCount = countsBySeam.getOrDefault(seam, 0)
    if (seamCount >= maxPerSeam) return
    countsBySeam[seam] = seamCount + 1
    records.add(ProcessRunDegradationRecord(kind = kind, seam = seam, detail = detail))
  }

  private companion object {
    const val MAX_RECORDS_PER_RUN = 32
    const val MAX_RECORDS_PER_SEAM = 8
  }
}

internal fun exportRunDegradationEvidence(
  degradation: ProcessRunDegradationRecorder,
  outputSink: AgentRunOutputSink,
) {
  val payload = degradation.appendToStderr("")
  if (payload.isBlank()) {
    return
  }
  val line = if (payload.endsWith("\n")) payload else "$payload\n"
  val sinkFailure =
    runCatching {
      outputSink.write(AgentRunOutputStream.STDERR, line)
    }.exceptionOrNull() ?: return
  degradationExportLogger.warning(
    "skillbill agent run: degradation export failed; " +
      "records=${payload.take(DEGRADATION_EXPORT_PAYLOAD_LIMIT)}; " +
      "sink=${boundedDegradationFailureDetail(sinkFailure)}",
  )
}

private val degradationExportLogger: Logger =
  Logger.getLogger("skillbill.agent.run.degradation")

private const val DEGRADATION_EXPORT_PAYLOAD_LIMIT = 1_024
private const val DEGRADATION_FAILURE_DETAIL_LIMIT = 240

private fun boundedDegradationFailureDetail(failure: Throwable): String {
  val message = failure.message?.takeIf { it.isNotBlank() }
  return (message ?: failure.failureCodeLabel() ?: failure::class.simpleName.orEmpty())
    .take(DEGRADATION_FAILURE_DETAIL_LIMIT)
}
