package skillbill.engine.goalrunner.planning.sweep

import me.tatarka.inject.annotations.Inject
import skillbill.engine.goalrunner.execution.core.ProduceMissingPlansArgs
import skillbill.engine.goalrunner.model.GoalRunnerRunRequest
import skillbill.engine.goalrunner.planning.context.GoalPlanningSharedContextPacket
import skillbill.engine.goalrunner.planning.context.SharedPreplanSettlement
import skillbill.engine.goalrunner.planning.context.currentProvenance
import skillbill.engine.goalrunner.planning.context.gatherSharedContext
import skillbill.engine.goalrunner.planning.context.planningPacketFrom
import skillbill.engine.goalrunner.planning.context.settleSharedPreplan
import skillbill.engine.goalrunner.planning.model.GoalPlanningSharedContext
import skillbill.engine.goalrunner.planning.model.GoalPlanningSweepOutcome
import skillbill.engine.goalrunner.planning.model.SharedPreplanSettlementArgs
import skillbill.engine.goalrunner.planning.outcome.preSweepStopped
import skillbill.engine.goalrunner.planning.outcome.preparationStateReadReason
import skillbill.engine.goalrunner.planning.outcome.produceMissingPlans
import skillbill.engine.goalrunner.planning.outcome.sharedContextReason
import skillbill.engine.goalrunner.planning.remedies.goalPlanningMissingSharedContextPacketStopReason
import skillbill.engine.goalrunner.planning.remedies.goalPlanningRemedySubtaskId
import skillbill.ports.goalrunner.model.GoalPlanningIdentity
import skillbill.ports.goalrunner.model.SharedGoalPreplanCheckpoint
import skillbill.ports.goalrunner.runner.model.GoalRunnerManifestState
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
  val outputValidator = checkpointBoundaries.outputValidator
  val invariantsSource = checkpointBoundaries.invariantsSource
  val manifestFileStore = checkpointBoundaries.manifestFileStore
  val contextDiscovery = checkpointBoundaries.contextDiscovery
  val planningProjectionValidator = checkpointBoundaries.planningProjectionValidator
  val subtaskLauncher = launchBoundaries.subtaskLauncher
  val manifestStore = launchBoundaries.manifestStore
  val planningAttemptRecorder = launchBoundaries.planningAttemptRecorder
  val planningRejectionRecorder = launchBoundaries.planningRejectionRecorder
  val timingPort = launchBoundaries.timingPort
  val fanOutPort = launchBoundaries.fanOutPort
  val burstSchedule = launchBoundaries.burstSchedule
  val refreshLiveness = launchBoundaries.refreshLiveness
  val phaseStrategies = launchBoundaries.phaseStrategies

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
    gathered: GoalPlanningSharedContext,
  ): GoalPlanningSweepOutcome {
    var shared = gathered
    val activeSubtasks =
      state.manifest.subtasks.filter {
        it.id in GoalPlanningSharedContextPacket.includedSubtaskIds(shared.planningPacket)
      }
    return when (
      val settled =
        settleSharedPreplan(
          SharedPreplanSettlementArgs(
            existingShared = existingShared,
            currentProvenance = currentProvenance(shared),
            shared = shared,
            state = state,
            request = request,
            identity = identity,
          ),
        )
    ) {
      is SharedPreplanSettlement.Halt -> settled.outcome
      is SharedPreplanSettlement.Ready -> {
        shared = settled.shared
        if (activeSubtasks.isEmpty()) {
          GoalPlanningSweepOutcome.PreparedAll(identity, settled.provenance)
        } else {
          produceMissingPlans(
            ProduceMissingPlansArgs(
              shared = shared,
              request = request,
              identity = identity,
              provenance = settled.provenance,
              sharedCheckpoint = settled.checkpoint,
              activeSubtasks = activeSubtasks,
            ),
          )
        }
      }
    }
  }
}
