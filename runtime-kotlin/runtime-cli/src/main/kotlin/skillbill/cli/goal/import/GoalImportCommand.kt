package skillbill.cli.goal.import

import com.github.ajalt.clikt.parameters.arguments.argument
import com.github.ajalt.clikt.parameters.options.option
import me.tatarka.inject.annotations.Inject
import skillbill.cli.kernel.cli.CliRunState
import skillbill.cli.kernel.cli.DocumentedCliCommand
import skillbill.cli.kernel.cli.resolveCliRepositoryRoot
import skillbill.cli.model.CliRunInputs
import skillbill.engine.goalrunner.model.GoalRunnerImportRequest
import skillbill.engine.goalrunner.status.GoalRunnerStatusService
import java.nio.file.Path

@Inject
class GoalImportCommand(
  private val goalRunnerStatusService: GoalRunnerStatusService,
  private val state: CliRunState,
  private val inputs: CliRunInputs,
) : DocumentedCliCommand(
    "import",
    "Restore a portable goal archive into the destination checkout. Lands the work item paused and idle.",
  ) {
  private val archive by argument(help = "Path to the goal archive zip produced by goal export.")
  private val repoRoot by option("--repo-root", help = "Repository root that owns the goal.")

  override fun run() {
    val result =
      goalRunnerStatusService.import(
        GoalRunnerImportRequest(
          archivePath = Path.of(archive).toAbsolutePath().normalize(),
          repoRoot = resolveCliRepositoryRoot(repoRoot, inputs),
        ),
      )
    val payload = result.toGoalImportCliMap()
    state.completeText(goalImportText(result), payload, exitCode = goalImportExitCode(result))
  }
}
