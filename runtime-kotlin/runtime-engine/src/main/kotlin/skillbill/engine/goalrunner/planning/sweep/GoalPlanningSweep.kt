package skillbill.engine.goalrunner.planning.sweep

import me.tatarka.inject.annotations.Inject
import skillbill.engine.featuretask.model.core.FeatureTaskRuntimeRunReport
import skillbill.engine.featuretask.runloop.core.FeatureTaskRuntimeRunLoopEntry
import skillbill.engine.featuretask.runloop.core.slotStepVerdictRule
import skillbill.engine.featuretask.runloop.core.strategySelectionFacts
import skillbill.engine.featuretask.runloop.state.FeatureTaskRuntimeRunState
import skillbill.engine.featuretask.slot.PhaseStrategyLookup
import skillbill.engine.goalplanning.GoalPlanningPreparationCheckpoint
import skillbill.engine.goalrunner.model.GoalRunnerManifestState
import skillbill.engine.goalrunner.model.GoalRunnerRunRequest
import skillbill.engine.goalrunner.planning.attempt.GoalPlanningPhaseAttemptGate
import skillbill.engine.goalrunner.planning.context.GoalPlanningSharedContextPacket
import skillbill.engine.goalrunner.planning.context.GoalPlanningSharedPreplanProduction
import skillbill.engine.goalrunner.planning.context.GoalPlanningSharedPreplanSettlement
import skillbill.engine.goalrunner.planning.model.GoalPlanningSharedContext
import skillbill.engine.goalrunner.planning.model.GoalPlanningSweepOutcome
import skillbill.engine.goalrunner.planning.outcome.GoalPlanningSubtaskPlanProduction
import skillbill.engine.goalrunner.planning.outcome.preSweepStopped
import skillbill.engine.goalrunner.planning.outcome.preparationStateReadReason
import skillbill.engine.goalrunner.planning.outcome.sharedContextReason
import skillbill.engine.goalrunner.planning.remedies.goalPlanningMissingSharedContextPacketStopReason
import skillbill.engine.goalrunner.planning.remedies.goalPlanningRemedySubtaskId
import skillbill.engine.goalrunner.planning.state.GoalPlanningPhaseRunState
import skillbill.engine.goalrunner.planning.state.GoalPlanningRunFacts
import skillbill.engine.goalrunner.planning.state.GoalPlanningRunProgress
import skillbill.engine.goalrunner.planning.state.GoalPlanningRunScope
import skillbill.ports.diagnostics.RuntimeDiagnostics
import skillbill.ports.goalrunner.model.GoalPlanningIdentity
import skillbill.ports.goalrunner.model.SharedGoalPreplanCheckpoint
import skillbill.ports.repository.RepositoryEnclosingRootPort
import skillbill.ports.workflow.decomposition.DecompositionManifestStore
import skillbill.workflow.model.FeatureTaskExecutionIdentityPolicy
import java.time.Clock

fun interface GoalPlanningSweep {
  fun prepare(
    state: GoalRunnerManifestState,
    request: GoalRunnerRunRequest,
  ): GoalPlanningSweepOutcome
}

@Inject
class DefaultGoalPlanningSweep(
  private val sharedPreplanProduction: GoalPlanningSharedPreplanProduction,
  private val sharedPreplanSettlement: GoalPlanningSharedPreplanSettlement,
  private val planProduction: GoalPlanningSubtaskPlanProduction,
  private val attemptGate: GoalPlanningPhaseAttemptGate,
  private val checkpoint: GoalPlanningPreparationCheckpoint,
  private val repositoryEnclosingRootPort: RepositoryEnclosingRootPort,
  private val phaseStrategies: PhaseStrategyLookup,
  private val runLoopEntry: FeatureTaskRuntimeRunLoopEntry,
  private val clock: Clock,
  private val diagnostics: RuntimeDiagnostics,
  private val manifestFileStore: DecompositionManifestStore,
) : GoalPlanningSweep {
  override fun prepare(
    state: GoalRunnerManifestState,
    request: GoalRunnerRunRequest,
  ): GoalPlanningSweepOutcome {
    val identity =
      GoalPlanningIdentity(
        state.parentWorkflowId,
        FeatureTaskExecutionIdentityPolicy.canonicalIssueKey(state.manifest.issueKey),
        repositoryEnclosingRootPort.repositoryIdentity(request.repoRoot),
      )
    val existingShared =
      runCatching {
        sharedPreplanProduction.findAdmittedSharedPreplan(identity)
      }
        .getOrElse { error ->
          return preSweepStopped(request, preparationStateReadReason(error, request.issueKey, 0))
        }
    val recoveredPacket = existingShared?.let(sharedPreplanProduction::planningPacketFrom)
    if (existingShared != null && recoveredPacket == null) {
      return preSweepStopped(
        request,
        goalPlanningMissingSharedContextPacketStopReason(
          request.issueKey,
          goalPlanningRemedySubtaskId(state.manifest.subtasks),
        ),
      )
    }
    val gathered =
      runCatching { sharedPreplanProduction.gatherSharedContext(state, request, recoveredPacket) }
        .getOrElse { error -> return preSweepStopped(request, sharedContextReason(error)) }
    return continueAfterSharedContext(state, request, identity, existingShared, gathered)
  }

  private fun continueAfterSharedContext(
    state: GoalRunnerManifestState,
    request: GoalRunnerRunRequest,
    identity: GoalPlanningIdentity,
    existingShared: SharedGoalPreplanCheckpoint?,
    shared: GoalPlanningSharedContext,
  ): GoalPlanningSweepOutcome {
    val activeSubtasks =
      state.manifest.subtasks.filter {
        it.id in GoalPlanningSharedContextPacket.includedSubtaskIds(shared.planningPacket)
      }
    val planning =
      GoalPlanningRunProgress(
        sharedPreplanSettlement,
        planProduction,
        attemptGate,
        checkpoint,
        repositoryEnclosingRootPort,
        manifestFileStore,
        GoalPlanningRunScope(state, request, identity, existingShared, shared, activeSubtasks),
      )
    val facts = GoalPlanningRunFacts(shared, request)
    val selection = strategySelectionFacts(facts)
    val executionPlan = phaseStrategies.executionPlan(selection)
    val progress =
      FeatureTaskRuntimeRunState(
        initialRecords = emptyMap(),
        transitions = executionPlan.traversal,
        stepVerdictRule = slotStepVerdictRule(phaseStrategies, executionPlan, diagnostics),
        resumeRulesFn = phaseStrategies.resumeRules(executionPlan),
      )
    val runState =
      GoalPlanningPhaseRunState(
        facts = facts,
        progress = progress,
        planning = planning,
        strategies = phaseStrategies,
        executionPlan = executionPlan,
        clock = clock,
        diagnostics = diagnostics,
        specSource = shared.specSource,
      )
    val report = runLoopEntry.run(runLoopEntry.context(facts, runState))
    return when (report) {
      is FeatureTaskRuntimeRunReport.Blocked -> planning.outcome(report.blockedReason, report.lastIncompletePhase)
      is FeatureTaskRuntimeRunReport.Paused -> planning.outcome(report.pauseReason, report.pausedPhase)
      else -> planning.outcome(null, null)
    }
  }
}
