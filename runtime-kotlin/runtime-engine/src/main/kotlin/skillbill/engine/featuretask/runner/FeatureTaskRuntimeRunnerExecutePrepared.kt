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
import skillbill.engine.featuretask.runloop.core.FeatureTaskRuntimeRunLoop
import skillbill.engine.featuretask.runloop.core.FeatureTaskRuntimeRunLoopContext
import skillbill.engine.featuretask.runloop.core.FeatureTaskRuntimeRunLoopDrive
import skillbill.engine.featuretask.runloop.core.FeatureTaskRuntimeRunLoopSession
import skillbill.engine.featuretask.runloop.core.strategySelectionFacts
import skillbill.engine.featuretask.runloop.observability.FeatureTaskRuntimeRunObservability
import skillbill.engine.featuretask.runloop.state.FeatureTaskRuntimeRunState
import skillbill.error.shellcontent.FeatureTaskRuntimeOperatorDecisionRejectedError
import skillbill.workflow.decomposition.model.SpecSource
import skillbill.workflow.model.WorkflowStepStatus
import skillbill.workflow.model.workflowStepStatus
import skillbill.workflow.taskruntime.model.core.PhaseSlot
import skillbill.workflow.taskruntime.model.phase.FeatureTaskRuntimeTransitionDeclaration

internal fun FeatureTaskRuntimeRunner.buildExecutePreparedRunTelemetryContext(
  runRequest: FeatureTaskRuntimeRunRequest,
  telemetrySessionId: String,
  reconciliation: FeatureTaskRuntimeCrashReconciliationResult,
  state: FeatureTaskRuntimeRunState,
) = FeatureTaskRuntimeFinishedTelemetryContext(
  telemetrySessionId = telemetrySessionId,
  phaseOutcomes = {
    recorder.loadPhaseRecords(runRequest.workflowId)
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

fun FeatureTaskRuntimeRunner.driveExecutePreparedRunLoop(
  runRequest: FeatureTaskRuntimeRunRequest,
  specSource: SpecSource,
  transitions: FeatureTaskRuntimeTransitionDeclaration,
  observability: FeatureTaskRuntimeRunObservability,
  state: FeatureTaskRuntimeRunState,
): FeatureTaskRuntimeRunReport {
  val context =
    FeatureTaskRuntimeRunLoopContext(
      runRequest,
      state,
      observability,
      specSource,
      transitions,
      recorder,
      goalContinuationRecorder,
      outputValidator,
      phaseGates,
      strategies,
      phaseSettlementService,
      activityStampWriter,
      worktreeEditJournalWriter,
      clock,
      diagnostics,
      FeatureTaskRuntimeRunLoopSession(
        operatorBlockRetry =
          recorder
            .loadOperatorBlockRetry(runRequest.workflowId)
            ?.takeIf { retry ->
              state.recordFor(retry.phaseId)?.status.let { status ->
                status == null || status.workflowStepStatus() == WorkflowStepStatus.PENDING
              }
            },
        initialPendingReentry = null,
      ),
    )
  FeatureTaskRuntimeRunLoopDrive.reopenStaleSettledSteps(context)
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
          PhaseSlot.PREPLAN.steps.first(),
        )
      is RemediationBaseCoherent -> Unit
    }
  }
  val loop = FeatureTaskRuntimeRunLoop(context = context)
  runRequest.operatorDecision?.let { decision ->
    loop.applyOperatorDecision()?.let { rejection ->
      throw FeatureTaskRuntimeOperatorDecisionRejectedError(runRequest.workflowId, decision.wireValue, rejection)
    }
  }
  loop.drive()
  return loop.report()
}

internal fun FeatureTaskRuntimeRunner.createExecutePreparedRunState(
  runRequest: FeatureTaskRuntimeRunRequest,
  transitions: FeatureTaskRuntimeTransitionDeclaration,
): FeatureTaskRuntimeRunState {
  val facts = strategySelectionFacts(runRequest)
  return FeatureTaskRuntimeRunState(
    initialRecords = recorder.loadPhaseRecords(runRequest.workflowId).orEmpty(),
    transitions = transitions,
    durableInitialLedger = recorder.loadPhaseLedger(runRequest.workflowId).orEmpty(),
    outputValidator = outputValidator,
    initialReviewGeneration = recorder.reconcileReviewGeneration(runRequest.workflowId),
    stepVerdictRule = { stepId ->
      stepId.takeIf { id -> PhaseSlot.entries.any { slot -> id in slot.steps } }
        ?.let { id -> strategies.strategyOrNull(id, facts)?.verdictRule(id, diagnostics) }
    },
  )
}

fun FeatureTaskRuntimeRunner.finalizeExecutePreparedRunReport(
  runRequest: FeatureTaskRuntimeRunRequest,
  report: FeatureTaskRuntimeRunReport,
  specSource: SpecSource,
): FeatureTaskRuntimeRunReport {
  val terminalReport =
    persistGoalContinuationOutcome(
      goalContinuationRecorder,
      recorder,
      phaseGates.gitOperations,
      runRequest,
      report,
      PhaseSlot.COMMIT_PUSH.steps.first(),
    )
  phaseGates.specGate.finalizeSingleSpecOnTerminal(
    runRequest,
    terminalReport,
    specSource,
    { finalizingAgentId(runRequest) },
  )
  return terminalReport
}
