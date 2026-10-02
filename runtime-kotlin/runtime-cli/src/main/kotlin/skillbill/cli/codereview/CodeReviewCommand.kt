package skillbill.cli.codereview

import com.github.ajalt.clikt.core.UsageError
import com.github.ajalt.clikt.parameters.arguments.argument
import com.github.ajalt.clikt.parameters.arguments.optional
import com.github.ajalt.clikt.parameters.options.default
import com.github.ajalt.clikt.parameters.options.multiple
import com.github.ajalt.clikt.parameters.options.option
import com.github.ajalt.clikt.parameters.types.choice
import com.github.ajalt.clikt.parameters.types.long
import me.tatarka.inject.annotations.Inject
import skillbill.application.review.model.ParallelCodeReviewRequest
import skillbill.application.review.model.ParallelCodeReviewResult
import skillbill.application.review.model.ParallelReviewLaneStatus
import skillbill.application.review.model.ReviewPrelaunchExpansion
import skillbill.application.review.service.RequestedReviewMode
import skillbill.cli.kernel.agent.invokingAgentResolutionHelp
import skillbill.cli.kernel.agent.requireInvokingAgentId
import skillbill.cli.kernel.cli.CliRunState
import skillbill.cli.kernel.cli.DEFAULT_CODE_REVIEW_SCOPE
import skillbill.cli.kernel.cli.DocumentedCliCommand
import skillbill.cli.kernel.cli.StandaloneCodeReviewTarget
import skillbill.cli.kernel.cli.resolveCliRepositoryRoot
import skillbill.cli.kernel.cli.resolveStandaloneCodeReviewTarget
import skillbill.cli.kernel.cli.usageError
import skillbill.cli.model.CliRunInputs
import skillbill.engine.featuretask.model.review.ReviewInvocation
import skillbill.engine.featuretask.model.review.ReviewTarget
import skillbill.engine.featuretask.phaserun.PhaseRunEntry
import skillbill.engine.featuretask.phaserun.PhaseRunRequest
import skillbill.engine.featuretask.phaserun.PhaseRunResult
import skillbill.error.core.SkillBillRuntimeException
import skillbill.error.core.rethrowUnless
import skillbill.error.shellcontent.ReviewAggregationIntegrityError
import skillbill.error.shellcontent.isShellContentContractFailure
import skillbill.workflow.model.goalreview.toReviewAccountingBoundedJson
import skillbill.workflow.taskruntime.model.skeleton.SkeletonDefinition
import java.nio.file.Path
import kotlin.time.Duration.Companion.minutes

@Inject
class CodeReviewCommand(
  private val entry: PhaseRunEntry,
  private val state: CliRunState,
  private val inputs: CliRunInputs,
) : DocumentedCliCommand(
    "code-review",
    "Run the in-memory review phase: both modes run the whole code_review slot and fix findings in the working " +
      "tree. Inline reviews through InlineReviewStrategy; delegated runs the multi-agent review, then " +
      "verify_findings and implement_fix.",
  ) {
  private val commitArgument by argument(
    name = "commit",
    help = "Review target: pr, last, a commit SHA, uncommitted, staged, or unstaged.",
  ).optional()
  private val agent1 by option(
    "--agent1",
    help = "Agent for the default lane. " + invokingAgentResolutionHelp("--agent1"),
  )
  private val scope by option(
    "--scope",
    help = "Diff scope: staged, unstaged, uncommitted, branch (default), or pr.",
  ).choice("staged", "unstaged", "uncommitted", "branch", "pr").default(DEFAULT_CODE_REVIEW_SCOPE)
  private val repoRoot by option(
    "--repo-root",
    help = "Repository root for diff and agent runs. Defaults to the invocation repository root.",
  )
  private val timeoutMinutes by option(
    "--timeout-minutes",
    help = "Optional per-lane wall-clock cap in minutes.",
  ).long()
  private val diffFile by option(
    "--diff-file",
    help = "Exact diff input for both lanes. When supplied, it replaces the configured review scope.",
  )
  private val baseRevision by option(
    "--base-revision",
    help = "Immutable base identity for --diff-file. Must be paired with --head-revision.",
  )
  private val headRevision by option(
    "--head-revision",
    help = "Immutable head identity for --diff-file. Must be paired with --base-revision.",
  )
  private val expandFiles by option(
    "--expand-file",
    help = "Governed prelaunch whole-file evidence as LANE:PATH=REACHABILITY_REASON. Repeatable.",
  ).multiple()
  private val codeReviewMode by option(
    "--execution-mode",
    help =
      "Execution mode: inline (default, one review prompt), auto (resolves inline), " +
        "or delegated (parent launches specialists; parent authors the final prose result).",
  ).default(RequestedReviewMode.defaultWireValue)
  private val baselineUntrackedIncludes by option(
    "--baseline-untracked-include",
    help = "Baseline-untracked path to include in the packet. Repeatable.",
  ).multiple()
  private val baselineUntrackedExcludes by option(
    "--baseline-untracked-exclude",
    help = "Baseline-untracked path to exclude from the packet. Repeatable.",
  ).multiple()
  private val reviewRunId by option(
    "--review-run-id",
    help =
      "Review run id (rvw-YYYYMMDD-HHMMSS-XXXX) this review will report. Pass the same id used " +
        "in the review output and import so review accounting is reachable from review_finished telemetry.",
  )
  private val reviewSessionId by option(
    "--review-session-id",
    help =
      "Review session id keying session_learnings and the printed 'Review session ID:' line. " +
        "Omit it and the driver mints one for this run.",
  )

  override fun run() {
    val resolvedAgent1 = resolveAgent1()
    val repo = resolveCliRepositoryRoot(repoRoot, inputs)
    validateCommitTarget()
    val target = resolveStandaloneCodeReviewTarget(commitArgument, scope)
    val flags =
      CodeReviewFlags(
        agentId = resolvedAgent1,
        repoRoot = repo,
        target = target,
        executionMode = codeReviewMode,
        diffFile = diffFile,
        baseRevision = baseRevision,
        headRevision = headRevision,
        expandFiles = expandFiles,
        baselineUntrackedIncludes = baselineUntrackedIncludes,
        baselineUntrackedExcludes = baselineUntrackedExcludes,
        reviewRunId = reviewRunId,
        reviewSessionId = reviewSessionId,
        timeoutMinutes = timeoutMinutes,
      )
    val result = runPhaseReview(entry, codeReviewPhaseRequest(flags), state) ?: return
    writePhaseReviewResult(state, result)
  }

  private fun resolveAgent1(): String = requireInvokingAgentId(agent1, inputs.environment, "--agent1")

  private fun validateCommitTarget() {
    if (commitArgument.isNullOrBlank()) return
    val error =
      when {
        diffFile != null -> "A positional review target cannot be combined with --diff-file."
        !baseRevision.isNullOrBlank() || !headRevision.isNullOrBlank() ->
          "A positional review target cannot be combined with --base-revision or --head-revision."
        else -> null
      }
    if (error != null) {
      throw UsageError(error)
    }
  }
}

internal data class CodeReviewFlags(
  val agentId: String,
  val repoRoot: Path,
  val target: StandaloneCodeReviewTarget,
  val executionMode: String = RequestedReviewMode.defaultWireValue,
  val diffFile: String? = null,
  val baseRevision: String? = null,
  val headRevision: String? = null,
  val expandFiles: List<String> = emptyList(),
  val baselineUntrackedIncludes: List<String> = emptyList(),
  val baselineUntrackedExcludes: List<String> = emptyList(),
  val reviewRunId: String? = null,
  val reviewSessionId: String? = null,
  val timeoutMinutes: Long? = null,
)

internal fun codeReviewPhaseRequest(flags: CodeReviewFlags): PhaseRunRequest {
  val (resolvedBase, resolvedHead) =
    resolveCodeReviewRevisions(flags.target.commitRevision, flags.baseRevision, flags.headRevision)
  val revisionError =
    when {
      (resolvedBase == null) != (resolvedHead == null) ->
        "--base-revision and --head-revision must be supplied together."
      flags.diffFile != null && resolvedBase == null ->
        "--diff-file requires paired --base-revision and --head-revision."
      else -> null
    }
  if (revisionError != null) {
    throw UsageError(revisionError)
  }
  val mode = RequestedReviewMode.parse(flags.executionMode)
  val delegatedOnly = flags.expandFiles + flags.baselineUntrackedIncludes + flags.baselineUntrackedExcludes
  if (!RequestedReviewMode.isDelegated(mode) && delegatedOnly.isNotEmpty()) {
    throw UsageError(
      "--expand-file and --baseline-untracked-include/-exclude apply only to --execution-mode delegated.",
    )
  }
  val suppliedDiffPath = flags.diffFile?.let { value -> Path.of(value).toAbsolutePath().normalize() }
  return PhaseRunRequest(
    definitionId = SkeletonDefinition.REVIEW.id,
    repoRoot = flags.repoRoot,
    invokedAgentId = flags.agentId,
    codeReviewMode = mode,
    reviewInvocation =
      ReviewInvocation(
        target = ReviewTarget.Scoped(flags.target.scope, resolvedBase, resolvedHead, suppliedDiffPath),
        reviewRunId = flags.reviewRunId?.takeIf(String::isNotBlank),
        reviewSessionId = flags.reviewSessionId?.takeIf(String::isNotBlank),
        prelaunchExpansions = flags.expandFiles.map(::parseExpansion),
        baselineUntrackedPolicy =
          ParallelCodeReviewRequest.baselineUntrackedPolicy(
            flags.baselineUntrackedIncludes,
            flags.baselineUntrackedExcludes,
          ),
      ),
    timeout = flags.timeoutMinutes?.minutes,
  )
}

private fun parseExpansion(value: String): ReviewPrelaunchExpansion {
  val laneSeparator = value.indexOf(':')
  val reasonSeparator = value.indexOf('=', startIndex = laneSeparator + 1)
  if (laneSeparator <= 0 || reasonSeparator <= laneSeparator + 1 || reasonSeparator == value.lastIndex) {
    throw UsageError("--expand-file must use LANE:PATH=REACHABILITY_REASON with non-blank values.")
  }
  val prefix = value.substring(0, laneSeparator)
  val remainder = value.substring(laneSeparator + 1, reasonSeparator)
  val skill = remainder.substringBefore(':')
  val resolvedLaneSeparator =
    if (
      isPlatformLanePrefix(prefix) &&
      ':' in remainder &&
      skill.matches(Regex("bill-[a-z0-9]+(?:-[a-z0-9]+)*"))
    ) {
      value.indexOf(':', startIndex = laneSeparator + 1)
    } else {
      laneSeparator
    }
  return ReviewPrelaunchExpansion(
    lane = value.substring(0, resolvedLaneSeparator),
    path = value.substring(resolvedLaneSeparator + 1, reasonSeparator),
    reachabilityReason = value.substring(reasonSeparator + 1),
  )
}

private fun isPlatformLanePrefix(prefix: String): Boolean =
  !prefix.startsWith("bill-") && prefix != "parallel-code-review"

internal fun resolveCodeReviewRevisions(
  commitTarget: String?,
  baseRevision: String?,
  headRevision: String?,
): Pair<String?, String?> {
  val target = commitTarget?.takeIf(String::isNotBlank)
  if (target != null) return "$target^" to target
  return baseRevision?.takeIf(String::isNotBlank) to headRevision?.takeIf(String::isNotBlank)
}

private fun runPhaseReview(
  entry: PhaseRunEntry,
  request: PhaseRunRequest,
  state: CliRunState,
): PhaseRunResult? =
  try {
    entry.run(request)
  } catch (error: SkillBillRuntimeException) {
    error.rethrowUnless(error.isShellContentContractFailure())
    usageError(error)
  } catch (error: ReviewAggregationIntegrityError) {
    state.completeText(error.message.orEmpty(), emptyMap(), exitCode = 1)
    null
  }

private fun writePhaseReviewResult(
  state: CliRunState,
  phase: PhaseRunResult,
) {
  val blocked = (phase as? PhaseRunResult.Blocked)?.reason
  val result = phase.reviewResult
  if (result == null) {
    state.completeText(blocked ?: "The review phase produced no review result.", emptyMap(), exitCode = 1)
    return
  }
  writeParallelReviewResult(state, result, blocked)
}

private fun writeParallelReviewResult(
  state: CliRunState,
  result: ParallelCodeReviewResult,
  blocked: String?,
) {
  val parent = result.lane1
  val exitCode = if (parent.success && blocked == null) 0 else 1
  val output =
    buildString {
      append(laneStatusOutput(listOf(parent), result.output))
      blocked?.let { reason ->
        appendLine()
        append("# Review phase blocked — $reason")
      }
      result.reviewSessionId?.let { sessionId ->
        appendLine()
        appendLine("Review session ID: $sessionId")
        append("Applied learnings: ${result.appliedLearnings.orEmpty()}")
      }
      laneDiagnosticsOutput(listOf(parent))?.let { diagnostics ->
        appendLine()
        append(diagnostics)
      }
      result.coverage?.let { coverage ->
        appendLine()
        append(coverage.render())
      }
      result.accountingSummary?.let { summary ->
        appendLine()
        append("# Review accounting — ")
        append(summary.toReviewAccountingBoundedJson())
      }
    }
  state.completeText(output, emptyMap(), exitCode = exitCode)
}

private fun laneStatusOutput(
  lanes: List<ParallelReviewLaneStatus>,
  register: String,
): String {
  if (lanes.all(ParallelReviewLaneStatus::success)) return register
  val summary =
    lanes.joinToString(" | ") { lane ->
      if (lane.success) "${lane.agentId}: ok" else "${lane.agentId}: failed (${lane.failureReason ?: "unknown reason"})"
    }
  return "# Lane status — $summary\n$register"
}

private fun laneDiagnosticsOutput(lanes: List<ParallelReviewLaneStatus>): String? =
  lanes
    .mapNotNull { lane -> lane.droppedCandidateDiagnostic?.let { "${lane.agentId}: $it" } }
    .takeIf { it.isNotEmpty() }
    ?.joinToString(" | ", prefix = "# Lane diagnostics — ")
