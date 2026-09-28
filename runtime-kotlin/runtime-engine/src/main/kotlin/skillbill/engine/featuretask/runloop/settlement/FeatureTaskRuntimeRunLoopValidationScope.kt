package skillbill.engine.featuretask.runloop.settlement

import skillbill.engine.featuretask.phase.core.FeatureTaskRuntimePhaseGates
import skillbill.engine.featuretask.runloop.core.FeatureTaskRuntimeRunLoopSession
import skillbill.engine.featuretask.runloop.core.PhaseRun
import skillbill.engine.featuretask.runloop.core.RepositoryCheckpointResolutionArgs
import skillbill.engine.featuretask.runloop.output.FeatureTaskRuntimeRunLoopOutputVerification
import skillbill.engine.featuretask.slot.state.PhaseRunGoal
import skillbill.engine.featuretask.slot.state.PhaseRunRecords
import skillbill.engine.featuretask.model.execution.ValidationGateCyclePhase
import skillbill.engine.featuretask.validation.model.ValidationGateResolution
import skillbill.error.featuretask.PhaseValidationScopeError
import skillbill.ports.workflow.gitops.model.WorkflowGitNameListResult
import skillbill.workflow.taskruntime.model.skeleton.SkeletonRunStateKind

object FeatureTaskRuntimeRunLoopValidationScope {
  internal fun validationChangedPaths(
    phaseGates: FeatureTaskRuntimePhaseGates,
    recorder: PhaseRunRecords,
    goalContinuationRecorder: PhaseRunGoal,
    session: FeatureTaskRuntimeRunLoopSession,
    run: PhaseRun,
  ): List<String>? {
    if (run.request.skeletonDefinition?.runStateKind == SkeletonRunStateKind.IN_MEMORY) {
      return when (val paths = phaseGates.gitOperations.repositoryOwnedPaths(run.request.repoRoot)) {
        is WorkflowGitNameListResult.Listed -> paths.names.distinct().sorted()
        is WorkflowGitNameListResult.Failed -> throw PhaseValidationScopeError(paths.error)
      }
    }
    return with(FeatureTaskRuntimeRunLoopOutputVerification) {
      resolveRepositoryCheckpoint(
        RepositoryCheckpointResolutionArgs(
          recorder = recorder,
          goalContinuationRecorder = goalContinuationRecorder,
          phaseGates = phaseGates,
          session = session,
          run = run,
        ),
      )
        ?.workingTreeOwnedPaths
        ?.distinct()
        ?.sorted()
    }
  }

  internal fun packBuildCommand(
    phaseGates: FeatureTaskRuntimePhaseGates,
    recorder: PhaseRunRecords,
    goalContinuationRecorder: PhaseRunGoal,
    session: FeatureTaskRuntimeRunLoopSession,
    run: PhaseRun,
  ): String? {
    run.request.admittedExecution?.let {
      return it.effectiveInputs.commandArgv(ValidationGateCyclePhase.INITIAL_DISCOVERY)?.joinToString(" ")
    }
    val validationChangedPaths =
      validationChangedPaths(
        phaseGates,
        recorder,
        goalContinuationRecorder,
        session,
        run,
      )
    return when (
      val resolution = phaseGates.validationGateResolver.resolve(validationChangedPaths.orEmpty())
    ) {
      is ValidationGateResolution.Declared -> resolution.declaration.buildCommand?.joinToString(" ")
      is ValidationGateResolution.Absent -> null
      is ValidationGateResolution.Incompatible -> null
    }
  }

  internal fun packCollectAllCommand(
    phaseGates: FeatureTaskRuntimePhaseGates,
    recorder: PhaseRunRecords,
    goalContinuationRecorder: PhaseRunGoal,
    session: FeatureTaskRuntimeRunLoopSession,
    run: PhaseRun,
  ): String? {
    run.request.admittedExecution?.let {
      return it.effectiveInputs.commandArgv(ValidationGateCyclePhase.INITIAL_DISCOVERY)?.joinToString(" ")
    }
    val paths = validationChangedPaths(phaseGates, recorder, goalContinuationRecorder, session, run)
    return (phaseGates.validationGateResolver.resolve(paths.orEmpty()) as? ValidationGateResolution.Declared)
      ?.declaration
      ?.collectAllFullGateCommand
      ?.joinToString(" ")
  }
}
