package skillbill.engine.goalrunner.planning.outcome

import me.tatarka.inject.annotations.Inject
import skillbill.engine.goalplanning.GoalPlanningPreparationCheckpoint
import skillbill.engine.goalplanning.readStoredPlanningRecord
import skillbill.engine.goalrunner.execution.core.ProduceMissingPlansArgs
import skillbill.engine.goalrunner.planning.attempt.GoalPlanningPhaseAttemptGate
import skillbill.engine.goalrunner.planning.model.GoalPlanningLaunch
import skillbill.engine.goalrunner.planning.model.GoalPlanningPhaseContext
import skillbill.engine.goalrunner.planning.model.GoalPlanningPhaseProduction
import skillbill.engine.goalrunner.planning.model.GoalPlanningProduceAttemptArgs
import skillbill.engine.goalrunner.planning.model.GoalPlanningSharedContext
import skillbill.engine.goalrunner.planning.model.GoalPlanningSweepOutcome
import skillbill.engine.goalrunner.planning.sweep.GoalPlanningSweepConstants
import skillbill.ports.goalrunner.model.GoalPlanningIdentity
import skillbill.ports.goalrunner.model.GoalSubtaskPlanCheckpoint
import skillbill.ports.goalrunner.model.GovernedGoalSubtaskDescriptor
import skillbill.ports.goalrunner.planning.model.GoalPlanningResolvedBoundaryBodies
import skillbill.ports.repository.RepositoryEnclosingRootPort
import skillbill.ports.taskruntime.FeatureTaskRuntimeRunInvariantsSource
import skillbill.ports.workflow.decomposition.DecompositionManifestStore
import skillbill.text.sha256HexUtf8
import skillbill.workflow.decomposition.model.DecompositionSubtask
import skillbill.workflow.model.DecompositionStatus
import skillbill.workflow.model.decompositionStatus
import skillbill.workflow.taskruntime.model.handoff.assembly.FeatureTaskRuntimePhaseOutput
import java.nio.file.Path

@Inject
class GoalPlanningSubtaskPlanProduction(
  private val attemptGate: GoalPlanningPhaseAttemptGate,
  private val checkpoint: GoalPlanningPreparationCheckpoint,
  private val invariantsSource: FeatureTaskRuntimeRunInvariantsSource,
  private val manifestFileStore: DecompositionManifestStore,
  private val repositoryEnclosingRootPort: RepositoryEnclosingRootPort,
) {
  internal fun producePlan(
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
        invariantsSource.read(
          resolvedSpecPath,
        ) to snapshotSubSpecs(shared, subtask, resolvedSpecPath, manifestFileStore, repositoryEnclosingRootPort)
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
      attemptGate.producePhase(
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
    return when (planProduction) {
      is GoalPlanningPhaseProduction.Stopped -> SubtaskPlanProduction.Stopped(planProduction.outcome)
      is GoalPlanningPhaseProduction.RequiredWriteRejected ->
        SubtaskPlanProduction.RequiredWriteRejected(planProduction.rejection)
      else -> {
        val captured = planProduction as GoalPlanningPhaseProduction.Captured
        checkpointProducedPlan(args, subtask, descriptor, resolvedSpecPath to snapshot, captured.payload)
          ?.let { SubtaskPlanProduction.Stopped(it) }
          ?: SubtaskPlanProduction.Planned
      }
    }
  }

  private fun checkpointProducedPlan(
    args: ProduceMissingPlansArgs,
    subtask: DecompositionSubtask,
    descriptor: GovernedGoalSubtaskDescriptor,
    launchedSpec: Pair<Path, GoalPlanningSubSpecSnapshot>,
    capturedPayload: String,
  ): GoalPlanningSweepOutcome.Stopped? {
    val shared = args.shared
    val (launchedSpecPath, snapshot) = launchedSpec
    val persistedSpec =
      admitPersistedSubSpec(
        shared,
        subtask,
        GoalPlanningSpecAdmission(launchedSpec, args.startedPlanIds),
        manifestFileStore,
        repositoryEnclosingRootPort,
      ).getOrElse { error ->
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

  internal fun descriptor(
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
        recovered != null && subtask.status.decompositionStatus() == DecompositionStatus.COMPLETE ->
          recovered.subSpecHash
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
}
