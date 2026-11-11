package skillbill.infrastructure.launcher.process.launch

import skillbill.infrastructure.launcher.process.interaction.AgentRunSessionCapture
import skillbill.infrastructure.launcher.process.interaction.AgentRunSessionOutcome
import skillbill.ports.agentrun.model.AgentRunSessionFailureEvidence

internal fun AgentRunSessionCapture.failureMessage(processCaptureIncomplete: Boolean): String? =
  listOfNotNull(
    "Provider session output was truncated at the capture limit.".takeIf { truncated },
    "Provider session output could not be delivered to the phase output sink.".takeIf { deliveryFailed },
    "Provider process output capture did not finish completely.".takeIf { processCaptureIncomplete },
    "Provider session output capture is incomplete.".takeIf { !complete && !truncated && !deliveryFailed },
  ).distinct().joinToString("\n").ifBlank { null }

internal fun sessionFailureMessage(outcome: AgentRunSessionOutcome?): String? =
  outcome?.failure ?: when {
    outcome == null ->
      "Provider session closed before a matching terminal turn; delivery will " +
        "not be replayed."
    outcome.completed && outcome.text.isBlank() ->
      "Provider completed the turn without a completed assistant response."
    else -> null
  }

internal fun buildSessionFailureEvidence(
  nativeStderr: String,
  capture: AgentRunSessionCapture?,
  failure: String?,
  captureFailure: String?,
): AgentRunSessionFailureEvidence =
  AgentRunSessionFailureEvidence(
    listOfNotNull(
      failure,
      captureFailure,
      capture?.failureDiagnostics,
      nativeStderr.takeIf(String::isNotBlank),
    ).joinToString("\n").ifBlank { null },
  )

internal fun sessionSettlement(
  outcome: AgentRunSessionOutcome?,
  capture: AgentRunSessionCapture?,
  expected: Boolean,
  release: ProcessRunReleaseSnapshot,
  degradation: List<ProcessRunDegradationRecord>,
): ProcessSessionSettlement {
  val cleanupFailure = sessionCleanupFailure(outcome, release, degradation)
  val failure =
    listOfNotNull(
      if (expected) sessionFailureMessage(outcome) else null,
      cleanupFailure,
    ).joinToString("\n").ifBlank { null }
  val effective = outcome ?: if (expected) AgentRunSessionOutcome("", null, null, false, failure) else null
  val complete =
    listOf(
      effective?.completed == true,
      effective?.text?.isNotBlank() == true,
      capture?.complete == true,
      !release.outputCaptureIncomplete,
      cleanupFailure == null,
    ).all { it }
  val captureFailure = capture?.failureMessage(release.outputCaptureIncomplete)
  return ProcessSessionSettlement(
    effective,
    capture,
    expected && !complete,
    failure,
    captureFailure,
    cleanupFailure,
    if (expected) {
      buildSessionFailureEvidence(release.stderrCapture.text, capture, failure, captureFailure)
    } else {
      null
    },
  )
}

internal data class ProcessSessionSettlement(
  val outcome: AgentRunSessionOutcome?,
  val capture: AgentRunSessionCapture?,
  val failed: Boolean,
  val failure: String?,
  val captureFailure: String?,
  val cleanupFailure: String?,
  val failureEvidence: AgentRunSessionFailureEvidence?,
)

private fun sessionCleanupFailure(
  outcome: AgentRunSessionOutcome?,
  release: ProcessRunReleaseSnapshot,
  degradation: List<ProcessRunDegradationRecord>,
): String? {
  val cleanupDetails =
    degradation.filter { record ->
      val unsafeProcess = record.seam.startsWith("process_") && !release.processTreeExited
      val streamFailure = record.seam.endsWith("_drain_join") || record.seam.endsWith("_stream_close")
      record.kind == ProcessRunDegradationKind.CLEANUP_FAILURE && (unsafeProcess || streamFailure)
    }
  return if (!release.processTreeExited || cleanupDetails.isNotEmpty()) {
    buildString {
      append(if (outcome?.completed == true) "Provider turn completed; " else "Provider session stopped; ")
      append("launcher cleanup failed. ")
      if (!release.processTreeExited) {
        append("Owned process exit is unconfirmed; continuation is blocked. ")
      }
      append(cleanupDetails.joinToString("; ") { "${it.seam}: ${it.detail}" })
    }.trim()
  } else {
    null
  }
}
