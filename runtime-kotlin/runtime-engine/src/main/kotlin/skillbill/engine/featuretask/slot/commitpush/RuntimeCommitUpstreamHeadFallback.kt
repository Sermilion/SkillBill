package skillbill.engine.featuretask.slot.commitpush

import skillbill.contracts.JsonCodec
import skillbill.contracts.SharedPayloadKeys
import skillbill.contracts.workflow.featuretask.FEATURE_TASK_RUNTIME_CONTRACT_VERSION
import skillbill.contracts.workflow.identity.task.FeatureTaskRuntimeMeasuredFactKeys
import skillbill.engine.diagnostics.RuntimeDiagnosticsBestEffortWarning
import skillbill.engine.featuretask.runloop.core.FeatureTaskRuntimeRunLoopContext
import skillbill.engine.featuretask.runloop.core.PhaseRun
import skillbill.engine.featuretask.runner.missingUpstream
import skillbill.engine.featuretask.slot.PhaseStepHooks
import skillbill.engine.featuretask.validation.FeatureTaskRuntimeBuildGateCoordinator
import skillbill.ports.workflow.gitops.model.WorkflowGitOperationResult
import skillbill.workflow.model.WorkflowStepStatus
import skillbill.workflow.model.workflowStepStatus
import skillbill.workflow.taskruntime.model.handoff.task.FeatureTaskRuntimePhaseOutput
import skillbill.workflow.taskruntime.model.phase.requireAcceptedOutput
import skillbill.workflow.taskruntime.model.validation.FeatureTaskRuntimeValidationGateRunRecord
import skillbill.workflow.taskruntime.phase.task.FeatureTaskRuntimePhaseWorkflowDefinition

private const val HEAD_SETTLED_HISTORY_SUMMARY = "Boundary history settled from repository HEAD."

internal object RuntimeCommitUpstreamHeadFallback : PhaseStepHooks {
  override fun reconcileBeforeLaunch(
    run: PhaseRun,
    context: FeatureTaskRuntimeRunLoopContext,
  ) {
    reconcile(run, context)
    clearUpstreamPersistedBlockIfRecovered(run, context)
  }

  private fun reconcile(
    run: PhaseRun,
    context: FeatureTaskRuntimeRunLoopContext,
  ) {
    val headSha =
      context.phaseGates.gitOperations.headCommitSha(context.request.repoRoot)
        .takeIf { it is WorkflowGitOperationResult.Ok }
        ?.value
        ?.trim()
        ?.takeIf(String::isNotBlank)
        ?: return
    val missing = missingUpstream(run.declaration, context.state.outputs()) ?: return
    if (missing.isEmpty()) return
    missing.forEach { phaseId ->
      reconcilePhase(phaseId, headSha, context)
    }
  }

  private fun clearUpstreamPersistedBlockIfRecovered(
    run: PhaseRun,
    context: FeatureTaskRuntimeRunLoopContext,
  ) {
    val reason = context.state.persistedBlockedReason(run.phaseId) ?: return
    if (!reason.contains("requires upstream output", ignoreCase = true)) return
    if (missingUpstream(run.declaration, context.state.outputs())?.isNotEmpty() == true) return
    context.state.clearPersistedBlock(run.phaseId)
  }

  private fun reconcilePhase(
    phaseId: String,
    headSha: String,
    context: FeatureTaskRuntimeRunLoopContext,
  ) {
    val state = context.state
    val record = state.recordFor(phaseId)
    val attemptCount = record?.attemptCount?.coerceAtLeast(1) ?: 1
    val output =
      phaseId
        .takeIf(::supportsHeadFallback)
        ?.takeIf { state.outputFor(it) == null }
        ?.takeIf {
          record == null || record.status.workflowStepStatus() == WorkflowStepStatus.COMPLETED
        }
        ?.let { syntheticOutput(it, headSha, attemptCount) }
    val accepted =
      output?.let {
        runCatching {
          state.outputValidator.validatePhaseOutput(it.payload, phaseId).requireAcceptedOutput(phaseId)
        }.getOrNull()
      }
    if (output != null && accepted != null) {
      state.recordCompleted(
        FeatureTaskRuntimePhaseOutput(
          phaseId = phaseId,
          iteration = attemptCount,
          payload = accepted.normalizedOutput.canonicalJson,
          normalizedOutput = accepted.normalizedOutput,
        ),
      )
      RuntimeDiagnosticsBestEffortWarning.record(
        context.diagnostics,
        "seam=FeatureTaskRuntimeCommitPushUpstreamHeadFallback.reconcile " +
          "value_used='repository HEAD $headSha' " +
          "value_expected=a settled durable output for phase '$phaseId' " +
          "cause=commit_push resumed while '$phaseId' was completed without output; " +
          "the runtime synthesized a HEAD-backed receipt so finalisation can proceed",
      )
    }
  }

  private fun supportsHeadFallback(phaseId: String): Boolean =
    phaseId == FeatureTaskRuntimePhaseWorkflowDefinition.PHASE_BUILD ||
      phaseId == FeatureTaskRuntimePhaseWorkflowDefinition.PHASE_WRITE_HISTORY

  private fun syntheticOutput(
    phaseId: String,
    headSha: String,
    attemptCount: Int,
  ): FeatureTaskRuntimePhaseOutput? =
    when (phaseId) {
      FeatureTaskRuntimePhaseWorkflowDefinition.PHASE_BUILD ->
        FeatureTaskRuntimeBuildGateCoordinator.runtimeOwnedBuildOutput(
          repositoryCheckpoint = headSha,
          measurements =
            listOf(
              FeatureTaskRuntimeValidationGateRunRecord(
                durationMs = 0,
                outcome = "passed",
                cacheMode = "cache_eligible",
                executedWorkUnits = 0,
                executedChecks = emptyList(),
              ),
            ),
        ).let { built ->
          FeatureTaskRuntimePhaseOutput(
            phaseId = built.phaseId,
            iteration = attemptCount.coerceAtLeast(1),
            payload = built.payload,
          )
        }
      FeatureTaskRuntimePhaseWorkflowDefinition.PHASE_WRITE_HISTORY -> {
        val payload =
          JsonCodec.mapToJsonString(
            mapOf(
              SharedPayloadKeys.CONTRACT_VERSION to FEATURE_TASK_RUNTIME_CONTRACT_VERSION,
              SharedPayloadKeys.PHASE_ID to FeatureTaskRuntimePhaseWorkflowDefinition.PHASE_WRITE_HISTORY,
              SharedPayloadKeys.STATUS to "completed",
              SharedPayloadKeys.SUMMARY to HEAD_SETTLED_HISTORY_SUMMARY,
              SharedPayloadKeys.PRODUCED_OUTPUTS to
                mapOf(
                  SharedPayloadKeys.VALUE to HEAD_SETTLED_HISTORY_SUMMARY,
                  FeatureTaskRuntimeMeasuredFactKeys.MEASURED_FACTS to
                    mapOf(
                      FeatureTaskRuntimeMeasuredFactKeys.CHANGED_PATHS to FeatureTaskRuntimeMeasuredFactKeys.UNKNOWN,
                      FeatureTaskRuntimeMeasuredFactKeys.HISTORY_WRITTEN to FeatureTaskRuntimeMeasuredFactKeys.UNKNOWN,
                      FeatureTaskRuntimeMeasuredFactKeys.DECISIONS_RECORDED to
                        FeatureTaskRuntimeMeasuredFactKeys.UNKNOWN,
                    ),
                ),
            ),
          )
        FeatureTaskRuntimePhaseOutput(
          phaseId = FeatureTaskRuntimePhaseWorkflowDefinition.PHASE_WRITE_HISTORY,
          iteration = attemptCount.coerceAtLeast(1),
          payload = payload,
        )
      }
      else -> null
    }
}
