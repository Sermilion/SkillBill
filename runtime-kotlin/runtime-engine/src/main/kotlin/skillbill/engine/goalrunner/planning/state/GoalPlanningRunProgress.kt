package skillbill.engine.goalrunner.planning.state

import skillbill.application.rethrowIfCooperativeCancellationOrInterruption
import skillbill.engine.featuretask.runloop.core.PhaseOutcome
import skillbill.engine.featuretask.slot.state.PhaseFanOutUnits
import skillbill.engine.featuretask.slot.state.RequiredPhaseWrite
import skillbill.engine.goalplanning.GoalPlanningPreparationCheckpoint
import skillbill.engine.goalrunner.execution.core.ProduceMissingPlansArgs
import skillbill.engine.goalrunner.model.GoalRunnerManifestState
import skillbill.engine.goalrunner.model.GoalRunnerRunRequest
import skillbill.engine.goalrunner.planning.attempt.GoalPlanningPhaseAttemptGate
import skillbill.engine.goalrunner.planning.context.GoalPlanningSharedPreplanSettlement
import skillbill.engine.goalrunner.planning.context.SharedPreplanSettlement
import skillbill.engine.goalrunner.planning.model.GoalPlanningLaunch
import skillbill.engine.goalrunner.planning.model.GoalPlanningRecoveryProgress
import skillbill.engine.goalrunner.planning.model.GoalPlanningSharedContext
import skillbill.engine.goalrunner.planning.model.GoalPlanningSweepOutcome
import skillbill.engine.goalrunner.planning.model.SharedPreplanSettlementArgs
import skillbill.engine.goalrunner.planning.outcome.GoalPlanningSubtaskPlanProduction
import skillbill.engine.goalrunner.planning.outcome.SubtaskPlanProduction
import skillbill.engine.goalrunner.planning.outcome.governedSubSpecReady
import skillbill.engine.goalrunner.planning.outcome.noSuchSubtaskReason
import skillbill.engine.goalrunner.planning.outcome.preparationStateReadReason
import skillbill.engine.goalrunner.planning.outcome.recoverySubtaskId
import skillbill.engine.goalrunner.planning.outcome.resolvedSubSpecPath
import skillbill.engine.goalrunner.planning.outcome.stopped
import skillbill.engine.goalrunner.planning.outcome.unexpectedPlanningFailureReason
import skillbill.engine.goalrunner.planning.recovery.GoalPlanningRecoveryKind
import skillbill.engine.goalrunner.planning.remedies.goalPlanningPreparationStateReadStopReason
import skillbill.engine.goalrunner.planning.sweep.GoalPlanningSweepConstants
import skillbill.error.shellcontent.IncompatibleGoalPlanningPreparationRecoveryError
import skillbill.ports.agentrun.model.AgentRunOutputSink
import skillbill.ports.goalrunner.model.GoalPlanningIdentity
import skillbill.ports.goalrunner.model.GovernedGoalSubtaskDescriptor
import skillbill.ports.goalrunner.model.SharedGoalPreplanCheckpoint
import skillbill.ports.repository.RepositoryEnclosingRootPort
import skillbill.ports.workflow.decomposition.DecompositionManifestStore
import skillbill.workflow.decomposition.model.DecompositionSubtask
import skillbill.workflow.model.DecompositionStatus
import skillbill.workflow.model.decompositionStatus
import skillbill.workflow.taskruntime.model.core.FeatureTaskRuntimeWorkflowArtifactMap
import skillbill.workflow.taskruntime.model.handoff.assembly.FeatureTaskRuntimePhaseOutput
import skillbill.workflow.taskruntime.model.handoff.task.NormalizedFeatureTaskRuntimePhaseOutput
import java.util.concurrent.ConcurrentHashMap

internal data class GoalPlanningRunScope(
  val state: GoalRunnerManifestState,
  val request: GoalRunnerRunRequest,
  val identity: GoalPlanningIdentity,
  val existingShared: SharedGoalPreplanCheckpoint?,
  val shared: GoalPlanningSharedContext,
  val activeSubtasks: List<DecompositionSubtask>,
)

internal class GoalPlanningRunProgress(
  private val sharedPreplanSettlement: GoalPlanningSharedPreplanSettlement,
  private val planProduction: GoalPlanningSubtaskPlanProduction,
  private val attemptGate: GoalPlanningPhaseAttemptGate,
  private val checkpoint: GoalPlanningPreparationCheckpoint,
  private val repositoryEnclosingRootPort: RepositoryEnclosingRootPort,
  private val manifestFileStore: DecompositionManifestStore,
  private val scope: GoalPlanningRunScope,
) {
  private var ready: SharedPreplanSettlement.Ready? = null
  private var descriptors: List<GovernedGoalSubtaskDescriptor> = emptyList()
  private val unitStops = ConcurrentHashMap<Int, GoalPlanningSweepOutcome.Stopped>()
  private val startedPlanIds: MutableSet<Int> = ConcurrentHashMap.newKeySet()
  private val persistedSubSpecHashes = ConcurrentHashMap<Int, String>()
  private var stop: GoalPlanningSweepOutcome.Stopped? = null

  val outputSink: AgentRunOutputSink = scope.request.outputSink

  fun settlePreplan(
    launch: GoalPlanningLaunch,
    onRequiredWriteRejected: (RequiredPhaseWrite.Rejected) -> PhaseOutcome,
  ): PhaseOutcome =
    when (
      val settled =
        sharedPreplanSettlement.settleSharedPreplan(
          SharedPreplanSettlementArgs(
            existingShared = scope.existingShared,
            currentProvenance = sharedPreplanSettlement.currentProvenance(scope.shared),
            shared = scope.shared,
            state = scope.state,
            request = scope.request,
            identity = scope.identity,
            launch = launch,
          ),
        )
    ) {
      is SharedPreplanSettlement.Halt -> halt(settled.outcome)
      is SharedPreplanSettlement.Ready -> {
        ready = settled
        completed(GoalPlanningSweepConstants.PHASE_PREPLAN)
      }
      is SharedPreplanSettlement.RequiredWriteRejected -> onRequiredWriteRejected(settled.rejection)
    }

  fun producePlan(
    unitId: Int,
    unitOutputSink: AgentRunOutputSink,
    launch: GoalPlanningLaunch,
    onRequiredWriteRejected: (RequiredPhaseWrite.Rejected) -> PhaseOutcome,
  ): PhaseOutcome {
    val settled = requireNotNull(ready)
    val subtask =
      scope.activeSubtasks.firstOrNull { it.id == unitId }
        ?: return unitStopped(unitId, stopped(settled.shared, unitId, noSuchSubtaskReason(unitId)))
    startedPlanIds.add(unitId)
    val args =
      ProduceMissingPlansArgs(
        shared = settled.shared,
        request = scope.request.copy(outputSink = unitOutputSink),
        identity = scope.identity,
        provenance = settled.provenance,
        sharedCheckpoint = settled.checkpoint,
        activeSubtasks = scope.activeSubtasks,
        startedPlanIds = startedPlanIds,
      )
    val descriptor = descriptors.single { it.subtaskId == unitId }
    when (val production = planProduction.producePlan(args, subtask, descriptor, launch)) {
      is SubtaskPlanProduction.Stopped -> return unitStopped(unitId, production.outcome)
      is SubtaskPlanProduction.RequiredWriteRejected -> return onRequiredWriteRejected(production.rejection)
      SubtaskPlanProduction.Planned -> Unit
    }
    checkpoint.findStoredSubtaskPlan(scope.identity, unitId, descriptor.governedSubSpecPath)
      ?.let { persistedSubSpecHashes[unitId] = it.subSpecHash }
    return completed(GoalPlanningSweepConstants.PHASE_PLAN)
  }

  fun pendingUnits(): PhaseFanOutUnits {
    val settled = requireNotNull(ready)
    if (scope.activeSubtasks.isEmpty()) return PhaseFanOutUnits.Pending(emptyList())
    descriptors =
      runCatching {
        scope.activeSubtasks.mapIndexed { order, subtask -> planProduction.descriptor(settled.shared, subtask, order) }
      }.getOrElse { error ->
        return PhaseFanOutUnits.Stopped(
          halt(
            stopped(
              settled.shared,
              0,
              "Goal planning governed subtask provenance could not be computed: ${error.message.orEmpty()}",
            ),
          ),
        )
      }
    return runCatching { recoveredPendingUnits(settled) }.getOrElse { error ->
      error.rethrowIfCooperativeCancellationOrInterruption()
      val subtaskId = recoverySubtaskId(error)
      val phaseId =
        GoalPlanningSweepConstants.PHASE_PLAN.takeIf { subtaskId != 0 }
          ?: GoalPlanningSweepConstants.PHASE_PREPLAN
      PhaseFanOutUnits.Stopped(
        halt(
          stopped(
            settled.shared,
            subtaskId,
            preparationStateReadReason(error, settled.shared.issueKey, subtaskId),
            phaseId,
          ),
        ),
      )
    }
  }

  private fun recoveredPendingUnits(settled: SharedPreplanSettlement.Ready): PhaseFanOutUnits =
    when (val recovery = checkpoint.recoveryProgress(scope.identity, descriptors, settled.provenance)) {
      is GoalPlanningRecoveryProgress.IncompletePlan ->
        PhaseFanOutUnits.Stopped(
          halt(
            stopped(
              settled.shared,
              0,
              goalPlanningPreparationStateReadStopReason(
                recovery.reason,
                recovery.subtaskId,
                settled.shared.issueKey,
                0,
                GoalPlanningRecoveryKind.SCOPED_REPLAN,
              ),
              GoalPlanningSweepConstants.PHASE_PREPLAN,
            ),
          ),
        )
      is GoalPlanningRecoveryProgress.Ready -> {
        requireStoredPlansReady(settled.shared, recovery.progress.missingSubtaskIds)
        PhaseFanOutUnits.Pending(recovery.progress.missingSubtaskIds)
      }
    }

  fun pauseBefore(unitId: Int): PhaseOutcome? =
    attemptGate.planningPauseOutcome(requireNotNull(ready).shared, unitId, GoalPlanningSweepConstants.PHASE_PLAN)
      ?.let { paused -> halt(paused.outcome) }

  fun settleUnit(
    unitId: Int,
    result: Result<PhaseOutcome>,
  ): PhaseOutcome? {
    val outcome =
      result.getOrElse { error ->
        return halt(
          stopped(
            requireNotNull(ready).shared,
            unitId,
            unexpectedPlanningFailureReason(GoalPlanningSweepConstants.PHASE_PLAN, error),
            GoalPlanningSweepConstants.PHASE_PLAN,
          ),
        )
      }
    if (outcome is PhaseOutcome.Completed) return null
    unitStops[unitId]?.let { stopped -> stop = stopped }
    return outcome
  }

  fun completed(): PhaseOutcome = completed(GoalPlanningSweepConstants.PHASE_PLAN)

  fun outcome(
    blockedReason: String?,
    blockedPhase: String?,
  ): GoalPlanningSweepOutcome {
    stop?.let { return it }
    val settled = ready
    return if (blockedReason == null && settled != null) {
      GoalPlanningSweepOutcome.PreparedAll(
        scope.identity,
        settled.provenance,
        descriptors.map { descriptor ->
          persistedSubSpecHashes[descriptor.subtaskId]?.let { descriptor.copy(subSpecHash = it) } ?: descriptor
        },
      )
    } else {
      stopped(
        scope.shared,
        0,
        blockedReason.orEmpty(),
        blockedPhase ?: GoalPlanningSweepConstants.PHASE_PREPLAN,
      )
    }
  }

  private fun requireStoredPlansReady(
    shared: GoalPlanningSharedContext,
    missingSubtaskIds: List<Int>,
  ) {
    scope.activeSubtasks
      .filter { it.id !in missingSubtaskIds && it.status.decompositionStatus() != DecompositionStatus.COMPLETE }
      .forEach { subtask ->
        val path = resolvedSubSpecPath(shared.repoRoot, subtask.specPath, repositoryEnclosingRootPort)
        val ready =
          path != null &&
            manifestFileStore.isRegularFile(path) &&
            governedSubSpecReady(manifestFileStore.readText(path))
        if (!ready) {
          throw IncompatibleGoalPlanningPreparationRecoveryError(
            scope.identity.parentGoalWorkflowId,
            subtask.id,
            "stored plan's governed sub-spec has no ready implementation details; replan this subtask",
          )
        }
      }
  }

  private fun halt(outcome: GoalPlanningSweepOutcome.Stopped): PhaseOutcome {
    stop = outcome
    return PhaseOutcome.blocked(outcome.blockedReason)
  }

  private fun unitStopped(
    unitId: Int,
    outcome: GoalPlanningSweepOutcome.Stopped,
  ): PhaseOutcome {
    unitStops[unitId] = outcome
    return PhaseOutcome.blocked(outcome.blockedReason)
  }

  private fun completed(stepId: String): PhaseOutcome =
    PhaseOutcome.completed(FeatureTaskRuntimePhaseOutput(stepId, 1, SETTLED_STEP_PAYLOAD, SETTLED_STEP_OUTPUT))
}

private const val SETTLED_STEP_PAYLOAD = "{}"

private val SETTLED_STEP_OUTPUT =
  NormalizedFeatureTaskRuntimePhaseOutput.fromRecordMap(
    FeatureTaskRuntimeWorkflowArtifactMap.from(emptyMap<String, Any?>()),
  )
