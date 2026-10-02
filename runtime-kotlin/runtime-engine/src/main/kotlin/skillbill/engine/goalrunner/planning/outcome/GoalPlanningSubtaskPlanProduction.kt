package skillbill.engine.goalrunner.planning.outcome

import skillbill.engine.featuretask.slot.state.RequiredPhaseWrite
import skillbill.engine.goalplanning.readStoredPlanningRecord
import skillbill.engine.goalrunner.execution.core.ProduceMissingPlansArgs
import skillbill.engine.goalrunner.planning.attempt.producePhase
import skillbill.engine.goalrunner.planning.model.GoalPlanningLaunch
import skillbill.engine.goalrunner.planning.model.GoalPlanningPhaseContext
import skillbill.engine.goalrunner.planning.model.GoalPlanningPhaseProduction
import skillbill.engine.goalrunner.planning.model.GoalPlanningProduceAttemptArgs
import skillbill.engine.goalrunner.planning.model.GoalPlanningSharedContext
import skillbill.engine.goalrunner.planning.model.GoalPlanningSweepOutcome
import skillbill.engine.goalrunner.planning.sweep.DefaultGoalPlanningSweep
import skillbill.engine.goalrunner.planning.sweep.GoalPlanningSweepConstants
import skillbill.ports.goalrunner.model.GoalPlanningIdentity
import skillbill.ports.goalrunner.model.GoalSubtaskPlanCheckpoint
import skillbill.ports.goalrunner.model.GovernedGoalSubtaskDescriptor
import skillbill.ports.goalrunner.planning.model.GoalPlanningResolvedBoundaryBodies
import skillbill.text.sha256HexUtf8
import skillbill.workflow.decomposition.model.DecompositionSubtask
import skillbill.workflow.model.DecompositionStatus
import skillbill.workflow.model.decompositionStatus
import skillbill.workflow.taskruntime.model.handoff.assembly.FeatureTaskRuntimePhaseOutput
import java.nio.file.Path

/** How producing one subtask plan ended: planned and checkpointed, stopped, or stopped by a rejected required write. */
internal sealed interface SubtaskPlanProduction {
  data object Planned : SubtaskPlanProduction

  data class Stopped(val outcome: GoalPlanningSweepOutcome.Stopped) : SubtaskPlanProduction

  data class RequiredWriteRejected(val rejection: RequiredPhaseWrite.Rejected) : SubtaskPlanProduction
}

internal fun DefaultGoalPlanningSweep.producePlan(
  args: ProduceMissingPlansArgs,
  subtask: DecompositionSubtask,
  descriptor: GovernedGoalSubtaskDescriptor,
  launch: GoalPlanningLaunch,
): SubtaskPlanProduction {
  val shared = args.shared
  val request = args.request
  val preplanPayload = args.sharedCheckpoint.preplanPayload
  val resolvedSpecPath =
    resolvedSubSpecPath(shared.repoRoot, subtask.specPath, repositoryEnclosingRootPort)
      ?: return SubtaskPlanProduction.Stopped(
        stopped(shared, subtask.id, unresolvedSpecReason(subtask), GoalPlanningSweepConstants.PHASE_PLAN),
      )
  val (runInvariants, snapshot) =
    runCatching {
      invariantsSource.read(resolvedSpecPath) to snapshotSubSpecs(shared, subtask, resolvedSpecPath)
    }.getOrElse { error ->
      return SubtaskPlanProduction.Stopped(
        stopped(shared, subtask.id, invariantReadReason(subtask, error), GoalPlanningSweepConstants.PHASE_PLAN),
      )
    }
  val preplanPhaseId = GoalPlanningSweepConstants.PHASE_PREPLAN
  val preplanOutput =
    FeatureTaskRuntimePhaseOutput(
      preplanPhaseId,
      1,
      preplanPayload,
      readStoredPlanningRecord(preplanPayload, preplanPhaseId, shared.parentWorkflowId),
    )
  val planProduction =
    producePhase(
      GoalPlanningProduceAttemptArgs(
        phase =
          GoalPlanningPhaseContext(
            shared = shared,
            request = request,
            subtask = subtask,
            runInvariants = runInvariants,
            phaseId = GoalPlanningSweepConstants.PHASE_PLAN,
            launch = launch,
            outputSink = request.outputSink,
          ),
        recordedOutputs = listOf(preplanOutput),
        resolvedBodies = GoalPlanningResolvedBoundaryBodies(),
      ),
    )
  if (planProduction is GoalPlanningPhaseProduction.Stopped) {
    return SubtaskPlanProduction.Stopped(planProduction.outcome)
  }
  if (planProduction is GoalPlanningPhaseProduction.RequiredWriteRejected) {
    return SubtaskPlanProduction.RequiredWriteRejected(planProduction.rejection)
  }
  val captured = planProduction as GoalPlanningPhaseProduction.Captured
  return checkpointProducedPlan(args, subtask, descriptor, resolvedSpecPath to snapshot, captured.payload)
    ?.let { SubtaskPlanProduction.Stopped(it) }
    ?: SubtaskPlanProduction.Planned
}

private fun DefaultGoalPlanningSweep.checkpointProducedPlan(
  args: ProduceMissingPlansArgs,
  subtask: DecompositionSubtask,
  descriptor: GovernedGoalSubtaskDescriptor,
  launchedSpec: Pair<Path, GoalPlanningSubSpecSnapshot>,
  capturedPayload: String,
): GoalPlanningSweepOutcome.Stopped? {
  val shared = args.shared
  val (launchedSpecPath, snapshot) = launchedSpec
  val persistedSpec =
    admitPersistedSubSpec(shared, subtask, launchedSpecPath, snapshot, args.startedPlanIds).getOrElse { error ->
      return stopped(shared, subtask.id, error.message.orEmpty(), GoalPlanningSweepConstants.PHASE_PLAN)
    }
  val planPayload = proseRecordPayload(GoalPlanningSweepConstants.PHASE_PLAN, capturedPayload)
  val record =
    GoalSubtaskPlanCheckpoint(
      identity = GoalPlanningIdentity(shared.parentWorkflowId, shared.normalizedIssueKey, shared.repositoryIdentity),
      subtaskId = subtask.id,
      manifestOrder = descriptor.manifestOrder,
      governedSubSpecPath = descriptor.governedSubSpecPath,
      subSpecHash = sha256HexUtf8(persistedSpec),
      provenance = args.provenance,
      payloadSha256 = sha256HexUtf8(planPayload),
      planPayload = planPayload,
    )
  return runCatching { checkpoint.recheckpointSubtaskPlan(record) }.fold(
    onSuccess = { null },
    onFailure = { error ->
      stopped(
        shared,
        subtask.id,
        persistenceReason(subtask, error),
        GoalPlanningSweepConstants.PHASE_PLAN,
      )
    },
  )
}

internal fun DefaultGoalPlanningSweep.descriptor(
  shared: GoalPlanningSharedContext,
  subtask: DecompositionSubtask,
  order: Int,
): GovernedGoalSubtaskDescriptor {
  val path =
    resolvedSubSpecPath(shared.repoRoot, subtask.specPath, repositoryEnclosingRootPort)
      ?: error(unresolvedSpecReason(subtask))
  val governedPath = shared.repoRoot.relativize(path).joinToString("/")
  val identity = GoalPlanningIdentity(shared.parentWorkflowId, shared.normalizedIssueKey, shared.repositoryIdentity)
  val recovered =
    checkpoint.findStoredSubtaskPlan(
      identity,
      subtask.id,
      governedPath,
    )
  val subSpecHash =
    when {
      recovered != null && subtask.status.decompositionStatus() == DecompositionStatus.COMPLETE -> recovered.subSpecHash
      manifestFileStore.isRegularFile(path) -> sha256HexUtf8(manifestFileStore.readText(path))
      else -> error(unresolvedSpecReason(subtask))
    }
  return GovernedGoalSubtaskDescriptor(
    subtask.id,
    order,
    governedPath,
    subSpecHash,
  )
}
