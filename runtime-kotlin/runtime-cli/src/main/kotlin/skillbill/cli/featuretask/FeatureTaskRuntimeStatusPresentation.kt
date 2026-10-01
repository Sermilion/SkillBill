package skillbill.cli.featuretask

import skillbill.application.decomposition.decompositionManifestPath
import skillbill.application.decomposition.parentSpecPath
import skillbill.cli.kernel.payload.CliPayloadStatus
import skillbill.contracts.SharedPayloadKeys
import skillbill.contracts.workflow.identity.evidence.ValidationEvidencePayloadKeys
import skillbill.engine.featuretask.model.core.FeatureTaskRuntimePhaseStatus
import skillbill.engine.featuretask.model.core.FeatureTaskRuntimeStatusProjection
import skillbill.workflow.taskruntime.artifact.presentationWireMap

internal fun FeatureTaskRuntimeStatusProjection?.toRuntimeStatusCliMap(workflowId: String): Map<String, Any?> =
  this?.let {
    linkedMapOf<String, Any?>(
      SharedPayloadKeys.STATUS to CliPayloadStatus.OK,
      SharedPayloadKeys.WORKFLOW_ID to it.workflowId,
      "feature_size" to it.featureSize,
      "complete_count" to it.completeCount,
      "pending_count" to it.pendingCount,
      "blocked_count" to it.blockedCount,
      "current_phase" to it.currentPhaseId,
      "resolved_branch" to it.resolvedBranch,
      "finalizing_agent_id" to it.finalizingAgentId,
      "gate_run_count" to it.gateRunCount,
      ValidationEvidencePayloadKeys.VALIDATION_STATUS to
        it.validationGateExecutionEvidence?.validationStatus,
      ValidationEvidencePayloadKeys.CHECKS to it.validationGateExecutionEvidence?.checks,
      ValidationEvidencePayloadKeys.GATE_RUN_COUNT to it.validationGateExecutionEvidence?.gateRunCount,
      ValidationEvidencePayloadKeys.GATE_RUNS to
        it.validationGateExecutionEvidence?.gateRuns?.map { run -> run.presentationWireMap() },
      "degraded_diagnostic" to
        it.degradedDiagnostic?.let { degraded ->
          linkedMapOf(
            "count" to degraded.count,
            "failure_class" to degraded.failureClass,
            SharedPayloadKeys.PHASE_ID to degraded.phaseId,
            "attempt" to degraded.attempt,
          )
        },
      "decompose_terminal" to
        it.decomposeTerminal?.let { terminal ->
          linkedMapOf(
            "reason" to terminal.reason,
            "parent_spec_path" to terminal.parentSpecPath,
            "decomposition_manifest_path" to terminal.decompositionManifestPath,
            "subtask_spec_paths" to terminal.subtaskSpecPaths,
            "subtask_count" to terminal.subtaskCount,
            "guidance" to DECOMPOSE_GUIDANCE,
          )
        },
      "phases" to it.phases.map(FeatureTaskRuntimePhaseStatus::toRuntimePhaseStatusCliMap),
    )
  } ?: linkedMapOf(
    SharedPayloadKeys.STATUS to CliPayloadStatus.NOT_FOUND,
    SharedPayloadKeys.WORKFLOW_ID to workflowId,
    "feature_size" to null,
    "complete_count" to 0,
    "pending_count" to 0,
    "blocked_count" to 0,
    "current_phase" to null,
    "resolved_branch" to null,
    "finalizing_agent_id" to null,
    "degraded_diagnostic" to null,
    "decompose_terminal" to null,
    "phases" to emptyList<Map<String, Any?>>(),
  )

internal fun FeatureTaskRuntimePhaseStatus.toRuntimePhaseStatusCliMap(): Map<String, Any?> =
  linkedMapOf(
    SharedPayloadKeys.PHASE_ID to phaseId,
    SharedPayloadKeys.STATUS to status,
    "attempt_count" to attemptCount,
    "resolved_agent_id" to resolvedAgentId,
    "execution_origin" to executionOrigin,
    "continuation_kind" to continuationKind,
    "finished" to finished,
  )

internal fun runtimeStatusExitCode(projection: FeatureTaskRuntimeStatusProjection?): Int =
  if (projection != null) 0 else 1

internal fun runtimeStatusText(
  projection: FeatureTaskRuntimeStatusProjection?,
  workflowId: String,
): String =
  buildString {
    appendLine("feature-task-runtime: ${projection?.workflowId ?: workflowId}")
    appendLine("status: ${projection.statusText()}")
    appendLine("feature_size: ${projection?.featureSize ?: "unknown"}")
    appendLine("complete: ${projection?.completeCount ?: 0}")
    appendLine("pending: ${projection?.pendingCount ?: 0}")
    appendLine("blocked: ${projection?.blockedCount ?: 0}")
    appendLine("current_phase: ${projection?.currentPhaseId ?: "none"}")
    appendLine("resolved_branch: ${projection?.resolvedBranch ?: "none"}")
    appendLine("finalizing_agent: ${projection?.finalizingAgentId ?: "none"}")
    appendValidationGateStatus(projection)
    appendDegradedDiagnostic(projection)
    appendDecompositionTerminal(projection)
    appendPhaseStatuses(projection)
  }

private fun FeatureTaskRuntimeStatusProjection?.statusText(): String =
  if (this == null) CliPayloadStatus.NOT_FOUND else CliPayloadStatus.OK

private fun StringBuilder.appendValidationGateStatus(projection: FeatureTaskRuntimeStatusProjection?) {
  projection?.validationGateExecutionEvidence?.let { evidence ->
    appendLine("validation_gate_status: ${evidence.validationStatus}")
    appendLine("validation_gate_checks: ${evidence.checks.joinToString(",")}")
    appendLine("validation_gate_run_count: ${evidence.gateRunCount}")
    evidence.gateRuns.forEach { run ->
      appendLine(
        "validation_gate_run: cache_mode=${run.cacheMode.wireValue} " +
          "outcome=${run.outcome.wireValue} " +
          "executed_work_units=${run.executedWorkUnits} " +
          "checks=${run.executedChecks.joinToString(",")}",
      )
    }
  }
}

private fun StringBuilder.appendDegradedDiagnostic(projection: FeatureTaskRuntimeStatusProjection?) {
  projection?.degradedDiagnostic?.let { degraded ->
    appendLine("degraded_diagnostic_count: ${degraded.count}")
    appendLine("degraded_diagnostic_failure_class: ${degraded.failureClass}")
    appendLine("degraded_diagnostic_phase: ${degraded.phaseId}")
    appendLine("degraded_diagnostic_attempt: ${degraded.attempt}")
  }
}

private fun StringBuilder.appendDecompositionTerminal(projection: FeatureTaskRuntimeStatusProjection?) {
  projection?.decomposeTerminal?.let { terminal ->
    appendLine("decomposition_reason: ${terminal.reason}")
    appendLine("subtask_count: ${terminal.subtaskCount}")
    appendLine("parent_spec_path: ${terminal.parentSpecPath}")
    appendLine("decomposition_manifest_path: ${terminal.decompositionManifestPath}")
    terminal.subtaskSpecPaths.forEach { appendLine("subtask_spec_path: $it") }
    appendLine("guidance: $DECOMPOSE_GUIDANCE")
  }
}

private fun StringBuilder.appendPhaseStatuses(projection: FeatureTaskRuntimeStatusProjection?) {
  projection?.phases.orEmpty().forEach { phase ->
    appendLine(
      "phase: id=${phase.phaseId} " +
        "status=${phase.status} " +
        "attempt=${phase.attemptCount} " +
        "agent=${phase.resolvedAgentId ?: "none"} " +
        "origin=${phase.executionOrigin ?: "none"} " +
        "finished=${phase.finished}",
    )
  }
}
