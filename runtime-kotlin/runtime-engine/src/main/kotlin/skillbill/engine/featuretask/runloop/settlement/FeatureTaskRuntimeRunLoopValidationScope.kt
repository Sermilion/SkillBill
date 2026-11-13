package skillbill.engine.featuretask.runloop.settlement

import skillbill.contracts.config.projectchecks.ProjectCheckCommand
import skillbill.engine.featuretask.model.execution.ValidationGateCyclePhase
import skillbill.engine.featuretask.runloop.core.PhaseRun
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
    acceptedBuildCommand(run)?.let { return it }
    admittedBuildCommand(run)?.let { return it }
    return resolvedPackBuildCommand(args)
  }

  private fun acceptedBuildCommand(run: PhaseRun): String? {
    val accepted = run.request.acceptedGoalQualityExecutionPlan ?: return null
    accepted.projectCheckCommands?.let { snapshot ->
      return snapshot.buildCommandList.takeIf { it.isNotEmpty() }?.let(::renderSavedBuildCommands)
    }
    return run.request.admittedExecution
      ?.effectiveInputs
      ?.commandArgv(ValidationGateCyclePhase.INITIAL_DISCOVERY)
      ?.joinToString(" ")
  }

  private fun admittedBuildCommand(run: PhaseRun): String? {
    val admitted = run.request.admittedExecution ?: return null
    admitted.plan.projectCheckCommands?.let { snapshot ->
      return snapshot.buildCommandList.takeIf { it.isNotEmpty() }?.let(::renderSavedBuildCommands)
    }
    return admitted.effectiveInputs.commandArgv(ValidationGateCyclePhase.INITIAL_DISCOVERY)?.joinToString(" ")
  }

  private fun resolvedPackBuildCommand(args: RepositoryCheckpointResolutionArgs): String? {
    val validationBranchPaths = validationBranchPaths(args)
    return when (
      val resolution = args.qualityGateCycles.resolve(args.run.request, validationBranchPaths.orEmpty())
    ) {
      is ValidationGateResolution.Declared -> resolution.declaration.buildCommand?.joinToString(" ")
      is ValidationGateResolution.Absent -> null
      is ValidationGateResolution.Incompatible -> null
    }
  }

  private fun renderSavedBuildCommands(commands: List<ProjectCheckCommand>): String =
    commands.joinToString(" ; ") { command ->
      if (command.workingDirectory == ".") {
        command.argv.joinToString(" ")
      } else {
        "${command.workingDirectory}: ${command.argv.joinToString(" ")}"
      }
    }
}
