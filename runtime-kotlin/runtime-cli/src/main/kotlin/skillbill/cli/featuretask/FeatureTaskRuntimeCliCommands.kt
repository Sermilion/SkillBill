package skillbill.cli.featuretask

import com.github.ajalt.clikt.core.subcommands
import com.github.ajalt.clikt.parameters.arguments.argument
import com.github.ajalt.clikt.parameters.arguments.optional
import com.github.ajalt.clikt.parameters.options.default
import com.github.ajalt.clikt.parameters.options.flag
import com.github.ajalt.clikt.parameters.options.multiple
import com.github.ajalt.clikt.parameters.options.option
import com.github.ajalt.clikt.parameters.types.int
import me.tatarka.inject.annotations.Inject
import skillbill.cli.kernel.agent.invokingAgentResolutionHelp
import skillbill.cli.kernel.cli.DocumentedCliCommand
import skillbill.cli.model.DEFAULT_GOAL_MAX_WALL_CLOCK_MINUTES
import skillbill.contracts.workflow.identity.task.FeatureTaskRuntimeGoalContinuationLaunchTokens
import skillbill.workflow.model.goalreview.GoalSubtaskOperatorDecision

abstract class FeatureTaskRuntimePhaseAgentCommand(
  name: String,
  help: String,
) : DocumentedCliCommand(name, help) {
  internal val repoRoot by option("--repo-root", help = "Repository root for phase agent runs.")
  internal val maxWallClockMinutes by option(
    "--max-wall-clock-minutes",
    "--timeout-minutes",
    help =
      "Per-phase wall-clock cap in minutes (default " +
        "$DEFAULT_GOAL_MAX_WALL_CLOCK_MINUTES). Hard ceiling even when a child process is still " +
        "alive. Pass 0 to disable.",
  ).int().default(DEFAULT_GOAL_MAX_WALL_CLOCK_MINUTES)
  internal val monitor by option(
    "--monitor",
    help = "Tee phase agent output and structured progress to this terminal.",
  ).flag(default = false)
  internal val agent by option(
    "--agent",
    help = invokingAgentResolutionHelp("--agent"),
  )
  internal val agentOverride by option(
    "--agent-override",
    help = "Agent to use for every phase run instead of the invoking agent. Wins over --agent and per-phase agents.",
  )
  internal val phaseAgents by option(
    "--phase-agent",
    help = "Per-phase agent assignment as phase=agent (e.g. --phase-agent plan=claude). Repeatable.",
  ).multiple()
  internal val phaseModels by option(
    "--phase-model",
    help =
      "Per-phase model directive as phase=model or phase=model@effort " +
        "(e.g. --phase-model plan=claude-opus-4-8@high). Wins over the config execution_matrix. Repeatable.",
  ).multiple()
  internal val goalParentIssueKey by option(
    FeatureTaskRuntimeGoalContinuationLaunchTokens.GOAL_PARENT_ISSUE_KEY_FLAG,
    help = "Parent decomposed issue key for non-interactive goal-continuation runtime runs.",
  )
  internal val goalSubtaskId by option(
    FeatureTaskRuntimeGoalContinuationLaunchTokens.GOAL_SUBTASK_ID_FLAG,
    help = "Subtask id for non-interactive goal-continuation runtime runs.",
  ).int()
  internal val goalBranch by option(
    FeatureTaskRuntimeGoalContinuationLaunchTokens.GOAL_BRANCH_FLAG,
    help = "Pre-created goal branch to reuse for non-interactive goal-continuation runtime runs.",
  )
  internal val goalParentWorkflowId by option(
    FeatureTaskRuntimeGoalContinuationLaunchTokens.GOAL_PARENT_WORKFLOW_ID_FLAG,
    help = "Optional parent workflow id for non-interactive goal-continuation runtime runs.",
  )

  internal val goalLastResumableStep by option(
    FeatureTaskRuntimeGoalContinuationLaunchTokens.GOAL_LAST_RESUMABLE_STEP_FLAG,
    help = "Optional durable resume step supplied by the goal runner.",
  )
  internal val goalReviewBaseSha by option(
    FeatureTaskRuntimeGoalContinuationLaunchTokens.GOAL_REVIEW_BASE_SHA_FLAG,
    help = "Review baseline commit captured by the goal runner before implementation.",
  )
  internal val goalBaselineUntrackedPaths by option(
    FeatureTaskRuntimeGoalContinuationLaunchTokens.GOAL_BASELINE_UNTRACKED_PATH_FLAG,
    help = "Baseline untracked path. Repeat for every path owned before this child starts.",
  ).multiple()
  internal val codeReviewModes by option(
    FeatureTaskRuntimeGoalContinuationLaunchTokens.CODE_REVIEW_MODE_FLAG,
    help =
      "Review execution mode for this run: inline (default, one review subagent per " +
        "pass) or auto (also resolves inline). Supply at most once; a resumed workflow " +
        "remains pinned to its original mode.",
  ).multiple()
  internal val operatorDecisions by option(
    "--operator-decision",
    help =
      "Release a subtask paused on an unresolved Blocker or Major: " +
        "${GoalSubtaskOperatorDecision.entries.joinToString { it.wireValue }}. Supply at most once.",
  ).multiple()
  internal val suppressPr by option(
    FeatureTaskRuntimeGoalContinuationLaunchTokens.SUPPRESS_PR_FLAG,
    help = "Suppress the runtime PR phase. Required with goal-continuation options.",
  ).flag(default = false)
  internal val qualityGateSelections by option(
    FeatureTaskRuntimeGoalContinuationLaunchTokens.QUALITY_GATE_SELECTION_FLAG,
    help = "Goal-child quality gate: build (compile proof) or validate (full collect-all). Defaults to validate.",
  ).multiple()
  internal val explicitWorkflowId by option(
    FeatureTaskRuntimeGoalContinuationLaunchTokens.WORKFLOW_ID_FLAG,
    help =
      "Open the run under this exact workflow id instead of minting a new one. Used by the goal " +
        "driver's open-with-assigned-id path for a first runtime subtask run (distinct from resume).",
  )
  internal val agentAddonSelectionJson by option(
    FeatureTaskRuntimeGoalContinuationLaunchTokens.AGENT_ADDON_SELECTION_JSON_FLAG,
    help = "Already-resolved ordered agent add-on selection JSON. Raw agent-addon tokens are not accepted here.",
  )
}

@Inject
class FeatureTaskRuntimeRunCommand(
  private val preparation: FeatureTaskRuntimeRunPreparation,
  private val execution: FeatureTaskRuntimeRunExecution,
  featureTaskRuntimeExplicitRunCommand: FeatureTaskRuntimeExplicitRunCommand,
  control: FeatureTaskRuntimeControlSubcommands,
  rejectedOutput: FeatureTaskRejectedOutputSubcommands,
) : FeatureTaskRuntimePhaseAgentCommand(
    FeatureTaskRuntimeGoalContinuationLaunchTokens.FEATURE_TASK_COMMAND,
    "Run the runtime-driven feature-task phase loop in the foreground.",
  ) {
  private val issueKey by argument(help = "Issue key the run implements.").optional()
  private val specPath by argument(help = "Path to the governed spec the run implements.").optional()

  override val invokeWithoutSubcommand: Boolean = true

  init {
    subcommands(listOf(featureTaskRuntimeExplicitRunCommand) + control.commands + rejectedOutput.commands)
  }

  override fun run() {
    if (currentContext.invokedSubcommand != null) {
      return
    }
    execution.run(
      this,
      preparation.prepareRun(this, issueKey, specPath),
      explicitWorkflowId?.takeIf(String::isNotBlank),
    )
  }
}

@Inject
class FeatureTaskRuntimeExplicitRunCommand(
  private val preparation: FeatureTaskRuntimeRunPreparation,
  private val execution: FeatureTaskRuntimeRunExecution,
) : FeatureTaskRuntimePhaseAgentCommand(
    FeatureTaskRuntimeGoalContinuationLaunchTokens.RUN_SUBCOMMAND,
    "Run the feature-task phase loop (explicit form of the parent command's default run).",
  ) {
  private val issueKey by argument(help = "Issue key the run implements.")
  private val specPath by argument(help = "Path to the governed spec the run implements.").optional()

  override fun run() {
    execution.run(
      this,
      preparation.prepareRun(this, issueKey, specPath),
      explicitWorkflowId?.takeIf(String::isNotBlank),
    )
  }
}
