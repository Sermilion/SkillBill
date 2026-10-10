package skillbill.cli.phase

import com.github.ajalt.clikt.core.UsageError
import com.github.ajalt.clikt.parameters.arguments.argument
import com.github.ajalt.clikt.parameters.arguments.multiple
import com.github.ajalt.clikt.parameters.options.option
import me.tatarka.inject.annotations.Inject
import skillbill.application.config.ConfigResolutionService
import skillbill.application.review.model.ParallelCodeReviewResult
import skillbill.application.review.service.RequestedReviewMode
import skillbill.cli.kernel.agent.invokingAgentResolutionHelp
import skillbill.cli.kernel.agent.requireInvokingAgentId
import skillbill.cli.kernel.cli.CliRunState
import skillbill.cli.kernel.cli.DocumentedCliCommand
import skillbill.cli.kernel.cli.namedStandaloneScope
import skillbill.cli.kernel.cli.resolveCliRepositoryRoot
import skillbill.cli.kernel.cli.standaloneReportText
import skillbill.cli.kernel.cli.usageError
import skillbill.cli.kernel.plan.StandalonePlanLauncher
import skillbill.cli.kernel.plan.StandalonePlanOptions
import skillbill.cli.kernel.plan.planReportText
import skillbill.cli.model.CliRunInputs
import skillbill.engine.featuretask.model.core.FeatureTaskRuntimeModelAssignment
import skillbill.engine.featuretask.model.core.FeatureTaskRuntimeRunEvent
import skillbill.engine.featuretask.model.core.FeatureTaskRuntimeRunEventSink
import skillbill.engine.featuretask.model.review.ReviewInvocation
import skillbill.engine.featuretask.model.review.ReviewTarget
import skillbill.engine.featuretask.phaserun.PhaseRunEntry
import skillbill.engine.featuretask.phaserun.PhaseRunRequest
import skillbill.engine.featuretask.phaserun.PhaseRunResult
import skillbill.engine.goalrunner.plan.StandalonePlanResult
import skillbill.error.core.SkillBillRuntimeException
import skillbill.error.core.rethrowUnless
import skillbill.error.featuretask.UnknownPhaseReviewTargetError
import skillbill.error.shellcontent.isShellContentContractFailure
import skillbill.workflow.decomposition.model.SpecSource
import skillbill.workflow.taskruntime.model.skeleton.PhaseIntakeRequirement
import skillbill.workflow.taskruntime.model.skeleton.SkeletonDefinition
import skillbill.workflow.taskruntime.model.skeleton.SkeletonRunStateKind
import java.nio.file.Path

@Inject
class PhaseCommand(
  private val entry: PhaseRunEntry,
  private val planLauncher: StandalonePlanLauncher,
  private val configResolution: ConfigResolutionService,
  private val state: CliRunState,
  private val inputs: CliRunInputs,
) : DocumentedCliCommand(
    "phase",
    "Run one standalone phase (${PhaseInvocationParser.phaseNames().joinToString(", ")}) over the working tree. " +
      "Review, validation, pr and monitor keep no workflow state; plan is a durable, resumable workflow that " +
      "stops after the spec bundle.",
  ) {
  private val name by argument(
    name = "name",
    help = "Phase to run: ${PhaseInvocationParser.expectedList(PhaseInvocationParser.phaseNames())}.",
  )
  private val rest by argument(
    name = "args",
    help =
      "Optional intake text, then key:value pairs: ${PhaseCommandKeys.MODE}:inline|delegated " +
        "(omission means inline) and ${PhaseCommandKeys.TARGET}:HEAD|uncommitted|" +
        "${PhaseInvocationParser.SCOPED_TARGETS.joinToString("|")}|" +
        "<commit-sha|branch|tag>. An omitted target reviews " +
        "uncommitted changes when the worktree is dirty and HEAD otherwise.",
  ).multiple()
  private val agent by option(
    "--agent",
    help = "Agent the phase launches. " + invokingAgentResolutionHelp("--agent"),
  )

  override fun run() {
    val invocation = PhaseInvocationParser.parse(name, rest)
    val repoRoot = resolveCliRepositoryRoot(null, inputs)
    val invokedAgentId = requireInvokingAgentId(agent, inputs.environment, "--agent")
    if (SkeletonDefinition.byId(invocation.definitionId).runStateKind == SkeletonRunStateKind.DURABLE) {
      runDurablePlan(invocation, repoRoot, invokedAgentId)
      return
    }
    val stdoutSink =
      FeatureTaskRuntimeRunEventSink { event ->
        event.phaseProgressLine()?.let(inputs.liveStdout)
      }
    val result =
      runPhase(state) {
        val specBacked = SkeletonDefinition.byId(invocation.definitionId).intake != PhaseIntakeRequirement.OPTIONAL
        val specSource =
          if (specBacked) {
            SpecSource.fromWireValue(configResolution.resolveSpecType(repoRoot, explicit = null).id)
          } else {
            null
          }
        entry.run(
          PhaseRunRequest(
            definitionId = invocation.definitionId,
            repoRoot = repoRoot,
            invokedAgentId = invokedAgentId,
            intake = invocation.intake,
            codeReviewMode = invocation.mode?.let(RequestedReviewMode::parse),
            reviewInvocation = ReviewInvocation(target = invocation.target),
            specSource = specSource ?: SpecSource.LOCAL,
            modelAssignment =
              FeatureTaskRuntimeModelAssignment(matrix = configResolution.resolveExecutionMatrix()),
            eventSink = stdoutSink,
          ),
        )
      } ?: return
    writePhaseResult(state, invocation.definitionId, result)
  }

  private fun runDurablePlan(
    invocation: PhaseInvocation,
    repoRoot: Path,
    invokedAgentId: String,
  ) {
    val intake = requireNotNull(invocation.intake) { "Durable phases require an intake." }
    try {
      val issueKey = planLauncher.issueKeyOf(intake)
      val stdoutSink =
        FeatureTaskRuntimeRunEventSink { event ->
          event.phaseProgressLine()?.let(inputs.liveStdout)
        }
      val result =
        planLauncher.run(
          issueKey,
          intake,
          repoRoot,
          invokedAgentId,
          options =
            StandalonePlanOptions(
              eventSink = stdoutSink,
            ),
        )
      state.completeText(
        result.planReportText(issueKey),
        emptyMap(),
        exitCode = if (result is StandalonePlanResult.Completed) 0 else 1,
      )
    } catch (error: SkillBillRuntimeException) {
      error.rethrowUnless(error.isShellContentContractFailure())
      state.completeText(error.message.orEmpty(), emptyMap(), exitCode = 1)
    }
  }
}

internal fun runPhase(
  state: CliRunState,
  run: () -> PhaseRunResult,
): PhaseRunResult? =
  try {
    run()
  } catch (error: UnknownPhaseReviewTargetError) {
    usageError(error)
  } catch (error: SkillBillRuntimeException) {
    error.rethrowUnless(error.isShellContentContractFailure())
    state.completeText(error.message.orEmpty(), emptyMap(), exitCode = 1)
    null
  }

internal const val PHASE_COLON_PREFIX = "phase:"

object PhaseCommandKeys {
  const val MODE: String = "mode"
  const val TARGET: String = "target"
}

data class PhaseInvocation(
  val definitionId: String,
  val intake: String?,
  val mode: String?,
  val target: ReviewTarget?,
)

object PhaseInvocationParser {
  private const val KEY_SEPARATOR = ':'
  private const val HEAD_TARGET = "HEAD"
  private const val LAST_TARGET = "last"
  private const val UNCOMMITTED_TARGET = "uncommitted"
  private const val COMMIT_PUSH = "commit_push"
  private const val VERIFY_NAME = "verify"
  val SCOPED_TARGETS: List<String> = listOf("pr", "staged", "unstaged")
  private val KEYS = setOf(PhaseCommandKeys.MODE, PhaseCommandKeys.TARGET)

  fun phaseNames(): List<String> =
    SkeletonDefinition.entries.filter(SkeletonDefinition::standaloneInvocable).map(SkeletonDefinition::id)

  fun parse(
    name: String,
    rest: List<String>,
  ): PhaseInvocation {
    val definitionId = definitionId(name)
    val pairs = rest.filter(::isKeyValue)
    val intake = rest.filterNot(::isKeyValue).joinToString(" ").takeIf(String::isNotBlank)
    val intakeRequirement = SkeletonDefinition.byId(definitionId).intake
    if (intake == null && intakeRequirement != PhaseIntakeRequirement.OPTIONAL) {
      throw UsageError("Phase '$definitionId' requires an intake (${intakeRequirement.wireValue}).")
    }
    val values = pairs.associate { pair -> pair.substringBefore(KEY_SEPARATOR) to pair.substringAfter(KEY_SEPARATOR) }
    return PhaseInvocation(
      definitionId = definitionId,
      intake = intake,
      mode = values[PhaseCommandKeys.MODE]?.let(::mode),
      target = values[PhaseCommandKeys.TARGET]?.let(::target),
    )
  }

  private fun definitionId(name: String): String {
    val phaseName = name.removePrefix(PHASE_COLON_PREFIX).ifEmpty { name }
    if (phaseName == VERIFY_NAME) {
      throw UsageError(
        "`$VERIFY_NAME` is an operation, not a phase; use `skill-bill operation $VERIFY_NAME <intake>`.",
      )
    }
    val names = phaseNames()
    if (phaseName in names) return phaseName
    throw UsageError(
      when {
        phaseName == COMMIT_PUSH ->
          "Phase '$COMMIT_PUSH' is not runnable on its own; run the full feature-task workflow."
        SkeletonDefinition.entries.any { it.id == phaseName } ->
          "Phase '$phaseName' runs over durable workflow state; expected ${expectedList(names)}."
        else -> "Unknown phase '$phaseName'; expected ${expectedList(names)}."
      },
    )
  }

  fun expectedList(names: List<String>): String =
    if (names.size < 2) names.joinToString() else "${names.dropLast(1).joinToString(", ")}, or ${names.last()}"

  private fun isKeyValue(value: String): Boolean =
    KEY_SEPARATOR in value && value.substringBefore(KEY_SEPARATOR) in KEYS

  private fun mode(value: String): String =
    value.takeIf(RequestedReviewMode::isKnown)
      ?: throw UsageError(
        "Unknown ${PhaseCommandKeys.MODE} '$value'; expected ${RequestedReviewMode.inlineWireValue} or " +
          "${RequestedReviewMode.delegatedWireValue} (${RequestedReviewMode.autoWireValue} resolves inline).",
      )

  private fun target(value: String): ReviewTarget {
    if (value.isBlank() || value.any(Char::isWhitespace)) {
      throw UsageError(
        "Unknown ${PhaseCommandKeys.TARGET} '$value'; expected $HEAD_TARGET, $UNCOMMITTED_TARGET, " +
          "${SCOPED_TARGETS.joinToString(", ")}, or a commit sha, branch, or tag.",
      )
    }
    if (value == UNCOMMITTED_TARGET) return ReviewTarget.Uncommitted
    if (value.equals(LAST_TARGET, ignoreCase = true)) return ReviewTarget.Commit(HEAD_TARGET)
    return namedStandaloneScope(value)?.let { scope -> ReviewTarget.Scoped(scope) } ?: ReviewTarget.Commit(value)
  }
}

internal fun writePhaseResult(
  state: CliRunState,
  definitionId: String,
  result: PhaseRunResult,
) {
  val review = result.reviewResult
  val register =
    when {
      result is PhaseRunResult.Completed && review != null -> review.standaloneReportText()
      else -> review?.rawOutput?.takeIf(String::isNotBlank) ?: review?.output
    }
  when (result) {
    is PhaseRunResult.Completed ->
      state.completeText(
        listOfNotNull(
          register ?: result.completedOutputs.takeIf { it.isNotEmpty() }?.joinToString("\n") ?: result.value,
          result.specBundle?.let { bundle ->
            (
              listOf("Parent spec: ${bundle.parentSpecPath}", "Manifest: ${bundle.decompositionManifestPath}") +
                bundle.subtaskSpecPaths.map { path -> "Subtask spec: $path" }
            ).joinToString("\n")
          },
          "Phase invocation ID: ${result.invocationId}",
        ).joinToString("\n"),
        emptyMap(),
        exitCode = 0,
      )
    is PhaseRunResult.Blocked ->
      state.completeText(
        listOfNotNull(
          result.completedOutputs.takeIf { it.isNotEmpty() }?.joinToString("\n"),
          register?.let { "Unaccepted report output:\n$it" },
          review?.output?.takeIf { it.isNotBlank() && it != register }?.let { "Retained findings:\n$it" },
          "Phase '$definitionId' blocked at '${result.stepId}': ${result.reason}",
          review?.let(::blockedReviewDetails)?.takeIf(String::isNotBlank),
          "Phase invocation ID: ${result.invocationId}",
        ).joinToString("\n"),
        emptyMap(),
        exitCode = 1,
      )
  }
}

private fun blockedReviewDetails(review: ParallelCodeReviewResult): String =
  listOfNotNull(
    review.integration?.failureReason,
    review.integration?.takeIf { it.rawOutput.isNotBlank() }?.let { integration ->
      "Unaccepted integration output:\n${integration.rawOutput}" +
        if (integration.outputTruncated) "\nIntegration output was truncated." else ""
    },
    review.coverage?.render()?.takeIf(String::isNotBlank),
    review.rejectedCandidateCount.takeIf { it > 0 }?.let { "Rejected finding candidates: $it." },
    review.citationDiagnostics.takeIf {
      it.isNotEmpty()
    }?.take(MAX_CITATION_DIAGNOSTICS)?.joinToString("\n") { it.toString() },
  ).joinToString("\n")

private const val MAX_CITATION_DIAGNOSTICS = 5

internal fun FeatureTaskRuntimeRunEvent.phaseProgressLine(): String? =
  when (this) {
    is FeatureTaskRuntimeRunEvent.PhaseStarted -> "Phase '$phaseId' ${if (resumed) "resumed" else "started"}.\n"
    is FeatureTaskRuntimeRunEvent.PhaseCompleted -> "Phase '$phaseId' completed.\n"
    is FeatureTaskRuntimeRunEvent.PhaseBlocked -> "Phase '$phaseId' blocked: $blockedReason\n"
    is FeatureTaskRuntimeRunEvent.PhasePaused -> "Phase '$phaseId' paused: $pauseReason\n"
    else -> null
  }
