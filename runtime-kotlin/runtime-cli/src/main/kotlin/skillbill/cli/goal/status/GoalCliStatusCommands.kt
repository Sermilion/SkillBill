package skillbill.cli.goal.status

import com.github.ajalt.clikt.core.UsageError
import com.github.ajalt.clikt.parameters.arguments.argument
import com.github.ajalt.clikt.parameters.options.default
import com.github.ajalt.clikt.parameters.options.flag
import com.github.ajalt.clikt.parameters.options.multiple
import com.github.ajalt.clikt.parameters.options.option
import com.github.ajalt.clikt.parameters.types.int
import me.tatarka.inject.annotations.Inject
import skillbill.cli.goal.run.DEFAULT_GOAL_WATCH_INTERVAL_SECONDS
import skillbill.cli.goal.run.DEFAULT_GOAL_WATCH_REFRESHES
import skillbill.cli.goal.run.IDLE_STOP_CONSECUTIVE_REFRESHES
import skillbill.cli.goal.run.MILLIS_PER_SECOND
import skillbill.cli.kernel.cli.CliRunState
import skillbill.cli.kernel.cli.DocumentedCliCommand
import skillbill.cli.model.CliRunInputs
import skillbill.contracts.SharedPayloadKeys
import skillbill.contracts.issuekey.MAX_ISSUE_KEY_LENGTH
import skillbill.contracts.issuekey.isWellFormedIssueKey
import skillbill.engine.goalrunner.status.GoalRunnerStatusService
import skillbill.error.core.DatabaseFailureCode
import skillbill.error.core.SkillBillRuntimeException
import skillbill.error.core.databaseAccessCondition
import skillbill.error.core.rethrowUnless
import skillbill.goalrunner.model.ExecutionLiveness
import skillbill.goalrunner.model.GoalRunnerStatusProjection
import skillbill.ports.repository.RepositoryEnclosingRootPort
import skillbill.ports.workflow.gitops.model.DEFAULT_SELECTED_DIFF_MAX_BYTES
import skillbill.ports.workflow.gitops.model.DEFAULT_SELECTED_DIFF_MAX_HUNKS
import skillbill.ports.workflow.gitops.model.DEFAULT_SELECTED_DIFF_MAX_LINES

@Inject
class GoalStatusCommand(
  private val goalRunnerStatusService: GoalRunnerStatusService,
  private val repositoryEnclosingRootPort: RepositoryEnclosingRootPort,
  private val state: CliRunState,
  private val inputs: CliRunInputs,
) : DocumentedCliCommand("status", "Show read-only decomposed goal status.") {
  private val issueKey by argument(help = "Parent issue key for the decomposed goal.")
  private val monitorOnly by option(
    "--monitor",
    help = "Render one bounded read-only snapshot; never launches or polls a goal.",
  ).flag(default = false)
  private val agent by option(
    "--agent",
    help =
      "Agent invoking this read-only status view. Resolution order: --agent, then SKILL_BILL_AGENT, " +
        "then the detected invoking-agent execution context. Optional and never required: active_agent " +
        "comes from persisted run state, not from this option.",
  )
  private val agentOverride by option(
    "--agent-override",
    help = "Optional agent override whose id should be shown as active.",
  )
  private val repoRoot by option("--repo-root", help = "Repository root for checked-in manifest recovery.")
  private val diffStat by option(
    "--diff-stat",
    help = "Include one current worktree diff stat snapshot. Runs git diff --numstat once.",
  ).flag(default = false)
  private val diffHunks by option(
    "--diff-hunk",
    help = "Include bounded selected diff hunks for this path. Repeat for multiple paths; noisier than --diff-stat.",
  ).multiple()
  private val diffHunkMaxHunks by option(
    "--diff-hunk-max-hunks",
    help = "Maximum selected diff hunks to print when --diff-hunk is used.",
  ).int().default(DEFAULT_SELECTED_DIFF_MAX_HUNKS)
  private val diffHunkMaxLines by option(
    "--diff-hunk-max-lines",
    help = "Maximum selected diff lines to print across requested hunks.",
  ).int().default(DEFAULT_SELECTED_DIFF_MAX_LINES)
  private val diffHunkMaxBytes by option(
    "--diff-hunk-max-bytes",
    help = "Maximum selected diff bytes to print across requested hunks.",
  ).int().default(DEFAULT_SELECTED_DIFF_MAX_BYTES)

  override fun run() {
    val options = statusCliRequestOptions()
    if (options.monitorOnly && !isWellFormedIssueKey(options.issueKey)) {
      throw UsageError(
        "Monitor requires a non-blank issue key of at most $MAX_ISSUE_KEY_LENGTH characters " +
          "with no control characters.",
      )
    }
    if (options.monitorOnly && (diffStat || diffHunks.isNotEmpty())) {
      throw UsageError("Monitor accepts only one bounded status snapshot; omit diff options.")
    }
    val projection =
      try {
        goalRunnerStatusService.status(inputs.goalStatusRequest(options, repositoryEnclosingRootPort))
      } catch (error: SkillBillRuntimeException) {
        error.rethrowUnless(error.code == DatabaseFailureCode.ACCESS)
        if (!options.monitorOnly) throw error
        val reason = databaseAccessCondition(error)
        val payload = databaseUnavailableGoalStatusCliMap(issueKey, reason)
        state.completeText(
          goalMonitorStatusText(issueKey, projection = null, databaseUnavailableReason = reason),
          payload,
          exitCode = goalStatusExitCode(projection = null, databaseUnavailable = true),
        )
        return
      }
    val payload =
      if (options.monitorOnly) {
        projection.toBoundedGoalStatusCliMap(issueKey)
      } else {
        projection.toGoalStatusCliMap(issueKey)
      }
    val text =
      if (options.monitorOnly) {
        goalMonitorStatusText(issueKey, projection)
      } else {
        goalStatusText(issueKey, projection)
      }
    state.completeText(text, payload, exitCode = goalStatusExitCode(projection))
  }

  private fun statusCliRequestOptions(): GoalStatusCliRequestOptions =
    GoalStatusCliRequestOptions(
      issueKey = issueKey,
      monitorOnly = monitorOnly,
      agent = agent,
      agentOverride = agentOverride,
      repoRoot = repoRoot,
      diff =
        GoalStatusCliDiffOptions(
          includeDiffStat = diffStat,
          selectedDiffHunkPaths = diffHunks,
          selectedDiffMaxHunks = diffHunkMaxHunks,
          selectedDiffMaxLines = diffHunkMaxLines,
          selectedDiffMaxBytes = diffHunkMaxBytes,
        ),
    )
}

@Inject
class GoalWatchCommand(
  private val goalRunnerStatusService: GoalRunnerStatusService,
  private val repositoryEnclosingRootPort: RepositoryEnclosingRootPort,
  private val state: CliRunState,
  private val inputs: CliRunInputs,
) : DocumentedCliCommand("watch", "Refresh decomposed goal status without starting child runs.") {
  private val issueKey by argument(help = "Parent issue key for the decomposed goal.")
  private val agent by option(
    "--agent",
    help =
      "Agent invoking this read-only status view. Resolution order: --agent, then SKILL_BILL_AGENT, " +
        "then the detected invoking-agent execution context. Optional and never required: active_agent " +
        "comes from persisted run state, not from this option.",
  )
  private val agentOverride by option(
    "--agent-override",
    help = "Optional agent override whose id should be shown as active.",
  )
  private val repoRoot by option("--repo-root", help = "Repository root for checked-in manifest recovery.")
  private val diffStat by option(
    "--diff-stat",
    help =
      "Include one current worktree diff stat snapshot per refresh. " +
        "Runs git diff --numstat each refresh.",
  ).flag(default = false)
  private val diffHunks by option(
    "--diff-hunk",
    help =
      "Include bounded selected diff hunks for this path on each refresh. " +
        "Repeat for multiple paths; can be noisy.",
  ).multiple()
  private val diffHunkMaxHunks by option(
    "--diff-hunk-max-hunks",
    help = "Maximum selected diff hunks to print per refresh when --diff-hunk is used.",
  ).int().default(DEFAULT_SELECTED_DIFF_MAX_HUNKS)
  private val diffHunkMaxLines by option(
    "--diff-hunk-max-lines",
    help = "Maximum selected diff lines to print per refresh across requested hunks.",
  ).int().default(DEFAULT_SELECTED_DIFF_MAX_LINES)
  private val diffHunkMaxBytes by option(
    "--diff-hunk-max-bytes",
    help = "Maximum selected diff bytes to print per refresh across requested hunks.",
  ).int().default(DEFAULT_SELECTED_DIFF_MAX_BYTES)
  private val intervalSeconds by option(
    "--interval-seconds",
    help = "Seconds between read-only status refreshes. Lower values increase terminal noise and repeated git cost.",
  ).int().default(DEFAULT_GOAL_WATCH_INTERVAL_SECONDS)
  private val maxRefreshes by option(
    "--max-refreshes",
    help = "Stop after this many refreshes. Zero follows until the goal finishes and is the default.",
  ).int().default(DEFAULT_GOAL_WATCH_REFRESHES)
  private val showUnchanged by option(
    "--show-unchanged",
    help = "Compatibility option. Every refresh, including unchanged output, is shown by default.",
  ).flag(default = false)
  private val suppressUnchanged by option(
    "--suppress-unchanged",
    help = "Print only changed refreshes. The first and loop-ending refresh are always shown.",
  ).flag(default = false)

  override fun run() {
    require(intervalSeconds >= 0) { "--interval-seconds must be non-negative." }
    require(maxRefreshes >= 0) { "--max-refreshes must be non-negative." }
    var latestRefresh: Map<String, Any?>? = null
    var latestProjection: GoalRunnerStatusProjection? = null
    var refreshCount = 0
    var stopReason = ""
    var lastPrintedRefresh: String? = null
    var consecutiveIdleRefreshes = 0
    while (true) {
      refreshCount += 1
      val projection =
        goalRunnerStatusService.statusRefresh(
          inputs.goalStatusRequest(statusCliRequestOptions(), repositoryEnclosingRootPort),
        )
      val refresh = projection.toGoalStatusCliMap(issueKey).withWatchRefresh(refreshCount)
      latestRefresh = refresh
      latestProjection = projection
      consecutiveIdleRefreshes =
        if (projection?.executionLiveness == ExecutionLiveness.IDLE) {
          consecutiveIdleRefreshes + 1
        } else {
          0
        }
      stopReason = goalWatchStopReason(
        projection = projection,
        refreshCount = refreshCount,
        maxRefreshes = maxRefreshes,
        idleStop = consecutiveIdleRefreshes >= IDLE_STOP_CONSECUTIVE_REFRESHES,
      ) ?: ""
      val renderedRefresh = goalWatchRefreshText(GoalWatchRefreshPresentation(refreshCount, projection))
      val normalizedRefresh = goalWatchRefreshText(GoalWatchRefreshPresentation(0, projection))
      val endsLoop = stopReason.isNotEmpty()
      val refreshChanged = lastPrintedRefresh == null || normalizedRefresh != lastPrintedRefresh
      val shouldPrintRefresh = endsLoop || showUnchanged || !suppressUnchanged || refreshChanged
      if (shouldPrintRefresh) {
        inputs.liveStdout(renderedRefresh)
        lastPrintedRefresh = normalizedRefresh
      }
      if (endsLoop) {
        break
      }
      if (intervalSeconds > 0) {
        Thread.sleep(intervalSeconds * MILLIS_PER_SECOND)
      }
    }
    val payload =
      linkedMapOf<String, Any?>(
        SharedPayloadKeys.STATUS to latestRefresh.orEmpty().get(SharedPayloadKeys.STATUS),
        SharedPayloadKeys.ISSUE_KEY to issueKey,
        "refresh_count" to refreshCount,
        "interval_seconds" to intervalSeconds,
        "latest_refresh" to latestRefresh,
        "stop_reason" to stopReason,
      )
    state.completeText(
      goalWatchText(
        GoalWatchPresentation(
          issueKey = issueKey,
          refreshCount = refreshCount,
          intervalSeconds = intervalSeconds,
          latestRefresh = latestProjection?.let { GoalWatchRefreshPresentation(refreshCount, it) },
          stopReason = stopReason,
        ),
      ),
      payload,
      exitCode = goalStatusExitCode(latestProjection),
    )
  }

  private fun statusCliRequestOptions(): GoalStatusCliRequestOptions =
    GoalStatusCliRequestOptions(
      issueKey = issueKey,
      agent = agent,
      agentOverride = agentOverride,
      repoRoot = repoRoot,
      diff =
        GoalStatusCliDiffOptions(
          includeDiffStat = diffStat,
          selectedDiffHunkPaths = diffHunks,
          selectedDiffMaxHunks = diffHunkMaxHunks,
          selectedDiffMaxLines = diffHunkMaxLines,
          selectedDiffMaxBytes = diffHunkMaxBytes,
        ),
    )
}
