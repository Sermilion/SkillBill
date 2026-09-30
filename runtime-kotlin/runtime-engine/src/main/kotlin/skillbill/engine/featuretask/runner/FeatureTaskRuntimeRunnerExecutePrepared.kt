package skillbill.engine.featuretask.runner

import skillbill.application.decomposition.specSource
import skillbill.engine.featuretask.lifecycle.continuation.isGoalContinuationRun
import skillbill.engine.featuretask.lifecycle.continuation.reconcileRemediationBaseCoherence
import skillbill.engine.featuretask.lifecycle.continuation.remediationBaseCoherenceBlockedReport
import skillbill.engine.featuretask.lifecycle.core.featureTaskRuntimeAgentContext
import skillbill.engine.featuretask.model.core.FeatureTaskRuntimeCrashReconciliationResult
import skillbill.engine.featuretask.model.core.FeatureTaskRuntimeFinishedTelemetryContext
import skillbill.engine.featuretask.model.core.FeatureTaskRuntimeRunReport
import skillbill.engine.featuretask.model.core.FeatureTaskRuntimeRunRequest
import skillbill.engine.featuretask.model.subtask.RemediationBaseBlocked
import skillbill.engine.featuretask.model.subtask.RemediationBaseCoherent
import skillbill.engine.featuretask.review.core.auditGapIterationCount
import skillbill.engine.featuretask.runloop.core.FeatureTaskRuntimeRunLoopContext
import skillbill.engine.featuretask.runloop.core.FeatureTaskRuntimeRunLoopDrive
import skillbill.engine.featuretask.runloop.core.FeatureTaskRuntimeRunLoopSession
import skillbill.engine.featuretask.runloop.core.slotStepVerdictRule
import skillbill.engine.featuretask.runloop.durable.FeatureTaskRuntimeRunLoopDurableState
import skillbill.engine.featuretask.runloop.observability.FeatureTaskRuntimeRunObservability
import skillbill.engine.featuretask.runloop.state.FeatureTaskRuntimeRunState
import skillbill.error.shellcontent.FeatureTaskRuntimeOperatorDecisionRejectedError
import skillbill.workflow.decomposition.model.SpecSource
import skillbill.workflow.model.WorkflowStepStatus
import skillbill.workflow.model.workflowStepStatus
import skillbill.workflow.taskruntime.model.skeleton.PhaseSlot
import skillbill.workflow.taskruntime.model.skeleton.ResolvedPhaseExecutionPlan

internal fun FeatureTaskRuntimeRunner.buildExecutePreparedRunTelemetryContext(
  runRequest: FeatureTaskRuntimeRunRequest,
  telemetrySessionId: String,
  reconciliation: FeatureTaskRuntimeCrashReconciliationResult,
  state: FeatureTaskRuntimeRunState,
) = FeatureTaskRuntimeFinishedTelemetryContext(
  telemetrySessionId = telemetrySessionId,
  phaseOutcomes = {
    recorder
      .loadPhaseRecords(runRequest.workflowId)
      .orEmpty()
      .mapValues { (_, record) -> record.status.wireValue }
  },
  reviewFixIterationCount = { loadReviewFixIterationCount(runRequest) },
  auditGapIterationCount = { auditGapIterationCount(runRequest.workflowId) },
  agentContext = { featureTaskRuntimeAgentContext(runRequest.workflowId) },
  regenerationTelemetry = { loadRegenerationTelemetry(runRequest) },
  findingVerificationTelemetry = { loadFindingVerificationTelemetry(runRequest) },
  phaseTokenData = { serializeTokenData(state.phaseTokenView) },
  crashReconciliation = { reconciliation },
)

internal fun FeatureTaskRuntimeRunner.driveExecutePreparedRunLoop(
  runRequest: FeatureTaskRuntimeRunRequest,
  specSource: SpecSource,
  executionPlan: ResolvedPhaseExecutionPlan,
  observability: FeatureTaskRuntimeRunObservability,
  state: FeatureTaskRuntimeRunState,
): FeatureTaskRuntimeRunReport {
  val session =
    FeatureTaskRuntimeRunLoopSession(
      operatorBlockRetry =
        recorder
          .loadOperatorBlockRetry(runRequest.workflowId)
          ?.takeIf { retry ->
            state.phase(retry.phaseId).record?.status.let { status ->
              status == null || status.workflowStepStatus() == WorkflowStepStatus.PENDING
            }
          },
      initialPendingReentry = null,
    )
  val runState =
    FeatureTaskRuntimeRunLoopDurableState(
      runRequest,
      state,
      session,
      observability,
      specSource,
      executionPlan,
      this,
    )
  val context = FeatureTaskRuntimeRunLoopContext(runRequest, runState, strategies)
  if (isGoalContinuationRun(runRequest)) {
    when (
      val remediation =
        goalContinuationRecorder.reconcileRemediationBaseCoherence(
          workflowId = runRequest.workflowId,
          gitOperations = phaseGates.gitOperations,
          repoRoot = runRequest.repoRoot,
        )
    ) {
      is RemediationBaseBlocked ->
        return remediationBaseCoherenceBlockedReport(
          runRequest,
          remediation.operatorGuidance,
          executionPlan.traversal.forwardPhaseIds.first(),
        )
      is RemediationBaseCoherent -> Unit
    }
  }
  FeatureTaskRuntimeRunLoopDrive.reopenStaleSettledSteps(context)
  return runLoopEntry.run(context) { loop ->
    runRequest.operatorDecision?.let { decision ->
      loop.applyOperatorDecision()?.let { rejection ->
        throw FeatureTaskRuntimeOperatorDecisionRejectedError(runRequest.workflowId, decision.wireValue, rejection)
      }
    }
  }
}

internal fun FeatureTaskRuntimeRunner.createExecutePreparedRunState(
  runRequest: FeatureTaskRuntimeRunRequest,
  executionPlan: ResolvedPhaseExecutionPlan,
): FeatureTaskRuntimeRunState =
  FeatureTaskRuntimeRunState(
    initialRecords = recorder.loadPhaseRecords(runRequest.workflowId).orEmpty(),
    transitions = executionPlan.traversal,
    durableInitialLedger = recorder.loadPhaseLedger(runRequest.workflowId).orEmpty(),
    outputValidator = outputValidator,
    initialReviewGeneration = recorder.reconcileReviewGeneration(runRequest.workflowId),
    stepVerdictRule = slotStepVerdictRule(strategies, executionPlan, diagnostics),
    resumeRulesFn = strategies.resumeRules(executionPlan),
  )

fun FeatureTaskRuntimeRunner.finalizeExecutePreparedRunReport(
  runRequest: FeatureTaskRuntimeRunRequest,
  report: FeatureTaskRuntimeRunReport,
  specSource: SpecSource,
  executionPlan: ResolvedPhaseExecutionPlan,
): FeatureTaskRuntimeRunReport {
  val commitStepId =
    executionPlan.selectedStrategies
      .first { strategy -> strategy.slot == PhaseSlot.COMMIT_PUSH }
      .entryStep
  val terminalReport =
    persistGoalContinuationOutcome(
      runRequest,
      report,
      commitStepId,
    )
  phaseGates.specGate.finalizeSingleSpecOnTerminal(
    runRequest,
    terminalReport,
    specSource,
    { finalizingAgentId(runRequest) },
  )
  return terminalReport
}
