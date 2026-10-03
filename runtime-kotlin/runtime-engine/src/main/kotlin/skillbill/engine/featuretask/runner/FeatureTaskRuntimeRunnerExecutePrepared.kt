package skillbill.engine.featuretask.runner

import me.tatarka.inject.annotations.Inject
import skillbill.engine.featuretask.lifecycle.continuation.FeatureTaskRuntimeGoalContinuationRecorder
import skillbill.engine.featuretask.lifecycle.continuation.isGoalContinuationRun
import skillbill.engine.featuretask.lifecycle.continuation.reconcileRemediationBaseCoherence
import skillbill.engine.featuretask.lifecycle.continuation.remediationBaseCoherenceBlockedReport
import skillbill.engine.featuretask.model.core.FeatureTaskRuntimeRunReport
import skillbill.engine.featuretask.model.core.FeatureTaskRuntimeRunRequest
import skillbill.engine.featuretask.model.subtask.RemediationBaseBlocked
import skillbill.engine.featuretask.model.subtask.RemediationBaseCoherent
import skillbill.engine.featuretask.phase.core.FeatureTaskPhaseSettlementService
import skillbill.engine.featuretask.phase.record.FeatureTaskRuntimePhaseRecorder
import skillbill.engine.featuretask.prepare.FeatureTaskRuntimeSpecGate
import skillbill.engine.featuretask.runloop.core.FeatureTaskRuntimeRunLoopDrive
import skillbill.engine.featuretask.runloop.core.FeatureTaskRuntimeRunLoopEntry
import skillbill.engine.featuretask.runloop.core.FeatureTaskRuntimeRunLoopSession
import skillbill.engine.featuretask.runloop.core.slotStepVerdictRule
import skillbill.engine.featuretask.runloop.durable.DurablePhaseRunCheckpoints
import skillbill.engine.featuretask.runloop.durable.DurablePhaseRunGoal
import skillbill.engine.featuretask.runloop.durable.DurablePhaseRunSettlements
import skillbill.engine.featuretask.runloop.durable.FeatureTaskRuntimeRunLoopDurableLaunch
import skillbill.engine.featuretask.runloop.durable.FeatureTaskRuntimeRunLoopDurableState
import skillbill.engine.featuretask.runloop.observability.FeatureTaskRuntimeRunObservability
import skillbill.engine.featuretask.runloop.state.FeatureTaskRuntimeRunState
import skillbill.engine.featuretask.slot.PhaseStrategyLookup
import skillbill.error.shellcontent.FeatureTaskRuntimeOperatorDecisionRejectedError
import skillbill.ports.diagnostics.RuntimeDiagnostics
import skillbill.ports.workflow.gitops.WorkflowGitOperations
import skillbill.workflow.decomposition.model.SpecSource
import skillbill.workflow.model.WorkflowStepStatus
import skillbill.workflow.model.workflowStepStatus
import skillbill.workflow.taskruntime.model.skeleton.PhaseSlot
import skillbill.workflow.taskruntime.model.skeleton.ResolvedPhaseExecutionPlan
import java.time.Clock

@Inject
class FeatureTaskRuntimeRunnerExecutePrepared(
  private val recorder: FeatureTaskRuntimePhaseRecorder,
  private val goalContinuationRecorder: FeatureTaskRuntimeGoalContinuationRecorder,
  private val strategies: PhaseStrategyLookup,
  private val clock: Clock,
  private val gitOperations: WorkflowGitOperations,
  private val specGate: FeatureTaskRuntimeSpecGate,
  private val runLoopEntry: FeatureTaskRuntimeRunLoopEntry,
  private val launchOutcomes: FeatureTaskRuntimeLaunchOutcomes,
  private val phaseSettlementService: FeatureTaskPhaseSettlementService,
  private val durableLaunch: FeatureTaskRuntimeRunLoopDurableLaunch,
) {
  internal fun driveExecutePreparedRunLoop(
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
        state,
        session,
        observability,
        specSource,
        executionPlan,
        strategies,
        DurablePhaseRunGoal(goalContinuationRecorder),
        DurablePhaseRunSettlements(phaseSettlementService),
        DurablePhaseRunCheckpoints(gitOperations),
        durableLaunch,
        clock,
      )
    val context = runLoopEntry.context(runRequest, runState)
    if (isGoalContinuationRun(runRequest)) {
      when (
        val remediation =
          goalContinuationRecorder.reconcileRemediationBaseCoherence(
            workflowId = runRequest.workflowId,
            gitOperations = gitOperations,
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

  internal fun createExecutePreparedRunState(
    runRequest: FeatureTaskRuntimeRunRequest,
    executionPlan: ResolvedPhaseExecutionPlan,
    diagnostics: RuntimeDiagnostics,
  ): FeatureTaskRuntimeRunState =
    FeatureTaskRuntimeRunState(
      initialRecords = recorder.loadPhaseRecords(runRequest.workflowId).orEmpty(),
      transitions = executionPlan.traversal,
      durableInitialLedger = recorder.loadPhaseLedger(runRequest.workflowId).orEmpty(),
      initialReviewGeneration = recorder.reconcileReviewGeneration(runRequest.workflowId),
      stepVerdictRule = slotStepVerdictRule(strategies, executionPlan, diagnostics),
      resumeRulesFn = strategies.resumeRules(executionPlan),
    )

  internal fun finalizeExecutePreparedRunReport(
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
      launchOutcomes.persistGoalContinuationOutcome(
        runRequest,
        report,
        commitStepId,
      )
    specGate.finalizeSingleSpecOnTerminal(
      runRequest,
      terminalReport,
      specSource,
      { launchOutcomes.finalizingAgentId(runRequest) },
    )
    return terminalReport
  }
}
