package skillbill.cli.operation

import com.github.ajalt.clikt.core.UsageError
import com.github.ajalt.clikt.parameters.arguments.argument
import com.github.ajalt.clikt.parameters.arguments.multiple
import com.github.ajalt.clikt.parameters.options.flag
import com.github.ajalt.clikt.parameters.options.option
import com.github.ajalt.clikt.parameters.types.choice
import me.tatarka.inject.annotations.Inject
import skillbill.cli.kernel.agent.detectInvokingAgentId
import skillbill.cli.kernel.agent.invokingAgentResolutionHelp
import skillbill.cli.kernel.agent.requireInvokingAgentId
import skillbill.cli.kernel.cli.CliRunState
import skillbill.cli.kernel.cli.DocumentedCliCommand
import skillbill.cli.kernel.cli.resolveCliRepositoryRoot
import skillbill.cli.model.CliRunInputs
import skillbill.engine.operation.core.OperationArguments
import skillbill.engine.operation.core.OperationExecutor
import skillbill.engine.operation.core.OperationOutcome
import skillbill.engine.operation.core.OperationOutputFormat
import skillbill.engine.operation.core.OperationRegistry
import skillbill.engine.operation.core.OperationRequest
import skillbill.engine.operation.core.OperationResult

@Inject
class OperationCommand(
  private val executor: OperationExecutor,
  registry: OperationRegistry,
  private val state: CliRunState,
  private val inputs: CliRunInputs,
) : DocumentedCliCommand(
    "operation",
    "Run one runtime operation (${registry.ids.joinToString(", ")}) with no feature-task workflow. An operation " +
      "that needs confirmation prints its proposal and `status: awaiting_confirmation confirm:<token>`, then exits " +
      "$OPERATION_EXIT_AWAITING_CONFIRMATION; re-run it with that confirm:<token> to execute the stored proposal.",
  ) {
  private val name by argument(name = "name", help = "Operation to run: ${registry.ids.joinToString(", ")}.")
  private val rest by argument(
    name = "args",
    help =
      "key:value pairs (${OperationInvocationParser.KEYS.joinToString(", ") { "$it:" }}); any other text is " +
        "operator instructions for the operation's agent step. pr-review-fix reads a leading #<number> or PR " +
        "URL, or a lone PR number (default: the current branch's PR) and alone accepts push:on|off and " +
        "replies:post|draft. verify reads its intake from the free text (a Linear issue key or URL, or the " +
        "requirements) or spec:<path>, and alone accepts spec:, target:<pr-number|branch|base..head> (default: " +
        "HEAD against origin/HEAD), and mode:inline|delegated; its confirm:<token> is the verify workflow id.",
  ).multiple()
  private val agent by option(
    "--agent",
    help = "Agent an operation's agent step launches. " + invokingAgentResolutionHelp("--agent"),
  )
  private val includePrereleases by option(
    OperationInvocationParser.INCLUDE_PRERELEASES_OPTION,
    help = "update-check only: include prerelease GitHub releases in the comparison.",
  ).flag(default = false)
  private val format by option(
    OperationInvocationParser.FORMAT_OPTION,
    help = "update-check only: report format, text (default) or json.",
  ).choice(OperationOutputFormat.entries.associateBy(OperationOutputFormat::wireValue))

  override fun run() {
    val invocation = OperationInvocationParser.parse(name, rest, OperationFlags(includePrereleases, format))
    val request =
      OperationRequest(
        operationId = invocation.operationId,
        repoRoot = resolveCliRepositoryRoot(null, inputs),
        invokedAgentId =
          detectInvokingAgentId(agent, inputs.environment)?.let {
            requireInvokingAgentId(agent, inputs.environment, "--agent")
          },
        arguments = invocation.arguments,
        instructions = invocation.instructions,
      )
    writeOperationResult(state, request.operationId, executor.execute(request))
  }
}

data class OperationInvocation(
  val operationId: String,
  val arguments: OperationArguments,
  val instructions: String?,
)

data class OperationFlags(
  val includePrereleases: Boolean = false,
  val format: OperationOutputFormat? = null,
)

object OperationInvocationParser {
  const val BUMP: String = "bump"
  const val CONFIRM: String = "confirm"
  const val SELECT: String = "select"
  const val MODE: String = "mode"
  const val SCOPE: String = "scope"
  const val PUSH: String = "push"
  const val REPLIES: String = "replies"
  const val SPEC: String = "spec"
  const val TARGET: String = "target"
  val KEYS: List<String> = listOf(BUMP, CONFIRM, SELECT, MODE, SCOPE, PUSH, REPLIES, SPEC, TARGET)
  const val INCLUDE_PRERELEASES_OPTION: String = "--include-prereleases"
  const val FORMAT_OPTION: String = "--format"
  private const val UPDATE_CHECK = "update-check"
  private const val KEY_SEPARATOR = ':'

  private val OPERATION_ONLY_KEYS: Map<String, String> =
    mapOf(
      PUSH to "pr-review-fix",
      REPLIES to "pr-review-fix",
      MODE to "verify",
      SPEC to "verify",
      TARGET to "verify",
    )

  fun parse(
    name: String,
    rest: List<String>,
    flags: OperationFlags = OperationFlags(),
  ): OperationInvocation {
    val pairs = rest.filter(::isKeyValue)
    val values = pairs.associate { pair -> pair.substringBefore(KEY_SEPARATOR) to pair.substringAfter(KEY_SEPARATOR) }
    if (values[CONFIRM]?.isBlank() == true) {
      throw UsageError("confirm: needs the token from the proposal's status line.")
    }
    OPERATION_ONLY_KEYS.forEach { (key, owner) ->
      if (key in values && name != owner) throw UsageError("$key: is accepted only by operation $owner.")
    }
    requireUpdateCheckFlagsOwner(name, flags)
    return OperationInvocation(
      operationId = name,
      arguments =
        OperationArguments(
          bump = values[BUMP],
          confirm = values[CONFIRM],
          select = values[SELECT],
          mode = values[MODE],
          scope = values[SCOPE],
          push = values[PUSH],
          replies = values[REPLIES],
          spec = values[SPEC],
          target = values[TARGET],
          includePrereleases = flags.includePrereleases,
          format = flags.format ?: OperationOutputFormat.TEXT,
        ),
      instructions = rest.filterNot(::isKeyValue).joinToString(" ").takeIf(String::isNotBlank),
    )
  }

  private fun requireUpdateCheckFlagsOwner(
    name: String,
    flags: OperationFlags,
  ) {
    if (name == UPDATE_CHECK) return
    val given =
      listOfNotNull(
        INCLUDE_PRERELEASES_OPTION.takeIf { flags.includePrereleases },
        FORMAT_OPTION.takeIf { flags.format != null },
      )
    given.firstOrNull()?.let { option -> throw UsageError("$option is accepted only by operation $UPDATE_CHECK.") }
  }

  private fun isKeyValue(value: String): Boolean =
    KEY_SEPARATOR in value && value.substringBefore(KEY_SEPARATOR) in KEYS
}

internal fun writeOperationResult(
  state: CliRunState,
  operationId: String,
  result: OperationResult,
) {
  val invocationLine = "Operation invocation ID: ${result.invocationId}"
  val (lines, exitCode) =
    when (val outcome = result.outcome) {
      is OperationOutcome.Completed -> listOf(outcome.text.trimEnd(), invocationLine) to 0
      is OperationOutcome.Blocked -> listOf("Operation '$operationId' blocked: ${outcome.reason}", invocationLine) to 1
      is OperationOutcome.Usage -> throw UsageError(outcome.reason)
      is OperationOutcome.Failed -> listOf("Operation '$operationId' failed: ${outcome.reason}", invocationLine) to 1
      is OperationOutcome.AwaitingConfirmation ->
        listOf(
          outcome.proposalSummary.trimEnd(),
          invocationLine,
          "status: awaiting_confirmation confirm:${outcome.token}",
        ) to OPERATION_EXIT_AWAITING_CONFIRMATION
    }
  state.completeText(lines.joinToString("\n", postfix = "\n"), emptyMap(), exitCode = exitCode)
}
