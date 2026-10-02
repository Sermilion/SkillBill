package skillbill.engine.goalrunner.planning.context

import skillbill.application.rethrowIfCooperativeCancellationOrInterruption
import skillbill.contracts.workflow.goal.GOAL_PLANNING_PREPARATION_SCHEMA_ID
import skillbill.engine.featuretask.slot.state.RequiredPhaseWrite
import skillbill.engine.goalrunner.planning.model.GoalPlanningSharedContext
import skillbill.engine.goalrunner.planning.model.GoalPlanningSweepOutcome
import skillbill.engine.goalrunner.planning.model.RefreshStaleSharedPreplanArgs
import skillbill.engine.goalrunner.planning.model.SharedPreplanSettlementArgs
import skillbill.engine.goalrunner.planning.model.StaleSharedPreplanSettlementArgs
import skillbill.engine.goalrunner.planning.outcome.preSweepStopped
import skillbill.engine.goalrunner.planning.outcome.preparationStateReadReason
import skillbill.engine.goalrunner.planning.outcome.stopped
import skillbill.engine.goalrunner.planning.recovery.GoalPlanningProvenanceRecoverability
import skillbill.engine.goalrunner.planning.recovery.classifyGoalPlanningProvenanceRecoverability
import skillbill.engine.goalrunner.planning.recovery.preplanProsePromptHash
import skillbill.engine.goalrunner.planning.recovery.preplanProseValueHash
import skillbill.engine.goalrunner.planning.recovery.refuseRefreshReason
import skillbill.engine.goalrunner.planning.sweep.DefaultGoalPlanningSweep
import skillbill.engine.goalrunner.planning.sweep.GoalPlanningSweepConstants
import skillbill.goalrunner.planning.cascadeEligiblePlanSubtaskIds
import skillbill.ports.goalrunner.model.GoalPlanningContractProvenance
import skillbill.ports.goalrunner.model.SharedGoalPreplanCheckpoint

internal sealed class SharedPreplanSettlement {
  class Ready(
    val provenance: GoalPlanningContractProvenance,
    val checkpoint: SharedGoalPreplanCheckpoint,
    val shared: GoalPlanningSharedContext,
  ) : SharedPreplanSettlement()

  class Halt(val outcome: GoalPlanningSweepOutcome.Stopped) : SharedPreplanSettlement()

  /** A required preplan briefing or start write was rejected; the caller blocks the attempt. */
  class RequiredWriteRejected(val rejection: RequiredPhaseWrite.Rejected) : SharedPreplanSettlement()
}

/** The result of refreshing a stale shared preplan: the refreshed checkpoint, or the rejected required write. */
internal sealed interface SharedPreplanRefresh {
  data class Refreshed(
    val provenance: GoalPlanningContractProvenance,
    val checkpoint: SharedGoalPreplanCheckpoint,
  ) : SharedPreplanRefresh

  data class RequiredWriteRejected(val rejection: RequiredPhaseWrite.Rejected) : SharedPreplanRefresh
}

class RefreshRefused(val reason: String) : RuntimeException(reason)

internal fun DefaultGoalPlanningSweep.settleSharedPreplan(args: SharedPreplanSettlementArgs): SharedPreplanSettlement {
  var working = args.shared
  val (provenance, sharedCheckpoint) =
    when (
      val recoverability = classifyRecoverability(args.existingShared, args.currentProvenance, working)
    ) {
      is GoalPlanningProvenanceRecoverability.Irrecoverable ->
        return SharedPreplanSettlement.Halt(incompatibleProvenance(working, recoverability.recoveryKind))
      is GoalPlanningProvenanceRecoverability.Reuse -> {
        val settled =
          args.existingShared
            ?: when (
              val production =
                produceSharedPreplan(this, working, args.request, recoverability.provenance, args.launch)
                  .getOrElse { error ->
                    return SharedPreplanSettlement.Halt(
                      stopped(working, 0, error.message.orEmpty(), GoalPlanningSweepConstants.PHASE_PREPLAN),
                    )
                  }
            ) {
              is SharedPreplanProduction.Produced -> production.checkpoint
              is SharedPreplanProduction.RequiredWriteRejected ->
                return SharedPreplanSettlement.RequiredWriteRejected(production.rejection)
            }
        recoverability.provenance to settled
      }
      is GoalPlanningProvenanceRecoverability.StaleValid -> {
        return settleStaleValidSharedPreplan(
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
  return SharedPreplanSettlement.Ready(provenance, sharedCheckpoint, working)
}

internal fun DefaultGoalPlanningSweep.settleStaleValidSharedPreplan(
  args: StaleSharedPreplanSettlementArgs,
): SharedPreplanSettlement {
  var working = args.shared
  var alreadyRefreshed = args.refreshedThisPrepare
  val refresh =
    refreshStaleSharedPreplan(
      RefreshStaleSharedPreplanArgs(
        existing = args.existingShared,
        shared = working,
        state = args.state,
        request = args.request,
        currentProvenance = args.currentProvenance,
        refreshedThisPrepare = alreadyRefreshed,
        launch = args.launch,
      ),
    ).getOrElse { error ->
      return SharedPreplanSettlement.Halt(refreshHaltOutcome(working, error))
    }
  val first =
    when (refresh) {
      is SharedPreplanRefresh.Refreshed -> refresh
      is SharedPreplanRefresh.RequiredWriteRejected ->
        return SharedPreplanSettlement.RequiredWriteRejected(refresh.rejection)
    }
  alreadyRefreshed = true
  when (val loaded = loadSharedPreplanAfterRefresh(args, first)) {
    is SharedPreplanAfterRefresh.Halt -> return SharedPreplanSettlement.Halt(loaded.outcome)
    is SharedPreplanAfterRefresh.Ready -> {
      val afterPacket = planningPacketFrom(loaded.checkpoint) ?: working.planningPacket
      working = working.copy(planningPacket = afterPacket)
      return reclassifyAfterStaleRefresh(
        StaleRefreshReclassifyArgs(
          settlement = args,
          working = working,
          afterRefresh = loaded.checkpoint,
          alreadyRefreshed = alreadyRefreshed,
        ),
      )
    }
  }
}

private fun DefaultGoalPlanningSweep.refreshHaltOutcome(
  working: GoalPlanningSharedContext,
  error: Throwable,
): GoalPlanningSweepOutcome.Stopped =
  when (error) {
    is RefreshRefused -> stopped(working, 0, error.reason, GoalPlanningSweepConstants.PHASE_PREPLAN)
    else -> stopped(working, 0, error.message.orEmpty(), GoalPlanningSweepConstants.PHASE_PREPLAN)
  }

private sealed interface SharedPreplanAfterRefresh {
  class Ready(val checkpoint: SharedGoalPreplanCheckpoint) : SharedPreplanAfterRefresh

  class Halt(val outcome: GoalPlanningSweepOutcome.Stopped) : SharedPreplanAfterRefresh
}

private fun DefaultGoalPlanningSweep.loadSharedPreplanAfterRefresh(
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

private fun DefaultGoalPlanningSweep.reclassifyAfterStaleRefresh(
  args: StaleRefreshReclassifyArgs,
): SharedPreplanSettlement {
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
          when (refreshed) {
            is SharedPreplanRefresh.Refreshed ->
              SharedPreplanSettlement.Ready(refreshed.provenance, refreshed.checkpoint, working)
            is SharedPreplanRefresh.RequiredWriteRejected ->
              SharedPreplanSettlement.RequiredWriteRejected(refreshed.rejection)
          }
        },
        onFailure = { error -> SharedPreplanSettlement.Halt(refreshHaltOutcome(working, error)) },
      )
    }
  }
}

internal fun DefaultGoalPlanningSweep.currentProvenance(shared: GoalPlanningSharedContext) =
  GoalPlanningContractProvenance(
    shared.parentSpecHash,
    shared.decompositionManifestHash,
    GOAL_PLANNING_PREPARATION_SCHEMA_ID,
  )

internal fun DefaultGoalPlanningSweep.classifyRecoverability(
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

internal fun DefaultGoalPlanningSweep.refreshStaleSharedPreplan(
  args: RefreshStaleSharedPreplanArgs,
): Result<SharedPreplanRefresh> =
  runCatching {
    val existing = args.existing
    val shared = args.shared
    val state = args.state
    val request = args.request
    val currentProvenance = args.currentProvenance
    if (args.refreshedThisPrepare) {
      return@runCatching SharedPreplanRefresh.Refreshed(existing.provenance, existing)
    }
    refuseRefreshReason(shared.issueKey, refreshLiveness.resolve(state))?.let { reason ->
      throw RefreshRefused(reason)
    }
    val refreshShared = shared.copy(planningPacket = freshPlanningPacket(shared, state))
    val produced =
      when (
        val production =
          produceSharedPreplanCheckpoint(this, refreshShared, request, currentProvenance, args.launch)
            .getOrElse { throw it }
      ) {
        is SharedPreplanProduction.Produced -> production.checkpoint
        is SharedPreplanProduction.RequiredWriteRejected ->
          return@runCatching SharedPreplanRefresh.RequiredWriteRejected(production.rejection)
      }
    val savedValueHash = preplanProseValueHash(existing.preplanPayload)
    val newValueHash = preplanProseValueHash(produced.preplanPayload)
    val savedPromptHash = preplanProsePromptHash(existing.preplanPayload)
    val newPromptHash = preplanProsePromptHash(produced.preplanPayload)
    if (savedValueHash == newValueHash && savedPromptHash == newPromptHash) {
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
  }.onFailure { error ->
    error.rethrowIfCooperativeCancellationOrInterruption()
  }
