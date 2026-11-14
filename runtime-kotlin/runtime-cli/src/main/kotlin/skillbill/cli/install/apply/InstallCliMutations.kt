package skillbill.cli.install.apply

import skillbill.cli.kernel.cli.CliRunState
import skillbill.cli.model.CliRunInputs
import skillbill.contracts.SharedPayloadKeys
import skillbill.contracts.workflow.identity.task.FeatureTaskRuntimeGoalContinuationLaunchTokens
import skillbill.ports.install.model.NativeAgentLinkOutcome
import java.nio.file.Path

private const val GOAL_CONTINUATION_INSTALL_REFUSAL_EXIT_CODE = 64

internal fun CliRunState.refuseInstallMutationDuringGoalContinuation(
  inputs: CliRunInputs,
  commandName: String,
): Boolean {
  if (inputs.environment[FeatureTaskRuntimeGoalContinuationLaunchTokens.GOAL_CONTINUATION_ENV] != "1") {
    return false
  }
  val message =
    "Refusing to run sona install $commandName during sona goal-continuation.\n" +
      "Goal workers must preserve the active workflow store; run install sync after the goal completes."
  completeText(
    "$message\n",
    mapOf(
      SharedPayloadKeys.STATUS to "error",
      "error" to message,
      "exit_code" to GOAL_CONTINUATION_INSTALL_REFUSAL_EXIT_CODE,
    ),
    exitCode = GOAL_CONTINUATION_INSTALL_REFUSAL_EXIT_CODE,
  )
  return true
}

internal fun completeNativeAgentLinkOutcome(
  state: CliRunState,
  outcome: NativeAgentLinkOutcome,
) {
  val text =
    (
      outcome.linked.map { path -> "linked\t$path" } +
        outcome.skipped.map { entry -> "skipped\t${entry.path}\t${entry.reason}" }
    ).joinToString("\n")
  state.completeText(
    text,
    mapOf(
      "linked" to outcome.linked.map(Path::toString),
      "skipped" to
        outcome.skipped.map { skip ->
          mapOf("path" to skip.path.toString(), "reason" to skip.reason)
        },
    ),
  )
}
