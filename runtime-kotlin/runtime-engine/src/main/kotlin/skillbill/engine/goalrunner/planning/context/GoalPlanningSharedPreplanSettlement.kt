package skillbill.engine.goalrunner.planning.context

import me.tatarka.inject.annotations.Inject
import skillbill.application.rethrowIfCooperativeCancellationOrInterruption
import skillbill.contracts.workflow.goal.GOAL_PLANNING_PREPARATION_SCHEMA_ID
import skillbill.engine.featuretask.slot.state.RequiredPhaseWrite
import skillbill.engine.goalplanning.GoalPlanningPreparationCheckpoint
import skillbill.engine.goalrunner.model.GoalRunnerManifestState
import skillbill.engine.goalrunner.planning.model.GoalPlanningSharedContext
import skillbill.engine.goalrunner.planning.model.GoalPlanningSweepOutcome
import skillbill.engine.goalrunner.planning.model.RefreshStaleSharedPreplanArgs
import skillbill.engine.goalrunner.planning.model.SharedPreplanSettlementArgs
import skillbill.engine.goalrunner.planning.model.StaleSharedPreplanSettlementArgs
import skillbill.engine.goalrunner.planning.outcome.preSweepStopped
import skillbill.engine.goalrunner.planning.outcome.preparationStateReadReason
import skillbill.engine.goalrunner.planning.outcome.stopped
import skillbill.engine.goalrunner.planning.recovery.GoalPlanningProvenanceRecoverability
import skillbill.engine.goalrunner.planning.recovery.GoalPlanningRefreshLiveness
import skillbill.engine.goalrunner.planning.recovery.classifyGoalPlanningProvenanceRecoverability
import skillbill.engine.goalrunner.planning.recovery.preplanProsePromptHash
import skillbill.engine.goalrunner.planning.recovery.preplanProseValueHash
import skillbill.engine.goalrunner.planning.recovery.refuseRefreshReason
import skillbill.engine.goalrunner.planning.sweep.GoalPlanningSweepConstants
import skillbill.goalrunner.planning.cascadeEligiblePlanSubtaskIds
import skillbill.ports.goalrunner.model.GoalPlanningContractProvenance
import skillbill.ports.goalrunner.model.SharedGoalPreplanCheckpoint
import skillbill.ports.goalrunner.planning.GoalPlanningContextDiscovery
import skillbill.ports.repository.RepositoryEnclosingRootPort
import skillbill.ports.workflow.decomposition.DecompositionManifestStore

internal sealed class SharedPreplanSettlement {
  class Ready(
    val provenance: GoalPlanningContractProvenance,
    val checkpoint: SharedGoalPreplanCheckpoint,
    val shared: GoalPlanningSharedContext,
  ) : SharedPreplanSettlement()

  class Halt(val outcome: GoalPlanningSweepOutcome.Stopped) : SharedPreplanSettlement()

  class RequiredWriteRejected(val rejection: RequiredPhaseWrite.Rejected) : SharedPreplanSettlement()
}

/** The result of refreshing a stale shared preplan: the refreshed checkpoint, a stop, or a rejected required write. */
internal sealed interface SharedPreplanRefresh {
  data class Refreshed(
    val provenance: GoalPlanningContractProvenance,
    val checkpoint: SharedGoalPreplanCheckpoint,
  ) : SharedPreplanRefresh

  data class Stopped(val outcome: GoalPlanningSweepOutcome.Stopped) : SharedPreplanRefresh

  data class RequiredWriteRejected(val rejection: RequiredPhaseWrite.Rejected) : SharedPreplanRefresh

  /** The refresh was refused; [reason] is the operator-facing text the halted sweep reports. */
  data class Refused(val reason: String) : SharedPreplanRefresh
}

@Inject
class GoalPlanningSharedPreplanSettlement(
  private val checkpoint: GoalPlanningPreparationCheckpoint,
  private val sharedPreplanProduction: GoalPlanningSharedPreplanProduction,
  private val contextDiscovery: GoalPlanningContextDiscovery,
  private val manifestFileStore: DecompositionManifestStore,
  private val repositoryEnclosingRootPort: RepositoryEnclosingRootPort,
  private val refreshLiveness: GoalPlanningRefreshLiveness,
) {
  internal fun settleSharedPreplan(args: SharedPreplanSettlementArgs): SharedPreplanSettlement {
    val working = args.shared
    return when (
      val recoverability = classifyRecoverability(args.existingShared, args.currentProvenance, working)
    ) {
      is GoalPlanningProvenanceRecoverability.Irrecoverable ->
        SharedPreplanSettlement.Halt(incompatibleProvenance(working, recoverability.recoveryKind))
      is GoalPlanningProvenanceRecoverability.Reuse ->
        args.existingShared
          ?.let { existing -> SharedPreplanSettlement.Ready(recoverability.provenance, existing, working) }
          ?: settleProducedSharedPreplan(args, recoverability.provenance)
      is GoalPlanningProvenanceRecoverability.StaleValid ->
        settleStaleValidSharedPreplan(
          StaleSharedPreplanSettlementArgs(
            existingShared = requireNotNull(args.existingShared),
            currentProvenance = args.currentProvenance,
            shared = working,
            state = args.state,
            request = args.request,
            identity = args.identity,
            refreshedThisPrepare = false,
            launch = args.launch,
          ),
        )
    }
  }

  private fun settleProducedSharedPreplan(
    args: SharedPreplanSettlementArgs,
    provenance: GoalPlanningContractProvenance,
  ): SharedPreplanSettlement {
    val working = args.shared
    val production =
      sharedPreplanProduction.produceSharedPreplan(working, args.request, provenance, args.launch).getOrElse { error ->
        return SharedPreplanSettlement.Halt(
          stopped(working, 0, error.message.orEmpty(), GoalPlanningSweepConstants.PHASE_PREPLAN),
        )
      }
    return when (production) {
      is SharedPreplanProduction.Produced -> SharedPreplanSettlement.Ready(provenance, production.checkpoint, working)
      is SharedPreplanProduction.Stopped -> SharedPreplanSettlement.Halt(production.outcome)
      is SharedPreplanProduction.RequiredWriteRejected ->
        SharedPreplanSettlement.RequiredWriteRejected(production.rejection)
    }
  }

  private inline fun SharedPreplanRefresh.settle(
    working: GoalPlanningSharedContext,
    onRefreshed: (SharedPreplanRefresh.Refreshed) -> SharedPreplanSettlement,
  ): SharedPreplanSettlement =
    when (this) {
      is SharedPreplanRefresh.Refreshed -> onRefreshed(this)
      is SharedPreplanRefresh.Stopped -> SharedPreplanSettlement.Halt(outcome)
      is SharedPreplanRefresh.RequiredWriteRejected -> SharedPreplanSettlement.RequiredWriteRejected(rejection)
      is SharedPreplanRefresh.Refused ->
        SharedPreplanSettlement.Halt(stopped(working, 0, reason, GoalPlanningSweepConstants.PHASE_PREPLAN))
    }

  internal fun settleStaleValidSharedPreplan(args: StaleSharedPreplanSettlementArgs): SharedPreplanSettlement {
    val working = args.shared
    val refresh =
      refreshStaleSharedPreplan(
        RefreshStaleSharedPreplanArgs(
          existing = args.existingShared,
          shared = working,
          state = args.state,
          request = args.request,
          currentProvenance = args.currentProvenance,
          refreshedThisPrepare = args.refreshedThisPrepare,
          launch = args.launch,
        ),
      ).getOrElse { error ->
        return SharedPreplanSettlement.Halt(refreshHaltOutcome(working, error))
      }
    return refresh.settle(working) { first ->
      when (val loaded = loadSharedPreplanAfterRefresh(args, first)) {
        is SharedPreplanAfterRefresh.Halt -> SharedPreplanSettlement.Halt(loaded.outcome)
        is SharedPreplanAfterRefresh.Ready -> {
          val afterPacket = sharedPreplanProduction.planningPacketFrom(loaded.checkpoint) ?: working.planningPacket
          reclassifyAfterStaleRefresh(
            StaleRefreshReclassifyArgs(
              settlement = args,
              working = working.copy(planningPacket = afterPacket),
              afterRefresh = loaded.checkpoint,
              alreadyRefreshed = true,
            ),
          )
        }
      }
    }
  }

  private fun refreshHaltOutcome(
    working: GoalPlanningSharedContext,
    error: Throwable,
  ): GoalPlanningSweepOutcome.Stopped =
    stopped(working, 0, error.message.orEmpty(), GoalPlanningSweepConstants.PHASE_PREPLAN)

  private sealed interface SharedPreplanAfterRefresh {
    class Ready(val checkpoint: SharedGoalPreplanCheckpoint) : SharedPreplanAfterRefresh

    class Halt(val outcome: GoalPlanningSweepOutcome.Stopped) : SharedPreplanAfterRefresh
  }

  private fun loadSharedPreplanAfterRefresh(
    args: StaleSharedPreplanSettlementArgs,
    first: SharedPreplanRefresh.Refreshed,
  ): SharedPreplanAfterRefresh {
    val afterRefresh =
      runCatching {
        checkpoint.findSharedPreplan(args.identity)
      }.getOrElse { error ->
        return SharedPreplanAfterRefresh.Halt(
          preSweepStopped(
            args.request,
            preparationStateReadReason(error, args.request.issueKey, 0),
          ),
        )
      }
    return SharedPreplanAfterRefresh.Ready(afterRefresh ?: first.checkpoint)
  }

  private data class StaleRefreshReclassifyArgs(
    val settlement: StaleSharedPreplanSettlementArgs,
    val working: GoalPlanningSharedContext,
    val afterRefresh: SharedGoalPreplanCheckpoint,
    val alreadyRefreshed: Boolean,
  )

  private fun reclassifyAfterStaleRefresh(args: StaleRefreshReclassifyArgs): SharedPreplanSettlement {
    val working = args.working
    val afterRefresh = args.afterRefresh
    return when (
      val second = classifyRecoverability(afterRefresh, args.settlement.currentProvenance, working)
    ) {
      is GoalPlanningProvenanceRecoverability.Irrecoverable ->
        SharedPreplanSettlement.Halt(incompatibleProvenance(working, second.recoveryKind))
      is GoalPlanningProvenanceRecoverability.Reuse ->
        SharedPreplanSettlement.Ready(second.provenance, afterRefresh, working)
      is GoalPlanningProvenanceRecoverability.StaleValid -> {
        refreshStaleSharedPreplan(
          RefreshStaleSharedPreplanArgs(
            existing = afterRefresh,
            shared = working,
            state = args.settlement.state,
            request = args.settlement.request,
            currentProvenance = args.settlement.currentProvenance,
            refreshedThisPrepare = args.alreadyRefreshed,
            launch = args.settlement.launch,
          ),
        ).fold(
          onSuccess = { refreshed ->
            refreshed.settle(working) { SharedPreplanSettlement.Ready(it.provenance, it.checkpoint, working) }
          },
          onFailure = { error -> SharedPreplanSettlement.Halt(refreshHaltOutcome(working, error)) },
        )
      }
    }
  }

  internal fun currentProvenance(shared: GoalPlanningSharedContext) =
    GoalPlanningContractProvenance(
      shared.parentSpecHash,
      shared.decompositionManifestHash,
      GOAL_PLANNING_PREPARATION_SCHEMA_ID,
    )

  internal fun classifyRecoverability(
    existing: SharedGoalPreplanCheckpoint?,
    current: GoalPlanningContractProvenance,
    shared: GoalPlanningSharedContext,
  ): GoalPlanningProvenanceRecoverability {
    if (existing == null) {
      return GoalPlanningProvenanceRecoverability.Reuse(current)
    }
    val packetParentSpec = shared.planningPacket[GoalPlanningSharedContextPacketPayloadKeys.PARENT_SPEC] as? String
    val savedParentSpec =
      if (existing.provenance.parentSpecHash == shared.parentSpecHash) {
        shared.parentSpec
      } else {
        packetParentSpec
      }
    return classifyGoalPlanningProvenanceRecoverability(
      existing = existing,
      current = current,
      savedParentSpec = savedParentSpec,
      currentParentSpec = shared.parentSpec,
    )
  }

  internal fun refreshStaleSharedPreplan(args: RefreshStaleSharedPreplanArgs): Result<SharedPreplanRefresh> =
    runCatching {
      val existing = args.existing
      val shared = args.shared
      val state = args.state
      val currentProvenance = args.currentProvenance
      if (args.refreshedThisPrepare) {
        return@runCatching SharedPreplanRefresh.Refreshed(existing.provenance, existing)
      }
      refuseRefreshReason(shared.issueKey, refreshLiveness.resolve(state))?.let { reason ->
        return@runCatching SharedPreplanRefresh.Refused(reason)
      }
      val refreshShared =
        shared.copy(
          planningPacket =
            freshPlanningPacket(
              shared,
              state,
              contextDiscovery,
              manifestFileStore,
              repositoryEnclosingRootPort,
            ),
        )
      val produced =
        when (
          val production =
            sharedPreplanProduction.produceSharedPreplanCheckpoint(
              refreshShared,
              args.request,
              currentProvenance,
              args.launch,
            )
              .getOrElse { throw it }
        ) {
          is SharedPreplanProduction.Produced -> production.checkpoint
          is SharedPreplanProduction.Stopped -> return@runCatching SharedPreplanRefresh.Stopped(production.outcome)
          is SharedPreplanProduction.RequiredWriteRejected ->
            return@runCatching SharedPreplanRefresh.RequiredWriteRejected(production.rejection)
        }
      checkpointRefreshedPreplan(existing, produced, currentProvenance, state)
    }.onFailure { error ->
      error.rethrowIfCooperativeCancellationOrInterruption()
    }

  private fun checkpointRefreshedPreplan(
    existing: SharedGoalPreplanCheckpoint,
    produced: SharedGoalPreplanCheckpoint,
    currentProvenance: GoalPlanningContractProvenance,
    state: GoalRunnerManifestState,
  ): SharedPreplanRefresh.Refreshed {
    val savedValueHash = preplanProseValueHash(existing.preplanPayload)
    val newValueHash = preplanProseValueHash(produced.preplanPayload)
    val savedPromptHash = preplanProsePromptHash(existing.preplanPayload)
    val newPromptHash = preplanProsePromptHash(produced.preplanPayload)
    return if (savedValueHash == newValueHash && savedPromptHash == newPromptHash) {
      checkpoint.sharedPreplanRefresh.advanceSharedPreplanProvenance(
        identity = existing.identity,
        expectedPayloadSha256 = existing.payloadSha256,
        provenance = currentProvenance,
      )
      val advanced = existing.copy(provenance = currentProvenance)
      SharedPreplanRefresh.Refreshed(currentProvenance, advanced)
    } else {
      val cascadeIds =
        cascadeEligiblePlanSubtaskIds(
          plannedIds =
            checkpoint.sharedPreplanRefresh.listPreparedPlanSubtaskIds(
              state.parentWorkflowId,
            ),
          subtasks = state.manifest.subtasks,
        )
      val replaced =
        checkpoint.sharedPreplanRefresh.replaceSharedPreplanForRefresh(
          checkpoint = produced,
          expectedPayloadSha256 = existing.payloadSha256,
          cascadePlanSubtaskIds = cascadeIds,
        )
      SharedPreplanRefresh.Refreshed(currentProvenance, replaced)
    }
  }
}
