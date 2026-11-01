package skillbill.engine.featuretask.runloop.settlement

import skillbill.engine.featuretask.model.execution.ValidationGateCyclePhase
import skillbill.engine.featuretask.runloop.core.RepositoryCheckpointResolutionArgs
import skillbill.engine.featuretask.validation.model.ValidationGateResolution
import skillbill.error.featuretask.PhaseValidationScopeError
import skillbill.ports.workflow.gitops.model.WorkflowGitNameListResult

internal object FeatureTaskRuntimeRunLoopValidationScope {
  internal fun validationBranchPaths(args: RepositoryCheckpointResolutionArgs): List<String>? {
    val run = args.run
    val gitOperations = args.gitOperations
    return when (val paths = gitOperations.trackedPaths(run.request.repoRoot)) {
      is WorkflowGitNameListResult.Listed -> paths.names.distinct().sorted()
      is WorkflowGitNameListResult.Failed -> throw PhaseValidationScopeError(paths.error)
    }
  }

  internal fun packBuildCommand(args: RepositoryCheckpointResolutionArgs): String? {
    val run = args.run
    run.request.admittedExecution?.let {
      return it.effectiveInputs.commandArgv(ValidationGateCyclePhase.INITIAL_DISCOVERY)?.joinToString(" ")
    }
    val validationBranchPaths =
      validationBranchPaths(args)
    return when (
      val resolution = args.qualityGateCycles.resolve(run.request, validationBranchPaths.orEmpty())
    ) {
      is ValidationGateResolution.Declared -> resolution.declaration.buildCommand?.joinToString(" ")
      is ValidationGateResolution.Absent -> null
      is ValidationGateResolution.Incompatible -> null
    }
  }
}
