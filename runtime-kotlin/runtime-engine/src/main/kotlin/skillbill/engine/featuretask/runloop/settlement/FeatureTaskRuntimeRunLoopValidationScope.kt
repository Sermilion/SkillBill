package skillbill.engine.featuretask.runloop.settlement

import skillbill.engine.featuretask.model.execution.ValidationGateCyclePhase
import skillbill.engine.featuretask.phase.core.FeatureTaskRuntimePhaseGates
import skillbill.engine.featuretask.runloop.core.FeatureTaskRuntimeRunSessionObservations
import skillbill.engine.featuretask.runloop.core.PhaseRun
import skillbill.engine.featuretask.runloop.core.RepositoryCheckpointResolutionArgs
import skillbill.engine.featuretask.runloop.output.FeatureTaskRuntimeRunLoopOutputVerification
import skillbill.engine.featuretask.runloop.state.FeatureTaskRuntimeRunTransitionOwner
import skillbill.engine.featuretask.slot.state.PhaseRunGoal
import skillbill.engine.featuretask.slot.state.PhaseRunRecords
import skillbill.engine.featuretask.validation.model.ValidationGateResolution
import skillbill.error.featuretask.PhaseValidationScopeError
import skillbill.ports.workflow.gitops.model.WorkflowGitNameListResult
import skillbill.workflow.taskruntime.model.skeleton.SkeletonRunStateKind

object FeatureTaskRuntimeRunLoopValidationScope {
  internal fun validationChangedPaths(
    args: RepositoryCheckpointResolutionArgs,
  ): List<String>? {
    val run = args.run
    val phaseGates = args.phaseGates
    if (run.request.skeletonDefinition?.runStateKind == SkeletonRunStateKind.IN_MEMORY) {
      return when (val paths = phaseGates.gitOperations.repositoryOwnedPaths(run.request.repoRoot)) {
        is WorkflowGitNameListResult.Listed -> paths.names.distinct().sorted()
        is WorkflowGitNameListResult.Failed -> throw PhaseValidationScopeError(paths.error)
      }
    }
    return with(FeatureTaskRuntimeRunLoopOutputVerification) {
      resolveRepositoryCheckpoint(
        args,
      )
        ?.workingTreeOwnedPaths
        ?.distinct()
        ?.sorted()
    }
  }

  internal fun packBuildCommand(
    args: RepositoryCheckpointResolutionArgs,
  ): String? {
    val run = args.run
    val phaseGates = args.phaseGates
    run.request.admittedExecution?.let {
      return it.effectiveInputs.commandArgv(ValidationGateCyclePhase.INITIAL_DISCOVERY)?.joinToString(" ")
    }
    val validationChangedPaths =
      validationChangedPaths(args)
    return when (
      val resolution = phaseGates.validationGateResolver.resolve(validationChangedPaths.orEmpty())
    ) {
      is ValidationGateResolution.Declared -> resolution.declaration.buildCommand?.joinToString(" ")
      is ValidationGateResolution.Absent -> null
      is ValidationGateResolution.Incompatible -> null
    }
  }

  internal fun packCollectAllCommand(
    args: RepositoryCheckpointResolutionArgs,
  ): String? {
    val run = args.run
    val phaseGates = args.phaseGates
    run.request.admittedExecution?.let {
      return it.effectiveInputs.commandArgv(ValidationGateCyclePhase.INITIAL_DISCOVERY)?.joinToString(" ")
    }
    val paths =
      validationChangedPaths(args)
    return (phaseGates.validationGateResolver.resolve(paths.orEmpty()) as? ValidationGateResolution.Declared)
      ?.declaration
      ?.collectAllFullGateCommand
      ?.joinToString(" ")
  }
}
