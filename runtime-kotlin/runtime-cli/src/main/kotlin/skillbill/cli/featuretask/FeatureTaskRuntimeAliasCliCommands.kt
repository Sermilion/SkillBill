package skillbill.cli.featuretask

import com.github.ajalt.clikt.core.subcommands
import com.github.ajalt.clikt.parameters.arguments.argument
import com.github.ajalt.clikt.parameters.arguments.optional
import me.tatarka.inject.annotations.Inject
import skillbill.cli.kernel.cli.CliRunState
import skillbill.contracts.workflow.identity.task.FeatureTaskRuntimeGoalContinuationLaunchTokens

private const val FEATURE_TASK_RUNTIME_DEPRECATION_NOTE: String =
  "feature-task-runtime is a deprecated alias for feature-task. Use feature-task; behavior is unchanged.\n"

@Inject
class FeatureTaskRuntimeDeprecatedRunCommand(
  private val preparation: FeatureTaskRuntimeRunPreparation,
  private val execution: FeatureTaskRuntimeRunExecution,
  private val state: CliRunState,
  featureTaskRuntimeExplicitRunCommand: FeatureTaskRuntimeExplicitRunCommand,
  featureTaskRuntimeStatusCommand: FeatureTaskRuntimeStatusCommand,
  featureTaskRuntimeDeprecatedResumeCommand: FeatureTaskRuntimeDeprecatedResumeCommand,
) : FeatureTaskRuntimePhaseAgentCommand(
    "feature-task-runtime",
    "Deprecated alias for feature-task. Use feature-task; behavior is unchanged.",
  ) {
  override val hiddenFromHelp: Boolean = true

  private val issueKey by argument(help = "Issue key the run implements.").optional()
  private val specPath by argument(help = "Path to the governed spec the run implements.").optional()

  override val invokeWithoutSubcommand: Boolean = true

  init {
    subcommands(
      featureTaskRuntimeExplicitRunCommand,
      featureTaskRuntimeStatusCommand,
      featureTaskRuntimeDeprecatedResumeCommand,
    )
  }

  override fun run() {
    state.appendStderr(FEATURE_TASK_RUNTIME_DEPRECATION_NOTE)
    if (currentContext.invokedSubcommand != null) {
      return
    }
    execution.run(this, preparation.prepareRun(this, issueKey, specPath))
  }
}

// Kept separate from FeatureTaskRuntimeResumeCommand only because the hidden alias help for specPath must stay
// byte-identical to the baseline.
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
    execution.execute(this, preparation.prepareResume(this, workflowId, issueKey, specPath), workflowId)
  }
}
