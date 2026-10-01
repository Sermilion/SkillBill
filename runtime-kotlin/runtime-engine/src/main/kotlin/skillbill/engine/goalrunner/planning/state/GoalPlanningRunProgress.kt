package skillbill.engine.goalrunner.planning.state

import skillbill.engine.featuretask.runloop.core.PhaseOutcome
import skillbill.engine.featuretask.slot.state.PhaseFanOutUnits
import skillbill.engine.goalrunner.execution.core.ProduceMissingPlansArgs
import skillbill.engine.goalrunner.model.GoalRunnerManifestState
import skillbill.engine.goalrunner.model.GoalRunnerRunRequest
import skillbill.engine.goalrunner.planning.attempt.planningPauseOutcome
import skillbill.engine.goalrunner.planning.context.SharedPreplanSettlement
import skillbill.engine.goalrunner.planning.context.currentProvenance
import skillbill.engine.goalrunner.planning.context.settleSharedPreplan
import skillbill.engine.goalrunner.planning.model.GoalPlanningLaunch
import skillbill.engine.goalrunner.planning.model.GoalPlanningSharedContext
import skillbill.engine.goalrunner.planning.model.GoalPlanningSweepOutcome
import skillbill.engine.goalrunner.planning.model.SharedPreplanSettlementArgs
import skillbill.engine.goalrunner.planning.outcome.descriptor
import skillbill.engine.goalrunner.planning.outcome.governedSubSpecReady
import skillbill.engine.goalrunner.planning.outcome.noSuchSubtaskReason
import skillbill.engine.goalrunner.planning.outcome.preparationStateReadReason
import skillbill.engine.goalrunner.planning.outcome.producePlan
import skillbill.engine.goalrunner.planning.outcome.recoverySubtaskId
import skillbill.engine.goalrunner.planning.outcome.resolvedSubSpecPath
import skillbill.engine.goalrunner.planning.outcome.stopped
import skillbill.engine.goalrunner.planning.outcome.unexpectedPlanningFailureReason
import skillbill.engine.goalrunner.planning.sweep.DefaultGoalPlanningSweep
import skillbill.engine.goalrunner.planning.sweep.GoalPlanningSweepConstants
import skillbill.error.shellcontent.IncompatibleGoalPlanningPreparationRecoveryError
import skillbill.ports.agentrun.model.AgentRunOutputSink
import skillbill.ports.goalrunner.model.GoalPlanningIdentity
import skillbill.ports.goalrunner.model.GovernedGoalSubtaskDescriptor
import skillbill.ports.goalrunner.model.SharedGoalPreplanCheckpoint
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
  private val sweep: DefaultGoalPlanningSweep,
  private val scope: GoalPlanningRunScope,
) {
  private var ready: SharedPreplanSettlement.Ready? = null
  private var descriptors: List<GovernedGoalSubtaskDescriptor> = emptyList()
  private val unitStops = ConcurrentHashMap<Int, GoalPlanningSweepOutcome.Stopped>()
  private val startedPlanIds: MutableSet<Int> = ConcurrentHashMap.newKeySet()
  private val persistedSubSpecHashes = ConcurrentHashMap<Int, String>()
  private var stop: GoalPlanningSweepOutcome.Stopped? = null

  val outputSink: AgentRunOutputSink = scope.request.outputSink

  fun settlePreplan(launch: GoalPlanningLaunch): PhaseOutcome =
    when (
      val settled =
        sweep.settleSharedPreplan(
          SharedPreplanSettlementArgs(
            existingShared = scope.existingShared,
            currentProvenance = sweep.currentProvenance(scope.shared),
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
    }

  fun producePlan(
    unitId: Int,
    unitOutputSink: AgentRunOutputSink,
    launch: GoalPlanningLaunch,
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
    val stoppedOutcome = sweep.producePlan(args, subtask, descriptor, launch)
    if (stoppedOutcome != null) return unitStopped(unitId, stoppedOutcome)
    sweep.checkpoint.findStoredSubtaskPlan(scope.identity, unitId, descriptor.governedSubSpecPath)
      ?.let { persistedSubSpecHashes[unitId] = it.subSpecHash }
    return completed(GoalPlanningSweepConstants.PHASE_PLAN)
  }

  fun pendingUnits(): PhaseFanOutUnits {
    val settled = requireNotNull(ready)
    if (scope.activeSubtasks.isEmpty()) return PhaseFanOutUnits.Pending(emptyList())
    descriptors =
      runCatching {
        scope.activeSubtasks.mapIndexed { order, subtask -> sweep.descriptor(settled.shared, subtask, order) }
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
    val recovery =
      runCatching {
        sweep.checkpoint.recoveryProgress(scope.identity, descriptors, settled.provenance).also { progress ->
          requireStoredPlansReady(settled.shared, progress.missingSubtaskIds)
        }
      }
    val error = recovery.exceptionOrNull() ?: return PhaseFanOutUnits.Pending(recovery.getOrThrow().missingSubtaskIds)
    val subtaskId = recoverySubtaskId(error)
    val phaseId =
      GoalPlanningSweepConstants.PHASE_PLAN.takeIf { subtaskId != 0 }
        ?: GoalPlanningSweepConstants.PHASE_PREPLAN
    return PhaseFanOutUnits.Stopped(
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

  fun pauseBefore(unitId: Int): PhaseOutcome? =
    sweep.planningPauseOutcome(requireNotNull(ready).shared, unitId, GoalPlanningSweepConstants.PHASE_PLAN)
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
        val path = resolvedSubSpecPath(shared.repoRoot, subtask.specPath, sweep.repositoryEnclosingRootPort)
        val ready =
          path != null &&
            sweep.manifestFileStore.isRegularFile(path) &&
            governedSubSpecReady(sweep.manifestFileStore.readText(path))
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
