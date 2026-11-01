package skillbill.engine.featuretask.runner

import me.tatarka.inject.annotations.Inject
import skillbill.engine.featuretask.lifecycle.continuation.FeatureTaskRuntimeGoalContinuationRecorder
import skillbill.engine.featuretask.lifecycle.continuation.isGoalContinuationRun
import skillbill.engine.featuretask.lifecycle.continuation.reconcileRemediationBaseCoherence
import skillbill.engine.featuretask.lifecycle.continuation.remediationBaseCoherenceBlockedReport
import skillbill.engine.featuretask.model.audit.AcceptanceCriterionRepairStepRoles
import skillbill.engine.featuretask.model.core.FeatureTaskRuntimeRunReport
import skillbill.engine.featuretask.model.core.FeatureTaskRuntimeRunRequest
import skillbill.engine.featuretask.model.subtask.RemediationBaseBlocked
import skillbill.engine.featuretask.model.subtask.RemediationBaseCoherent
import skillbill.engine.featuretask.persist.acceptance.restoreAcceptanceRepairResumeEvidence
import skillbill.engine.featuretask.phase.core.FeatureTaskPhaseSettlementService
import skillbill.engine.featuretask.phase.core.MonitorRepairAccounting
import skillbill.engine.featuretask.phase.record.FeatureTaskRuntimePhaseRecorder
import skillbill.engine.featuretask.prepare.FeatureTaskRuntimeSpecGate
import skillbill.engine.featuretask.runloop.core.FeatureTaskRuntimeRunLoopDrive
import skillbill.engine.featuretask.runloop.core.FeatureTaskRuntimeRunLoopEntry
import skillbill.engine.featuretask.runloop.core.slotStepVerdictRule
import skillbill.engine.featuretask.runloop.observability.FeatureTaskRuntimeRunObservability
import skillbill.engine.featuretask.runloop.state.FeatureTaskRuntimeRunState
import skillbill.engine.featuretask.slot.AcceptanceRepairCapability
import skillbill.engine.featuretask.slot.MonitorRepairCapability
import skillbill.engine.featuretask.slot.PhaseStrategyLookup
import skillbill.error.shellcontent.featureTaskRuntimeOperatorDecisionRejected
import skillbill.ports.diagnostics.RuntimeDiagnostics
import skillbill.ports.workflow.gitops.WorkflowGitOperations
import skillbill.workflow.decomposition.model.SpecSource
import skillbill.workflow.taskruntime.model.skeleton.PhaseSlot
import skillbill.workflow.taskruntime.model.skeleton.ResolvedPhaseExecutionPlan

@Inject
public class FeatureTaskRuntimeRunnerExecutePrepared(
  private val recorder: FeatureTaskRuntimePhaseRecorder,
  private val goalContinuationRecorder: FeatureTaskRuntimeGoalContinuationRecorder,
  private val strategies: PhaseStrategyLookup,
  private val gitOperations: WorkflowGitOperations,
  private val specGate: FeatureTaskRuntimeSpecGate,
  private val runLoopEntry: FeatureTaskRuntimeRunLoopEntry,
  private val launchOutcomes: FeatureTaskRuntimeLaunchOutcomes,
  private val phaseSettlementService: FeatureTaskPhaseSettlementService,
  private val durableRuns: FeatureTaskRuntimeDurableRunFactory,
) {
  internal fun acceptanceRepairSteps(plan: ResolvedPhaseExecutionPlan): AcceptanceCriterionRepairStepRoles? {
    val audit = plan.selectedStrategies.singleOrNull { it.slot == PhaseSlot.AUDIT } ?: return null
    return audit.steps.mapNotNull {
      (strategies.selectedOwnerOf(it, plan) as? AcceptanceRepairCapability)?.acceptanceRepairSteps
    }.distinct().singleOrNull()
  }

  internal fun driveExecutePreparedRunLoop(
    runRequest: FeatureTaskRuntimeRunRequest,
    specSource: SpecSource,
    executionPlan: ResolvedPhaseExecutionPlan,
    observability: FeatureTaskRuntimeRunObservability,
    state: FeatureTaskRuntimeRunState,
  ): FeatureTaskRuntimeRunReport {
    val runState = durableRuns.create(runRequest, specSource, executionPlan, observability, state)
    val context = runLoopEntry.context(runRequest, runState)
    if (isGoalContinuationRun(runRequest)) {
      when (
        val remediation =
          goalContinuationRecorder.reconcileRemediationBaseCoherence(
            workflowId = runRequest.workflowId,
            gitOperations = gitOperations,
            repoRoot = runRequest.repoRoot,
            admitted = requireNotNull(runRequest.admittedExecution),
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
          throw featureTaskRuntimeOperatorDecisionRejected(runRequest.workflowId, decision.wireValue, rejection)
        }
      }
    }
  }

  internal fun createExecutePreparedRunState(
    runRequest: FeatureTaskRuntimeRunRequest,
    executionPlan: ResolvedPhaseExecutionPlan,
    diagnostics: RuntimeDiagnostics,
  ): FeatureTaskRuntimeRunState {
    val records = recorder.loadPhaseRecords(runRequest.workflowId).orEmpty()
    val ledger = recorder.loadPhaseLedger(runRequest.workflowId).orEmpty()
    val roles =
      executionPlan.selectedStrategies.mapNotNull {
        (strategies.selectedOwnerOf(it.entryStep, executionPlan) as? MonitorRepairCapability)?.monitorRepairSteps
      }.singleOrNull()
    val history =
      roles?.let {
        MonitorRepairAccounting(
          it,
          gitOperations,
          recorder::producerOutput,
          phaseSettlementService::findEnvelope,
          diagnostics,
        ).restore(
          runRequest,
          records,
          ledger,
          runRequest.admittedExecution?.recordedMonitorSemantics,
          executionPlan,
        )
      } ?: MonitorRepairAccounting.RestoredHistory(emptyList(), emptyMap(), false)
    return FeatureTaskRuntimeRunState(
      initialRecords = records,
      transitions = executionPlan.phaseTraversal(),
      durableInitialLedger = ledger,
      initialRetainedOutputs =
        history.outputs +
          listOfNotNull(
            phaseSettlementService.restoreAcceptanceRepairResumeEvidence(
              runRequest,
              acceptanceRepairSteps(executionPlan),
              records,
              ledger,
              diagnostics,
            ),
          ),
      initialMonitorRepairFailures = history.failedRepairs,
      initialMonitorFailureEvidenceUnavailable = history.evidenceUnavailable,
      initialReviewGeneration = recorder.reconcileReviewGeneration(runRequest.workflowId),
      stepVerdictRule = slotStepVerdictRule(strategies, executionPlan, diagnostics),
      resumeRulesFn = strategies.resumeRules(executionPlan),
      workflowId = runRequest.workflowId,
      initialQaRepairState = recorder.loadQaRepairState(runRequest.workflowId),
    )
  }

  internal fun finalizeExecutePreparedRunReport(
    runRequest: FeatureTaskRuntimeRunRequest,
    report: FeatureTaskRuntimeRunReport,
    specSource: SpecSource,
    executionPlan: ResolvedPhaseExecutionPlan,
  ): FeatureTaskRuntimeRunReport {
    val terminalReport =
      runRequest.goalContinuation?.let {
        val commitStepId =
          executionPlan.selectedStrategies
            .first { strategy -> strategy.slot == PhaseSlot.COMMIT_PUSH }
            .entryStep
        launchOutcomes.persistGoalContinuationOutcome(runRequest, report, commitStepId)
      } ?: report
    specGate.finalizeSingleSpecOnTerminal(
      runRequest,
      terminalReport,
      specSource,
      { launchOutcomes.finalizingAgentId(runRequest) },
    )
    return terminalReport
  }
}
