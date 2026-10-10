package skillbill.cli.core

import com.github.ajalt.clikt.completion.completionOption
import com.github.ajalt.clikt.core.ParameterHolder
import com.github.ajalt.clikt.core.subcommands
import com.github.ajalt.clikt.parameters.options.flag
import com.github.ajalt.clikt.parameters.options.option
import me.tatarka.inject.annotations.Inject
import skillbill.cli.kernel.cli.DocumentedCliCommand
import skillbill.cli.phase.PHASE_COLON_PREFIX
import skillbill.contracts.issuekey.looksLikeGoalIntakeToken
import skillbill.di.core.verboseLoggingRequestedByEnvironment

internal const val VERBOSE_OPTION = "--verbose"
internal const val OPERATION_COLON_PREFIX = "operation:"
private const val GOAL_SUBCOMMAND = "goal"

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
  val first = arguments.getOrNull(index)?.takeUnless { it.startsWith('-') || isCommand(it) } ?: return arguments
  val routed = intakeCommandTokens(first) ?: return arguments
  return arguments.take(index) + routed + arguments.drop(index + 1)
}

private fun intakeCommandTokens(token: String): List<String>? =
  splitColonCommand(token) ?: listOf(GOAL_SUBCOMMAND, token).takeIf { looksLikeGoalIntakeToken(token) }

private fun splitColonCommand(token: String): List<String>? {
  val prefix = listOf(PHASE_COLON_PREFIX, OPERATION_COLON_PREFIX).firstOrNull(token::startsWith) ?: return null
  val name = token.removePrefix(prefix).takeIf(String::isNotBlank) ?: return null
  return listOf(prefix.dropLast(1), name)
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
    "Run a goal, a standalone phase, or a runtime operation, and inspect review and telemetry data.",
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
