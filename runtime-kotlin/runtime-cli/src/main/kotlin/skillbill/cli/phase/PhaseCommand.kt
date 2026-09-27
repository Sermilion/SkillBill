package skillbill.cli.phase

import com.github.ajalt.clikt.core.UsageError
import com.github.ajalt.clikt.parameters.arguments.argument
import com.github.ajalt.clikt.parameters.arguments.multiple
import com.github.ajalt.clikt.parameters.options.option
import me.tatarka.inject.annotations.Inject
import skillbill.cli.kernel.agent.invokingAgentResolutionHelp
import skillbill.cli.kernel.agent.requireInvokingAgentId
import skillbill.cli.kernel.cli.CliRunState
import skillbill.cli.kernel.cli.DocumentedCliCommand
import skillbill.cli.kernel.cli.resolveCliRepositoryRoot
import skillbill.cli.model.CliRunInputs
import skillbill.engine.featuretask.model.review.ReviewInvocation
import skillbill.engine.featuretask.model.review.ReviewTarget
import skillbill.engine.featuretask.phaserun.PhaseRunEntry
import skillbill.engine.featuretask.phaserun.PhaseRunRequest
import skillbill.engine.featuretask.phaserun.PhaseRunResult
import skillbill.error.core.ShellContentContractException
import skillbill.error.featuretask.UnknownPhaseReviewTargetError
import skillbill.review.context.model.launch.CodeReviewExecutionMode
import skillbill.workflow.taskruntime.phase.task.SkeletonDefinition
import skillbill.workflow.taskruntime.phase.task.SkeletonRunStateKind

@Inject
class PhaseCommand(
  private val entry: PhaseRunEntry,
  private val state: CliRunState,
  private val inputs: CliRunInputs,
) : DocumentedCliCommand(
    "phase",
    "Run one in-memory phase (review or validation) over the working tree, with no workflow state.",
  ) {
  private val name by argument(
    name = "name",
    help = "Phase to run: ${PhaseInvocationParser.phaseNames().joinToString(" or ")}.",
  )
  private val rest by argument(
    name = "args",
    help =
      "Optional intake text, then key:value pairs: ${PhaseCommandKeys.MODE}:inline|delegated|auto " +
        "and ${PhaseCommandKeys.TARGET}:HEAD|uncommitted|<commit-sha|branch|tag>. An omitted target reviews " +
        "uncommitted changes when the worktree is dirty and HEAD otherwise.",
  ).multiple()
  private val agent by option(
    "--agent",
    help = "Agent the phase launches. " + invokingAgentResolutionHelp("--agent"),
  )

  override fun run() {
    val invocation = PhaseInvocationParser.parse(name, rest)
    val request =
      PhaseRunRequest(
        definitionId = invocation.definitionId,
        repoRoot = resolveCliRepositoryRoot(null, inputs),
        invokedAgentId = requireInvokingAgentId(agent, inputs.environment, "--agent"),
        intake = invocation.intake,
        codeReviewMode = invocation.mode,
        reviewInvocation = ReviewInvocation(target = invocation.target),
      )
    val result = runPhase(state) { entry.run(request) } ?: return
    writePhaseResult(state, invocation.definitionId, result)
  }
}

internal fun runPhase(
  state: CliRunState,
  run: () -> PhaseRunResult,
): PhaseRunResult? =
  try {
    run()
  } catch (error: UnknownPhaseReviewTargetError) {
    throw UsageError(error.message.orEmpty())
  } catch (error: ShellContentContractException) {
    state.completeText(error.message.orEmpty(), emptyMap(), exitCode = 1)
    null
  }

object PhaseCommandKeys {
  const val MODE: String = "mode"
  const val TARGET: String = "target"
}

data class PhaseInvocation(
  val definitionId: String,
  val intake: String?,
  val mode: CodeReviewExecutionMode?,
  val target: ReviewTarget?,
)

object PhaseInvocationParser {
  private const val KEY_SEPARATOR = ':'
  private const val HEAD_TARGET = "HEAD"
  private const val UNCOMMITTED_TARGET = "uncommitted"
  private const val COMMIT_PUSH = "commit_push"
  private val KEYS = setOf(PhaseCommandKeys.MODE, PhaseCommandKeys.TARGET)

  fun phaseNames(): List<String> =
    SkeletonDefinition.entries.filter { it.runStateKind == SkeletonRunStateKind.IN_MEMORY }.map(SkeletonDefinition::id)

  fun parse(
    name: String,
    rest: List<String>,
  ): PhaseInvocation {
    val definitionId = definitionId(name)
    val pairs = rest.filter(::isKeyValue)
    val intake = rest.filterNot(::isKeyValue).joinToString(" ").takeIf(String::isNotBlank)
    val values = pairs.associate { pair -> pair.substringBefore(KEY_SEPARATOR) to pair.substringAfter(KEY_SEPARATOR) }
    return PhaseInvocation(
      definitionId = definitionId,
      intake = intake,
      mode = values[PhaseCommandKeys.MODE]?.let(::mode),
      target = values[PhaseCommandKeys.TARGET]?.let(::target),
    )
  }

  private fun definitionId(name: String): String {
    val names = phaseNames()
    return when {
      name in names -> name
      name == COMMIT_PUSH ->
        throw UsageError("Phase '$COMMIT_PUSH' is not runnable on its own; run the full feature-task workflow.")
      SkeletonDefinition.entries.any { it.id == name } ->
        throw UsageError("Phase '$name' runs over durable workflow state; expected ${names.joinToString(" or ")}.")
      else -> throw UsageError("Unknown phase '$name'; expected ${names.joinToString(" or ")}.")
    }
  }

  // Only known keys split off, so intake words such as `Foo.kt:12` or URLs stay intake.
  private fun isKeyValue(value: String): Boolean =
    KEY_SEPARATOR in value && value.substringBefore(KEY_SEPARATOR) in KEYS

  private fun mode(value: String): CodeReviewExecutionMode =
    CodeReviewExecutionMode.entries.firstOrNull { it.wireValue == value }
      ?: throw UsageError(
        "Unknown ${PhaseCommandKeys.MODE} '$value'; expected ${CodeReviewExecutionMode.INLINE.wireValue} or " +
          "${CodeReviewExecutionMode.DELEGATED.wireValue} (${CodeReviewExecutionMode.AUTO.wireValue} resolves inline).",
      )

  private fun target(value: String): ReviewTarget =
    when {
      value.isBlank() || value.any(Char::isWhitespace) ->
        throw UsageError(
          "Unknown ${PhaseCommandKeys.TARGET} '$value'; expected $HEAD_TARGET, $UNCOMMITTED_TARGET, or a commit " +
            "sha, branch, or tag.",
        )
      value == UNCOMMITTED_TARGET -> ReviewTarget.Uncommitted
      else -> ReviewTarget.Commit(value)
    }
}

private fun writePhaseResult(
  state: CliRunState,
  definitionId: String,
  result: PhaseRunResult,
) {
  val register = result.reviewResult?.output
  when (result) {
    is PhaseRunResult.Completed ->
      state.completeText(
        listOfNotNull(register ?: result.value, "Phase invocation ID: ${result.invocationId}").joinToString("\n"),
        emptyMap(),
        exitCode = 0,
      )
    is PhaseRunResult.Blocked ->
      state.completeText(
        listOfNotNull(
          register,
          "Phase '$definitionId' blocked at '${result.stepId}': ${result.reason}",
          "Phase invocation ID: ${result.invocationId}",
        ).joinToString("\n"),
        emptyMap(),
        exitCode = 1,
      )
  }
}
