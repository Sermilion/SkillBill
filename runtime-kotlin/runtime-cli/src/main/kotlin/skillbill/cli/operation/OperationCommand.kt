package skillbill.cli.operation

import com.github.ajalt.clikt.core.UsageError
import com.github.ajalt.clikt.parameters.arguments.argument
import com.github.ajalt.clikt.parameters.arguments.multiple
import com.github.ajalt.clikt.parameters.options.option
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
import skillbill.engine.operation.core.OperationRegistry
import skillbill.engine.operation.core.OperationRequest
import skillbill.engine.operation.core.OperationResult
import skillbill.error.operation.OperationUsageError

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
        "operator instructions for the operation's agent step.",
  ).multiple()
  private val agent by option(
    "--agent",
    help = "Agent an operation's agent step launches. " + invokingAgentResolutionHelp("--agent"),
  )

  override fun run() {
    val invocation = OperationInvocationParser.parse(name, rest)
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
    val result =
      try {
        executor.execute(request)
      } catch (error: OperationUsageError) {
        throw UsageError(error.message.orEmpty())
      }
    writeOperationResult(state, request.operationId, result)
  }
}

data class OperationInvocation(
  val operationId: String,
  val arguments: OperationArguments,
  val instructions: String?,
)

object OperationInvocationParser {
  const val BUMP: String = "bump"
  const val CONFIRM: String = "confirm"
  const val SELECT: String = "select"
  const val MODE: String = "mode"
  const val SCOPE: String = "scope"
  val KEYS: List<String> = listOf(BUMP, CONFIRM, SELECT, MODE, SCOPE)
  private const val KEY_SEPARATOR = ':'

  fun parse(
    name: String,
    rest: List<String>,
  ): OperationInvocation {
    val pairs = rest.filter(::isKeyValue)
    val values = pairs.associate { pair -> pair.substringBefore(KEY_SEPARATOR) to pair.substringAfter(KEY_SEPARATOR) }
    // A blank confirm: would otherwise run as a fresh proposal and supersede the token the operator meant to confirm.
    if (values[CONFIRM]?.isBlank() == true) {
      throw UsageError("confirm: needs the token from the proposal's status line.")
    }
    return OperationInvocation(
      operationId = name,
      arguments =
        OperationArguments(
          bump = values[BUMP],
          confirm = values[CONFIRM],
          select = values[SELECT],
          mode = values[MODE],
          scope = values[SCOPE],
        ),
      instructions = rest.filterNot(::isKeyValue).joinToString(" ").takeIf(String::isNotBlank),
    )
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
  // The machine-readable status line stays last so a relaying agent finds the token on the final line.
  val (lines, exitCode) =
    when (val outcome = result.outcome) {
      is OperationOutcome.Completed -> listOf(outcome.text.trimEnd(), invocationLine) to 0
      is OperationOutcome.Blocked -> listOf("Operation '$operationId' blocked: ${outcome.reason}", invocationLine) to 1
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
