package skillbill.cli.core

import com.github.ajalt.clikt.completion.completionOption
import com.github.ajalt.clikt.core.ParameterHolder
import com.github.ajalt.clikt.core.subcommands
import com.github.ajalt.clikt.parameters.options.flag
import com.github.ajalt.clikt.parameters.options.option
import me.tatarka.inject.annotations.Inject
import skillbill.cli.kernel.cli.DocumentedCliCommand
import skillbill.di.core.verboseLoggingRequestedByEnvironment

internal const val VERBOSE_OPTION = "--verbose"

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

internal fun ParameterHolder.verboseOption() =
  option(
    VERBOSE_OPTION,
    help = "Print runtime diagnostics to stderr. Same as SKILL_BILL_VERBOSE=1.",
  ).flag()

internal fun leadingRootOptionCount(arguments: List<String>): Int {
  var index = 0
  while (index < arguments.size) {
    val token = arguments[index]
    if (token == "--db" || token == "--home") {
      index += 2
    } else if (token == VERBOSE_OPTION || token.startsWith("--db=") || token.startsWith("--home=")) {
      index += 1
    } else {
      break
    }
  }
  return index
}

internal fun routeIntakeTokens(
  arguments: List<String>,
  isCommand: (String) -> Boolean,
): List<String> {
  val index = leadingRootOptionCount(arguments)
  val first = arguments.getOrNull(index) ?: return arguments
  if (first.startsWith('-') || isCommand(first)) return arguments
  return arguments.take(index) + "goal" + arguments.drop(index)
}

internal fun resolveVerboseLogging(
  args: List<String>,
  environment: Map<String, String>,
): Boolean =
  VERBOSE_OPTION in args.take(leadingRootOptionCount(args)) ||
    verboseLoggingRequestedByEnvironment(environment)

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
    registerOption(verboseOption())
    completionOption()
    subcommands(commands.commands)
  }

  override fun aliases(): Map<String, List<String>> =
    mapOf(
      "feature-verify-stats" to listOf("verify-stats"),
      "feature-task-runtime-stats" to listOf("runtime-stats"),
    )

  internal fun routeIntake(arguments: List<String>): List<String> =
    routeIntakeTokens(arguments) { it in registeredSubcommandNames() || it in aliases() }

  override fun run() = Unit
}
