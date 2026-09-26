package skillbill.engine.featuretask.runloop.state

import skillbill.workflow.taskruntime.model.core.PhaseStepPolicy
import skillbill.workflow.taskruntime.phase.task.FeatureTaskRuntimePhaseWorkflowDefinition

object FeatureTaskRuntimeAttemptBudgets {
  const val MAX_OUTPUT_GATE_RETRY_ATTEMPTS: Int = 1
  const val MAX_FORMAT_RETRY_ATTEMPTS: Int = MAX_OUTPUT_GATE_RETRY_ATTEMPTS
  const val MAX_PROCESS_FAILURE_ATTEMPTS: Int = 3
  private const val MAX_VALIDATE_MALFORMED_ATTEMPTS: Int = 2

  fun validateRemainingUnchangedBlockReason(): String =
    "Phase '${FeatureTaskRuntimePhaseWorkflowDefinition.PHASE_VALIDATE}' reported the same remaining check " +
      "failures as the prior repair turn; the leftover set did not shrink, so the run blocks rather than " +
      "repeating a session that made no progress."

  fun processFailureBlockReason(
    phaseId: String,
    policy: PhaseStepPolicy,
    processFailureCount: Int,
    lastFailureReason: String?,
  ): String? {
    require(processFailureCount >= 0) {
      "processFailureCount must be >= 0, was $processFailureCount."
    }
    val cap =
      if (policy.singleAgentSession) {
        1
      } else {
        MAX_PROCESS_FAILURE_ATTEMPTS
      }
    if (processFailureCount < cap) return null
    val last = lastFailureReason?.takeIf(String::isNotBlank)?.let { " Last failure: $it" }.orEmpty()
    return "Phase '$phaseId' failed to execute $processFailureCount times " +
      "(cap=$cap) without reaching its output gate; the run blocks rather than " +
      "relaunching a process that keeps dying. No repair attempt was consumed.$last"
  }

  fun outputGateBlockReason(
    phaseId: String,
    failureCount: Int,
  ): String? {
    require(failureCount >= 1) {
      "failureCount must be >= 1, was $failureCount."
    }
    val cap =
      if (phaseId == FeatureTaskRuntimePhaseWorkflowDefinition.PHASE_VALIDATE) {
        MAX_VALIDATE_MALFORMED_ATTEMPTS
      } else {
        MAX_OUTPUT_GATE_RETRY_ATTEMPTS
      }
    return if (failureCount >= cap) {
      val attemptWord = if (failureCount == 1) "attempt" else "attempts"
      "Phase '$phaseId' exhausted the bounded output-gate correction budget after " +
        "$failureCount $attemptWord (cap=$cap); the run blocks rather than relaunching."
    } else {
      null
    }
  }

  fun malformedOutputBlockReason(
    phaseId: String,
    malformedAttemptCount: Int,
  ): String? = outputGateBlockReason(phaseId, malformedAttemptCount)

  fun outputGateRejectionExhaustsBudget(
    phaseId: String,
    policy: PhaseStepPolicy,
    priorOutputGateFailures: Int,
  ): Boolean {
    require(priorOutputGateFailures >= 0) {
      "priorOutputGateFailures must be >= 0, was $priorOutputGateFailures."
    }
    val relaunches = policy.relaunchOnInvalidOutput && !policy.singleAgentSession
    return !relaunches || outputGateBlockReason(phaseId, priorOutputGateFailures + 1) != null
  }

  fun unresolvedFindingBlockReason(
    phaseId: String,
    unresolved: Set<String>,
    priorUnresolved: Set<String>,
    detail: String,
  ): String? {
    require(unresolved.isNotEmpty()) { "unresolved must name at least one finding, was empty." }
    val repeated = unresolved.intersect(priorUnresolved).ifEmpty { return null }
    return "Phase '$phaseId' reported the same review findings unresolved on two consecutive " +
      "attempts: ${repeated.sorted().joinToString(", ")}. It had its retry at each of them and the " +
      "finding still stands, so the run blocks for an operator rather than spending a third session " +
      "on it. Reported: $detail"
  }

  fun findingCoverageBlockReason(
    phaseId: String,
    omitted: Set<String>,
    priorOmitted: Set<String>?,
  ): String? {
    require(omitted.isNotEmpty()) { "omitted must name at least one finding, was empty." }
    if (priorOmitted == null) return null
    val progressed = omitted.size < priorOmitted.size && omitted.all(priorOmitted::contains)
    if (progressed) return null
    return "Phase '$phaseId' was sent back for the review findings its repair receipt left out and " +
      "accounted for none of them: ${omitted.sorted().joinToString(", ")}. Re-entering it would " +
      "repeat a round that made no progress on coverage. A round that cannot close a finding " +
      "declares it with outcome 'attempted_unresolved' and a reason; leaving it out is not an " +
      "outcome, so the run blocks for an operator."
  }
}
