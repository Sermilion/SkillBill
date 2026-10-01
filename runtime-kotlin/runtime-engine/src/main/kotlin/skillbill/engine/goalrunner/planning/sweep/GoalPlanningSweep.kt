package skillbill.engine.goalrunner.planning.sweep

import me.tatarka.inject.annotations.Inject
import skillbill.engine.featuretask.model.core.FeatureTaskRuntimeRunReport
import skillbill.engine.featuretask.runloop.core.FeatureTaskRuntimeRunLoopContext
import skillbill.engine.featuretask.runloop.core.slotStepVerdictRule
import skillbill.engine.featuretask.runloop.core.strategySelectionFacts
import skillbill.engine.featuretask.runloop.state.FeatureTaskRuntimeRunState
import skillbill.engine.featuretask.slot.attempt.PhaseAttemptCollaborators
import skillbill.engine.goalrunner.model.GoalRunnerManifestState
import skillbill.engine.goalrunner.model.GoalRunnerRunRequest
import skillbill.engine.goalrunner.planning.context.GoalPlanningSharedContextPacket
import skillbill.engine.goalrunner.planning.context.gatherSharedContext
import skillbill.engine.goalrunner.planning.context.planningPacketFrom
import skillbill.engine.goalrunner.planning.model.GoalPlanningSharedContext
import skillbill.engine.goalrunner.planning.model.GoalPlanningSweepOutcome
import skillbill.engine.goalrunner.planning.outcome.preSweepStopped
import skillbill.engine.goalrunner.planning.outcome.preparationStateReadReason
import skillbill.engine.goalrunner.planning.outcome.sharedContextReason
import skillbill.engine.goalrunner.planning.remedies.goalPlanningMissingSharedContextPacketStopReason
import skillbill.engine.goalrunner.planning.remedies.goalPlanningRemedySubtaskId
import skillbill.engine.goalrunner.planning.state.GoalPlanningPhaseRunState
import skillbill.engine.goalrunner.planning.state.GoalPlanningRunFacts
import skillbill.engine.goalrunner.planning.state.GoalPlanningRunProgress
import skillbill.engine.goalrunner.planning.state.GoalPlanningRunScope
import skillbill.ports.goalrunner.model.GoalPlanningIdentity
import skillbill.ports.goalrunner.model.SharedGoalPreplanCheckpoint
import skillbill.ports.repository.RepositoryEnclosingRootPort

fun interface GoalPlanningSweep {
  fun prepare(
    state: GoalRunnerManifestState,
    request: GoalRunnerRunRequest,
  ): GoalPlanningSweepOutcome
}

@Inject
class DefaultGoalPlanningSweep(
  checkpointBoundaries: GoalPlanningSweepCheckpointBoundaries,
  launchBoundaries: GoalPlanningSweepLaunchBoundaries,
  val repositoryEnclosingRootPort: RepositoryEnclosingRootPort,
) : GoalPlanningSweep {
  val checkpoint = checkpointBoundaries.checkpoint
  val invariantsSource = checkpointBoundaries.invariantsSource
  val manifestFileStore = checkpointBoundaries.manifestFileStore
  val contextDiscovery = checkpointBoundaries.contextDiscovery
  val manifestStore = launchBoundaries.manifestStore
  val planningAttemptRecorder = launchBoundaries.planningAttemptRecorder
  val planningRejectionRecorder = launchBoundaries.planningRejectionRecorder
  val timingPort = launchBoundaries.timingPort
  val burstSchedule = launchBoundaries.burstSchedule
  val refreshLiveness = launchBoundaries.refreshLiveness
  val phaseStrategies = launchBoundaries.phaseStrategies
  private val runLoopEntry = launchBoundaries.runLoopEntry
  private val clock = launchBoundaries.clock
  private val diagnostics = launchBoundaries.diagnostics

  override fun prepare(
    state: GoalRunnerManifestState,
    request: GoalRunnerRunRequest,
  ): GoalPlanningSweepOutcome {
    val identity =
      GoalPlanningIdentity(
        state.parentWorkflowId,
        state.manifest.issueKey.trim().uppercase(),
        repositoryEnclosingRootPort.repositoryIdentity(request.repoRoot),
      )
    val existingShared =
      runCatching { checkpoint.findSharedPreplan(identity) }
        .getOrElse { error ->
          return preSweepStopped(request, preparationStateReadReason(error, request.issueKey, 0))
        }
    val recoveredPacket = existingShared?.let(::planningPacketFrom)
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
      runCatching { gatherSharedContext(this, state, request, recoveredPacket) }
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
        this,
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
        collaborators = PhaseAttemptCollaborators(clock, diagnostics),
        specSource = shared.specSource,
      )
    val report = runLoopEntry.run(FeatureTaskRuntimeRunLoopContext(facts, runState, phaseStrategies))
    return when (report) {
      is FeatureTaskRuntimeRunReport.Blocked -> planning.outcome(report.blockedReason, report.lastIncompletePhase)
      is FeatureTaskRuntimeRunReport.Paused -> planning.outcome(report.pauseReason, report.pausedPhase)
      else -> planning.outcome(null, null)
    }
  }
}
