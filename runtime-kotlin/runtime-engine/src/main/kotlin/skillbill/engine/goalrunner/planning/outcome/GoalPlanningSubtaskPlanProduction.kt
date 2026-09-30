package skillbill.engine.goalrunner.planning.outcome

import skillbill.engine.goalrunner.execution.core.ProduceMissingPlansArgs
import skillbill.engine.goalrunner.planning.attempt.producePhase
import skillbill.engine.goalrunner.planning.model.GoalPlanningLaunch
import skillbill.engine.goalrunner.planning.model.GoalPlanningPhaseContext
import skillbill.engine.goalrunner.planning.model.GoalPlanningPhaseProduction
import skillbill.engine.goalrunner.planning.model.GoalPlanningProduceAttemptArgs
import skillbill.engine.goalrunner.planning.model.GoalPlanningProducePhaseArgs
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
import skillbill.workflow.taskruntime.model.handoff.task.FeatureTaskRuntimePhaseOutput

internal fun DefaultGoalPlanningSweep.producePlan(
  args: ProduceMissingPlansArgs,
  subtask: DecompositionSubtask,
  descriptor: GovernedGoalSubtaskDescriptor,
  launch: GoalPlanningLaunch,
): GoalPlanningSweepOutcome.Stopped? {
  val shared = args.shared
  val request = args.request
  val provenance = args.provenance
  val preplanPayload = args.sharedCheckpoint.preplanPayload
  val resolvedSpecPath =
    resolvedSubSpecPath(shared.repoRoot, subtask.specPath, repositoryEnclosingRootPort)
      ?: return stopped(shared, subtask.id, unresolvedSpecReason(subtask), GoalPlanningSweepConstants.PHASE_PLAN)
  val runInvariants =
    runCatching { invariantsSource.read(resolvedSpecPath) }.getOrElse { error ->
      return stopped(shared, subtask.id, invariantReadReason(subtask, error), GoalPlanningSweepConstants.PHASE_PLAN)
    }
  val planProduction =
    producePhase(
      GoalPlanningProducePhaseArgs(
        attempt =
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
            recordedOutputs =
              listOf(
                FeatureTaskRuntimePhaseOutput(GoalPlanningSweepConstants.PHASE_PREPLAN, 1, preplanPayload),
              ),
            resolvedBodies = GoalPlanningResolvedBoundaryBodies(),
          ),
      ),
    )
  if (planProduction is GoalPlanningPhaseProduction.Stopped) return planProduction.outcome
  val captured = planProduction as GoalPlanningPhaseProduction.Captured
  val planPayload = captured.payload
  val record =
    GoalSubtaskPlanCheckpoint(
      identity = GoalPlanningIdentity(shared.parentWorkflowId, shared.normalizedIssueKey, shared.repositoryIdentity),
      subtaskId = subtask.id,
      manifestOrder = descriptor.manifestOrder,
      governedSubSpecPath = descriptor.governedSubSpecPath,
      subSpecHash = descriptor.subSpecHash,
      provenance = provenance,
      payloadSha256 = sha256HexUtf8(planPayload),
      planPayload = planPayload,
      repairEvidence = captured.repairEvidence,
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
