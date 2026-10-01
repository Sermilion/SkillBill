package skillbill.cli.goal.control

import com.github.ajalt.clikt.core.UsageError
import com.github.ajalt.clikt.parameters.arguments.argument
import com.github.ajalt.clikt.parameters.options.flag
import com.github.ajalt.clikt.parameters.options.option
import com.github.ajalt.clikt.parameters.options.required
import com.github.ajalt.clikt.parameters.types.int
import me.tatarka.inject.annotations.Inject
import skillbill.cli.goal.run.goalPauseText
import skillbill.cli.goal.run.goalResumeText
import skillbill.cli.goal.run.goalStopText
import skillbill.cli.goal.run.toGoalPauseCliMap
import skillbill.cli.goal.run.toGoalResumeCliMap
import skillbill.cli.goal.run.toGoalStopCliMap
import skillbill.cli.kernel.cli.CliRunState
import skillbill.cli.kernel.cli.DocumentedCliCommand
import skillbill.cli.kernel.cli.resolveCliRepositoryRoot
import skillbill.cli.model.CliRunInputs
import skillbill.engine.goalrunner.GoalOperatorDecisionService
import skillbill.engine.goalrunner.model.GoalRunnerAcceptRequest
import skillbill.engine.goalrunner.model.GoalRunnerOperatorDecisionRequest
import skillbill.engine.goalrunner.model.GoalRunnerRepairRequest
import skillbill.engine.goalrunner.model.GoalRunnerReplanRequest
import skillbill.engine.goalrunner.model.GoalRunnerResetRequest
import skillbill.engine.goalrunner.status.GoalRunnerStatusService
import skillbill.workflow.model.goalreview.GoalSubtaskOperatorDecision

@Inject
class GoalPauseCommand(
  private val goalRunnerStatusService: GoalRunnerStatusService,
  private val state: CliRunState,
  private val inputs: CliRunInputs,
) : DocumentedCliCommand("pause", "Request a durable pause for an already-running goal.") {
  private val issueKey by argument(help = "Parent issue key for the decomposed goal.")
  private val repoRoot by option("--repo-root", help = "Repository root that owns the goal.")

  override fun run() {
    val result =
      goalRunnerStatusService.pause(
        issueKey,
        resolveCliRepositoryRoot(repoRoot, inputs),
      )
    val payload = result.toGoalPauseCliMap()
    state.completeText(goalPauseText(result), payload, exitCode = goalPauseExitCode(result))
  }
}

@Inject
class GoalStopCommand(
  private val goalRunnerStatusService: GoalRunnerStatusService,
  private val state: CliRunState,
  private val inputs: CliRunInputs,
) : DocumentedCliCommand("stop", "Stop a running goal now: record the operator stop, then terminate the runner.") {
  private val issueKey by argument(help = "Parent issue key for the decomposed goal.")
  private val repoRoot by option("--repo-root", help = "Repository root that owns the goal.")

  override fun run() {
    val result =
      goalRunnerStatusService.stop(
        issueKey,
        resolveCliRepositoryRoot(repoRoot, inputs),
      )
    val payload = result.toGoalStopCliMap()
    state.completeText(goalStopText(result), payload, exitCode = goalStopExitCode(result))
  }
}

@Inject
class GoalResumeCommand(
  private val goalRunnerStatusService: GoalRunnerStatusService,
  private val state: CliRunState,
  private val inputs: CliRunInputs,
) : DocumentedCliCommand("resume", "Clear a durable pause for a goal without starting child runs.") {
  private val issueKey by argument(help = "Parent issue key for the decomposed goal.")
  private val repoRoot by option("--repo-root", help = "Repository root that owns the goal.")

  override fun run() {
    val result =
      goalRunnerStatusService.resume(
        issueKey,
        resolveCliRepositoryRoot(repoRoot, inputs),
      )
    val payload = result.toGoalResumeCliMap()
    state.completeText(goalResumeText(result), payload, exitCode = goalResumeExitCode(result.status))
  }
}

@Inject
class GoalResetCommand(
  private val goalRunnerStatusService: GoalRunnerStatusService,
  private val state: CliRunState,
  private val inputs: CliRunInputs,
) : DocumentedCliCommand("reset", "Reset decomposed goal runtime state.") {
  private val issueKey by argument(help = "Parent issue key for the decomposed goal.")
  private val hard by option("--hard", help = "Reset all subtask runtime fields, including completed subtasks.")
    .flag(default = false)
  private val force by option("--force", "--yes", help = "Bypass hard-reset confirmation gate.")
    .flag(default = false)
  private val preservePlanning by option(
    "--preserve-planning",
    help = "Delete incompatible child workflows while preserving immutable goal planning checkpoints.",
  ).flag(default = false)
  private val subtaskId by option(
    "--subtask",
    help = "Selected subtask ID for scoped incompatible-child recovery.",
  ).int()
  private val deleteChildWorkflow by option(
    "--delete-child-workflow",
    help = "Explicitly delete the selected subtask's incompatible terminal child workflow.",
  ).flag(default = false)
  private val confirmIssueKey by option(
    "--confirm-issue-key",
    help = "Confirmation gate for --hard. Must match the issue key.",
  )
  private val repoRoot by option("--repo-root", help = "Repository root for checked-in manifest recovery.")

  override fun run() {
    if ((subtaskId != null) != deleteChildWorkflow) {
      throw UsageError("--subtask ID and --delete-child-workflow must be supplied together.")
    }
    if (subtaskId != null && requireNotNull(subtaskId) <= 0) {
      throw UsageError("--subtask must be a positive integer.")
    }
    if (deleteChildWorkflow && (hard || preservePlanning)) {
      throw UsageError(
        "--subtask ID --delete-child-workflow is incompatible with --hard and --preserve-planning.",
      )
    }
    if (preservePlanning && !hard) {
      throw UsageError(
        "--preserve-planning only applies to a hard reset; a soft reset never deletes child workflows. " +
          "Pass --hard as well, or drop --preserve-planning.",
      )
    }
    if (hard && !force && confirmIssueKey != issueKey) {
      throw UsageError(
        "Hard reset requires explicit confirmation. Pass --confirm-issue-key $issueKey or --force.",
      )
    }
    emitHardResetAcceptanceWarning()
    val result =
      goalRunnerStatusService.reset(
        GoalRunnerResetRequest(
          issueKey = issueKey,
          hard = hard,
          preservePlanning = preservePlanning,
          subtaskId = subtaskId,
          deleteChildWorkflow = deleteChildWorkflow,
          repoRoot = resolveCliRepositoryRoot(repoRoot, inputs),
        ),
      )
    val payload = result.toGoalResetCliMap(issueKey, hard)
    state.completeText(goalResetText(result, issueKey, hard), payload, exitCode = goalResetExitCode(result))
  }

  private fun emitHardResetAcceptanceWarning() {
    if (!hard) return
    val discardedAcceptances = goalRunnerStatusService.hardResetPreflight(issueKey)
    if (discardedAcceptances.isNotEmpty()) {
      inputs.liveStdout(hardResetAcceptanceWarning(issueKey, discardedAcceptances))
    }
  }
}

@Inject
class GoalReplanCommand(
  private val goalRunnerStatusService: GoalRunnerStatusService,
  private val state: CliRunState,
  private val inputs: CliRunInputs,
) : DocumentedCliCommand(
    "replan",
    "Discard one subtask plan while preserving sibling plans, shared preplan, and runtime state; " +
      "pass --include-shared-preplan to also discard the shared preplan and cascade non-terminal sibling plans.",
  ) {
  private val issueKey by argument(help = "Parent issue key for the decomposed goal.")
  private val subtaskId by option(
    "--subtask",
    help = "Subtask whose stored plan should be discarded and regenerated on the next goal run.",
  ).int().required()
  private val includeSharedPreplan by option(
    "--include-shared-preplan",
    help =
      "Also discard the goal-wide shared preplan and cascade sibling plans that are not " +
        "complete with a commit_sha (planning rows only; runtime state and terminal plan rows stay).",
  ).flag(default = false)
  private val repoRoot by option("--repo-root", help = "Repository root for the goal.")

  override fun run() {
    if (subtaskId <= 0) {
      throw UsageError("--subtask must be a positive integer.")
    }
    val result =
      goalRunnerStatusService.replan(
        GoalRunnerReplanRequest(
          issueKey = issueKey,
          subtaskId = subtaskId,
          repoRoot = resolveCliRepositoryRoot(repoRoot, inputs),
          includeSharedPreplan = includeSharedPreplan,
        ),
      )
    val payload = result.toGoalReplanCliMap(issueKey)
    state.completeText(goalReplanText(result, issueKey), payload, exitCode = goalReplanExitCode(result))
  }
}

@Inject
class GoalAcceptCommand(
  private val goalRunnerStatusService: GoalRunnerStatusService,
  private val state: CliRunState,
  private val inputs: CliRunInputs,
) : DocumentedCliCommand(
    "accept",
    "Restore an acceptance discarded by hard reset (--restore-after-hard-reset only). " +
      "Ordinary out-of-band accept is disabled; repair or resume blocked children instead.",
  ) {
  private val issueKey by argument(help = "Parent issue key for the decomposed goal.")
  private val subtaskId by option("--subtask", help = "Subtask id whose work already landed.")
    .int()
    .required()
  private val commit by option("--commit", help = "Commit that carries the landed work.").required()
  private val reason by option("--reason", help = "Why this subtask was completed outside the runtime.").required()
  private val repoRoot by option("--repo-root", help = "Repository root used to verify the commit.")
  private val restoreAfterHardReset by option(
    "--restore-after-hard-reset",
    help = "Restore an acceptance discarded by a goal-wide hard reset.",
  ).flag(default = false)

  override fun run() {
    val result =
      goalRunnerStatusService.accept(
        GoalRunnerAcceptRequest(
          issueKey = issueKey,
          subtaskId = subtaskId,
          commitSha = commit,
          reason = reason,
          repoRoot = resolveCliRepositoryRoot(repoRoot, inputs),
          restoreAfterHardReset = restoreAfterHardReset,
        ),
      )
    val payload = result.toGoalAcceptCliMap()
    state.completeText(goalAcceptText(result), payload, exitCode = goalAcceptExitCode(result))
  }
}

@Inject
class GoalRepairCommand(
  private val goalRunnerStatusService: GoalRunnerStatusService,
  private val state: CliRunState,
  private val inputs: CliRunInputs,
) : DocumentedCliCommand(
    "repair",
    "Inspect or clear known goal resume wedges without discarding completed work. " +
      "Default is inspect-only; pass --apply to act. " +
      "Clears: stale_execution_lease on the parent goal runner; stale_child_worker_lease on a " +
      "child workflow; stale_runner_interrupted_pause residue on parent controls; missing " +
      "validation_depth on the continuation artifact; unreachable stored review_base_sha; " +
      "unreachable stored remediation_base_sha; stale blocked goal_continuation_outcome; " +
      "completed upstream phase records missing settled output for a blocked consumer. " +
      "Does not touch: completed commit shas, review pass history, audit repair state, " +
      "planning checkpoints, or anything goal reset/replan/accept own.",
  ) {
  private val issueKey by argument(help = "Parent issue key for the decomposed goal.")
  private val subtaskId by option(
    "--subtask",
    help = "Optional subtask ID to scope diagnosis and repair to one child.",
  ).int()
  private val apply by option(
    "--apply",
    help = "Apply repairs for diagnosed wedges. Without this flag the command only reports.",
  ).flag(default = false)
  private val repoRoot by option("--repo-root", help = "Repository root for reachability checks.")

  override fun run() {
    if (subtaskId != null && requireNotNull(subtaskId) <= 0) {
      throw UsageError("--subtask must be a positive integer.")
    }
    val result =
      goalRunnerStatusService.repair(
        GoalRunnerRepairRequest(
          issueKey = issueKey,
          apply = apply,
          subtaskId = subtaskId,
          repoRoot = resolveCliRepositoryRoot(repoRoot, inputs),
        ),
      )
    val payload = result.toGoalRepairCliMap()
    state.completeText(goalRepairText(result), payload, exitCode = goalRepairExitCode(result))
  }
}

@Inject
class GoalOperatorDecisionCommand(
  private val goalOperatorDecisionService: GoalOperatorDecisionService,
  private val state: CliRunState,
  private val inputs: CliRunInputs,
) : DocumentedCliCommand(
    "operator-decision",
    "Record retry_fix, accept_and_advance, or abandon_subtask for a paused goal subtask " +
      "without hand-editing durable state or decomposition-manifest.yaml. Resume the goal to consume it.",
  ) {
  private val issueKey by argument(help = "Parent issue key for the decomposed goal.")
  private val subtaskId by option("--subtask", help = "Paused subtask id to decide.")
    .int()
    .required()
  private val decision by option(
    "--decision",
    help = "Operator decision: ${GoalSubtaskOperatorDecision.entries.joinToString { it.wireValue }}.",
  ).required()
  private val repoRoot by option("--repo-root", help = "Repository root for the goal.")

  override fun run() {
    if (subtaskId <= 0) {
      throw UsageError("--subtask must be a positive integer.")
    }
    val parsed =
      GoalSubtaskOperatorDecision.entries.firstOrNull { it.wireValue == decision }
        ?: throw UsageError(
          "Unknown --decision '$decision'. Allowed: " +
            GoalSubtaskOperatorDecision.entries.joinToString { it.wireValue } + ".",
        )
    val result =
      goalOperatorDecisionService.record(
        GoalRunnerOperatorDecisionRequest(
          issueKey = issueKey,
          subtaskId = subtaskId,
          decision = parsed,
          repoRoot = resolveCliRepositoryRoot(repoRoot, inputs),
        ),
      )
    val payload = result.toGoalOperatorDecisionCliMap()
    state.completeText(goalOperatorDecisionText(result), payload, exitCode = goalOperatorDecisionExitCode(result))
  }
}
