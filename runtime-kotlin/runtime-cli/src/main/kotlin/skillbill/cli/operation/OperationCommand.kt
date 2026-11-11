package skillbill.cli.operation

import com.github.ajalt.clikt.core.UsageError
import com.github.ajalt.clikt.parameters.arguments.argument
import com.github.ajalt.clikt.parameters.arguments.multiple
import com.github.ajalt.clikt.parameters.options.flag
import com.github.ajalt.clikt.parameters.options.multiple
import com.github.ajalt.clikt.parameters.options.option
import com.github.ajalt.clikt.parameters.types.choice
import me.tatarka.inject.annotations.Inject
import skillbill.cli.kernel.agent.detectInvokingAgentId
import skillbill.cli.kernel.agent.invokingAgentResolutionHelp
import skillbill.cli.kernel.agent.requireInvokingAgentId
import skillbill.cli.kernel.agent.resolveRequestedQaDevice
import skillbill.cli.kernel.cli.CliRunState
import skillbill.cli.kernel.cli.DocumentedCliCommand
import skillbill.cli.kernel.cli.resolveCliRepositoryRoot
import skillbill.cli.model.CliRunInputs
import skillbill.contracts.operationcard.OperationCardEnvironmentKeys
import skillbill.contracts.operationcard.OperationCardOperationIds
import skillbill.contracts.qa.QaDeviceLaunchTokens.QA_DEVICE_FLAG
import skillbill.engine.featuretask.model.execution.QaNamedDevice
import skillbill.engine.operation.core.OperationArguments
import skillbill.engine.operation.core.OperationExecutor
import skillbill.engine.operation.core.OperationOutcome
import skillbill.engine.operation.core.OperationOutputFormat
import skillbill.engine.operation.core.OperationRegistry
import skillbill.engine.operation.core.OperationRequest
import skillbill.engine.operation.core.OperationResult
import skillbill.workflow.taskruntime.validation.FeatureTaskRuntimeProviderLimitPause
import skillbill.workflow.verify.model.VerifyQaSelection

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
        "operator instructions for the operation's agent step. pr-review-fix accepts mode:local|remote (default " +
        "local: the current branch's open local diff comments; " +
        "push:/replies: and a PR need mode:remote), and push:on|off and replies:post|draft. " +
        "verify reads its intake from the free text (a Linear issue key or URL, " +
        "or the requirements) or spec:<path>, and alone accepts spec:, " +
        "target:<pr-number|branch|base..head> (default: " +
        "HEAD against origin/HEAD), mode:inline|delegated, and qa:on|off (default off; a confirm may repeat " +
        "but not change it); its confirm:<token> is the verify workflow id.",
  ).multiple()
  private val qaDevices by option(
    QA_DEVICE_FLAG,
    help =
      "verify with qa:on only: the device its QA step uses, serial:<serial> or avd:<name>. The proposal stores it " +
        "and a confirm may repeat but not change it.",
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
    val invocation =
      OperationInvocationParser.parse(
        name,
        rest,
        OperationFlags(includePrereleases, format, resolveRequestedQaDevice(qaDevices)),
      )
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
        launchId = inputs.environment[OperationCardEnvironmentKeys.LAUNCH_ID]?.trim()?.takeIf(String::isNotEmpty),
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
  val qaDevice: QaNamedDevice? = null,
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
  const val QA: String = "qa"
  val KEYS: List<String> = listOf(BUMP, CONFIRM, SELECT, MODE, SCOPE, PUSH, REPLIES, SPEC, TARGET, QA)
  const val INCLUDE_PRERELEASES_OPTION: String = "--include-prereleases"
  const val FORMAT_OPTION: String = "--format"
  private const val UPDATE_CHECK = "update-check"
  private const val KEY_SEPARATOR = ':'

  private val OPERATION_ONLY_KEYS: Map<String, Set<String>> =
    mapOf(
      PUSH to setOf("pr-review-fix"),
      REPLIES to setOf("pr-review-fix"),
      MODE to setOf("verify", "pr-review-fix"),
      SPEC to setOf("verify"),
      TARGET to setOf("verify"),
      QA to setOf("verify"),
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
    OPERATION_ONLY_KEYS.forEach { (key, owners) ->
      if (key in values && name !in owners) {
        throw UsageError("$key: is accepted only by operation ${owners.joinToString(" or ")}.")
      }
    }
    requireSingleQa(pairs)
    requireQaDeviceOwner(name, values[QA], flags.qaDevice)
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
          qa = values[QA],
          qaDevice = flags.qaDevice,
          includePrereleases = flags.includePrereleases,
          format = flags.format ?: OperationOutputFormat.TEXT,
        ),
      instructions = rest.filterNot(::isKeyValue).joinToString(" ").takeIf(String::isNotBlank),
    )
  }

  private fun requireSingleQa(pairs: List<String>) {
    val given =
      pairs.filter { pair -> pair.substringBefore(KEY_SEPARATOR) == QA }.map { it.substringAfter(KEY_SEPARATOR) }
    if (given.size < 2) return
    throw UsageError(
      if (given.distinct().size == 1) {
        "Duplicate $QA:${given.first()} is not allowed; supply $QA:on or $QA:off at most once."
      } else {
        "Conflicting $QA: values '${given.joinToString(", ")}' are not allowed; " +
          "supply exactly one of $QA:on or $QA:off."
      },
    )
  }

  private fun requireQaDeviceOwner(
    name: String,
    qa: String?,
    device: QaNamedDevice?,
  ) {
    if (device == null) return
    if (name != OperationCardOperationIds.VERIFY || qa != VerifyQaSelection.ON.wireValue) {
      throw UsageError(
        "$QA_DEVICE_FLAG is accepted only by operation ${OperationCardOperationIds.VERIFY} with " +
          "$QA:${VerifyQaSelection.ON.wireValue}.",
      )
    }
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
      is OperationOutcome.Blocked ->
        if (FeatureTaskRuntimeProviderLimitPause.isProviderLimitPauseReason(outcome.reason)) {
          listOf("Operation '$operationId' paused: ${outcome.reason}", invocationLine) to OPERATION_EXIT_PAUSED
        } else {
          listOf("Operation '$operationId' blocked: ${outcome.reason}", invocationLine) to 1
        }
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
