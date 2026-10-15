package skillbill.cli.featuretask

import com.github.ajalt.clikt.core.subcommands
import com.github.ajalt.clikt.parameters.arguments.argument
import me.tatarka.inject.annotations.Inject
import skillbill.cli.kernel.cli.CliRunState
import skillbill.cli.kernel.cli.DocumentedCliCommand
import skillbill.contracts.workflow.identity.task.FeatureTaskRuntimeGoalContinuationLaunchTokens

private const val FEATURE_TASK_RUNTIME_DEPRECATION_NOTE: String =
  "feature-task-runtime is a deprecated alias for feature-task. Use feature-task; behavior is unchanged.\n"

@Inject
class FeatureTaskRuntimeDeprecatedCommand(
  private val state: CliRunState,
  featureTaskRuntimeStatusCommand: FeatureTaskRuntimeStatusCommand,
  featureTaskRuntimeDeprecatedResumeCommand: FeatureTaskRuntimeDeprecatedResumeCommand,
) : DocumentedCliCommand(
    "feature-task-runtime",
    "Deprecated alias for feature-task. Use feature-task; behavior is unchanged.",
  ) {
  override val hiddenFromHelp: Boolean = true

  init {
    subcommands(featureTaskRuntimeStatusCommand, featureTaskRuntimeDeprecatedResumeCommand)
  }

  override fun run() {
    state.appendStderr(FEATURE_TASK_RUNTIME_DEPRECATION_NOTE)
  }
}

@Inject
class FeatureTaskRuntimeDeprecatedResumeCommand(
  private val preparation: FeatureTaskRuntimeRunPreparation,
  private val execution: FeatureTaskRuntimeRunExecution,
) : FeatureTaskRuntimePhaseAgentCommand(
    FeatureTaskRuntimeGoalContinuationLaunchTokens.RESUME_SUBCOMMAND,
    "Resume a feature-task run against an existing workflow id.",
  ) {
  private val workflowId by argument(help = "Existing runtime workflow id to resume.")
  private val issueKey by argument(help = "Issue key the resumed run implements.")
  private val specPath by argument(help = "Path to the governed spec the resumed run implements.")

  override fun run() {
    execution.run(this, preparation.prepareResume(this, workflowId, issueKey, specPath), workflowId)
  }
}
