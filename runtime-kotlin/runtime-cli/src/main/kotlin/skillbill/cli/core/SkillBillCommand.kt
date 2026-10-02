package skillbill.cli.core

import com.github.ajalt.clikt.completion.completionOption
import com.github.ajalt.clikt.core.ParameterHolder
import com.github.ajalt.clikt.core.subcommands
import com.github.ajalt.clikt.parameters.options.option
import me.tatarka.inject.annotations.Inject
import skillbill.cli.kernel.cli.DocumentedCliCommand

internal fun ParameterHolder.databasePathOption() =
  option(
    "--db",
    help = "Optional SQLite path. Defaults to SKILL_BILL_DB or the standard local state path.",
  )

internal fun ParameterHolder.userHomeOverrideOption() =
  option(
    "--home",
    help = "User home directory for install/runtime path detection.",
  )

@Inject
class SkillBillCommand(
  commands: CliCommandProvider,
) : DocumentedCliCommand(
    "skill-bill",
    "Import Skill Bill review output, triage findings, manage learnings, " +
      "scaffold governed skills, and inspect telemetry.",
  ) {
  init {
    registerOption(databasePathOption())
    registerOption(userHomeOverrideOption())
    completionOption()
    subcommands(commands.commands)
  }

  override fun aliases(): Map<String, List<String>> =
    mapOf(
      "feature-verify-stats" to listOf("verify-stats"),
      "feature-task-runtime-stats" to listOf("runtime-stats"),
    )

  internal fun routeIntake(arguments: List<String>): List<String> {
    var index = 0
    while (index < arguments.size) {
      val token = arguments[index]
      if (token == "--db" || token == "--home") {
        index += 2
      } else if (token.startsWith("--db=") || token.startsWith("--home=")) {
        index += 1
      } else {
        break
      }
    }
    val first = arguments.getOrNull(index) ?: return arguments
    if (first.startsWith('-') || first in registeredSubcommandNames() || first in aliases()) return arguments
    return arguments.take(index) + "goal" + arguments.drop(index)
  }

  override fun run() = Unit
}
