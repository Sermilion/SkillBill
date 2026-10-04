package skillbill.engine.featuretask.runloop.checkpoint

import skillbill.engine.diagnostics.RuntimeDiagnosticsBestEffortWarning
import skillbill.engine.featuretask.runloop.core.PhaseRun
import skillbill.engine.featuretask.runloop.state.coupledRunTransitions
import skillbill.engine.featuretask.runner.missingUpstream
import skillbill.engine.featuretask.slot.attempt.PhaseAttemptLaunchRuntimeContext
import skillbill.ports.workflow.gitops.model.WorkflowGitOperationResult
import skillbill.workflow.model.WorkflowStepStatus
import skillbill.workflow.model.workflowStepStatus
import skillbill.workflow.taskruntime.model.handoff.assembly.FeatureTaskRuntimePhaseOutput
import skillbill.workflow.taskruntime.model.handoff.task.NormalizedFeatureTaskRuntimePhaseOutput

internal object RuntimeCommitUpstreamHeadRecovery {
  fun reconcileBeforeLaunch(
    run: PhaseRun,
    context: PhaseAttemptLaunchRuntimeContext,
    upstreamReceipt: (String, Int) -> FeatureTaskRuntimePhaseOutput?,
  ) {
    reconcile(run, context, upstreamReceipt)
    clearUpstreamPersistedBlockIfRecovered(run, context)
  }

  private fun reconcile(
    run: PhaseRun,
    context: PhaseAttemptLaunchRuntimeContext,
    upstreamReceipt: (String, Int) -> FeatureTaskRuntimePhaseOutput?,
  ) {
    val headSha =
      context.gitOperations
        .headCommitSha(context.request.repoRoot)
        .takeIf { it is WorkflowGitOperationResult.Ok }
        ?.value
        ?.trim()
        ?.takeIf(String::isNotBlank)
        ?: return
    val missing = missingUpstream(run.declaration, context.progress.outputs()) ?: return
    if (missing.isEmpty()) return
    missing.forEach { phaseId ->
      reconcilePhase(phaseId, headSha, context, upstreamReceipt)
    }
  }

  private fun clearUpstreamPersistedBlockIfRecovered(
    run: PhaseRun,
    context: PhaseAttemptLaunchRuntimeContext,
  ) {
    val reason = context.progress.phase(run.phaseId).blockedReason ?: return
    if (!reason.contains("requires upstream output", ignoreCase = true)) return
    if (missingUpstream(run.declaration, context.progress.outputs())?.isNotEmpty() == true) return
    context.coupledRunTransitions.clearPersistedBlockAfterUpstreamRecovery(run.phaseId)
  }

  private fun reconcilePhase(
    phaseId: String,
    headSha: String,
    context: PhaseAttemptLaunchRuntimeContext,
    upstreamReceipt: (String, Int) -> FeatureTaskRuntimePhaseOutput?,
  ) {
    val state = context.progress
    val record = state.phase(phaseId).record
    val attemptCount = record?.attemptCount?.coerceAtLeast(1) ?: 1
    val output =
      phaseId
        .takeIf { state.phase(it).output == null }
        ?.takeIf {
          record == null || record.status.workflowStepStatus() == WorkflowStepStatus.COMPLETED
        }?.let { upstreamReceipt(it, attemptCount) }
    val accepted =
      output?.let {
        runCatching {
          NormalizedFeatureTaskRuntimePhaseOutput.fromEnvelopeText(it.payload, phaseId)
        }.getOrNull()
      }
    if (output != null && accepted != null) {
      context.coupledRunTransitions.recordSyntheticUpstreamCompletion(
        FeatureTaskRuntimePhaseOutput(
          phaseId = phaseId,
          iteration = attemptCount,
          payload = accepted.canonicalJson,
          normalizedOutput = accepted,
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
}
