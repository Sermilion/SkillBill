
package skillbill.cli.skillremove

import com.github.ajalt.clikt.parameters.arguments.argument
import com.github.ajalt.clikt.parameters.arguments.optional
import com.github.ajalt.clikt.parameters.options.default
import com.github.ajalt.clikt.parameters.options.flag
import com.github.ajalt.clikt.parameters.options.option
import me.tatarka.inject.annotations.Inject
import skillbill.application.scaffold.SkillRemove
import skillbill.cli.kernel.cli.CliRunState
import skillbill.cli.kernel.cli.DocumentedCliCommand
import skillbill.cli.kernel.cli.formatOption
import skillbill.cli.kernel.cli.resolveCliRepositoryRoot
import skillbill.cli.model.CliRunInputs

@Inject
class RemoveCliCommand(
  private val state: CliRunState,
  private val inputs: CliRunInputs,
  private val skillRemove: SkillRemove,
) : DocumentedCliCommand(
    "remove",
    "Remove a horizontal skill, a platform pack, or a governed add-on, including manifest, README, " +
      "and agent-symlink cleanup.",
  ) {
  private val target by argument(
    help =
      "Removal target. Examples: 'skill:bill-foo', 'platform:my-platform', " +
        "'addon:platform-packs/kmp/addons/my-addon.md'.",
  ).optional()
  private val repoRoot by option(
    "--repo-root",
    help = "Repo root to operate on. Defaults to the invocation repository root.",
  )
  private val dryRun by option("--dry-run", help = "Preview the removal cascade without touching disk.")
    .flag(default = false)
  private val allowShipped by option(
    "--allow-shipped",
    help =
      "Allow removal of shipped product surfaces such as bill-* skills. " +
        "'.bill-shared' is never removable.",
  ).flag(default = false)
  private val format by formatOption()

  override fun run() {
    executeRemoveCommand(
      RemoveCommandExecutionRequest(
        inputs = inputs,
        skillRemove = skillRemove,
        rawTarget = target,
        repoRoot = resolveCliRepositoryRoot(repoRoot, inputs).toString(),
        dryRun = dryRun,
        allowShipped = allowShipped,
        format = format,
      ),
      state,
    )
  }
}
