package skillbill.cli.goal.export

import com.github.ajalt.clikt.parameters.arguments.argument
import com.github.ajalt.clikt.parameters.options.option
import me.tatarka.inject.annotations.Inject
import skillbill.cli.kernel.cli.CliRunState
import skillbill.cli.kernel.cli.DocumentedCliCommand
import skillbill.cli.kernel.cli.resolveCliRepositoryRoot
import skillbill.cli.model.CliRunInputs
import skillbill.engine.goalrunner.model.GoalRunnerExportRequest
import skillbill.engine.goalrunner.status.GoalRunnerStatusService
import java.nio.file.Path

@Inject
class GoalExportCommand(
  private val goalRunnerStatusService: GoalRunnerStatusService,
  private val state: CliRunState,
  private val inputs: CliRunInputs,
) : DocumentedCliCommand(
    "export",
    "Write a portable zip archive of a decomposed goal's spec bundle and purge-owned durable state.",
  ) {
  private val issueKey by argument(help = "Parent issue key for the decomposed goal.")
  private val output by option("--output", help = "Destination zip path.")
  private val repoRoot by option("--repo-root", help = "Repository root that owns the goal.")

  override fun run() {
    val effectiveRepoRoot = resolveCliRepositoryRoot(repoRoot, inputs)
    val outputPath =
      (output?.let(Path::of) ?: Path.of("$issueKey-archive.zip"))
        .toAbsolutePath()
        .normalize()
    val result =
      goalRunnerStatusService.export(
        GoalRunnerExportRequest(
          issueKey = issueKey,
          repoRoot = effectiveRepoRoot,
          outputPath = outputPath,
        ),
      )
    val payload = result.toGoalExportCliMap()
    state.completeText(goalExportText(result), payload, exitCode = goalExportExitCode(result))
  }
}
