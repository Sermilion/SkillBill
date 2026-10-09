package skillbill.cli.goal.purge

import com.github.ajalt.clikt.core.UsageError
import com.github.ajalt.clikt.parameters.arguments.argument
import com.github.ajalt.clikt.parameters.options.flag
import com.github.ajalt.clikt.parameters.options.option
import me.tatarka.inject.annotations.Inject
import skillbill.cli.kernel.cli.CliRunState
import skillbill.cli.kernel.cli.DocumentedCliCommand
import skillbill.cli.kernel.cli.resolveCliRepositoryRoot
import skillbill.cli.model.CliRunInputs
import skillbill.engine.goalrunner.model.GoalRunnerPurgeRequest
import skillbill.engine.goalrunner.status.GoalRunnerStatusService

@Inject
class GoalPurgeCommand(
  private val goalRunnerStatusService: GoalRunnerStatusService,
  private val state: CliRunState,
  private val inputs: CliRunInputs,
) : DocumentedCliCommand(
    "purge",
    "Delete all runtime state a decomposed goal owns, keep the parent spec, and reset the feature-spec bundle " +
      "to an unlaunched shape. Exits 1 when anything is left behind. $GOAL_PURGE_RETAINED_NOTE",
  ) {
  private val issueKey by argument(help = "Parent issue key for the decomposed goal.")
  private val force by option("--force", "--yes", help = "Bypass purge confirmation gate.")
    .flag(default = false)
  private val confirmIssueKey by option(
    "--confirm-issue-key",
    help = "Confirmation gate for purge. Must match the issue key.",
  )
  private val repoRoot by option("--repo-root", help = "Repository root that owns the goal.")

  override fun run() {
    if (!force && confirmIssueKey != issueKey) {
      throw UsageError(
        "Goal purge requires explicit confirmation. Pass --confirm-issue-key $issueKey or --force.",
      )
    }
    val result =
      goalRunnerStatusService.purge(
        GoalRunnerPurgeRequest(
          issueKey = issueKey,
          repoRoot = resolveCliRepositoryRoot(repoRoot, inputs),
        ),
      )
    val payload = result.toGoalPurgeCliMap()
    state.completeText(goalPurgeText(result), payload, exitCode = goalPurgeExitCode(result))
  }
}
