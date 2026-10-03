package skillbill.cli.goal.core

import com.github.ajalt.clikt.core.CliktCommand
import com.github.ajalt.clikt.core.subcommands
import com.github.ajalt.clikt.parameters.arguments.argument
import com.github.ajalt.clikt.parameters.arguments.multiple
import com.github.ajalt.clikt.parameters.options.default
import com.github.ajalt.clikt.parameters.options.flag
import com.github.ajalt.clikt.parameters.options.multiple
import com.github.ajalt.clikt.parameters.options.option
import com.github.ajalt.clikt.parameters.types.int
import me.tatarka.inject.annotations.Inject
import skillbill.agentaddon.model.HydratedAgentAddonSelection
import skillbill.application.system.RuntimeProvenanceService
import skillbill.application.telemetry.service.TelemetryService
import skillbill.cli.goal.control.GoalAcceptCommand
import skillbill.cli.goal.control.GoalOperatorDecisionCommand
import skillbill.cli.goal.control.GoalPauseCommand
import skillbill.cli.goal.control.GoalRepairCommand
import skillbill.cli.goal.control.GoalReplanCommand
import skillbill.cli.goal.control.GoalResetCommand
import skillbill.cli.goal.control.GoalResumeCommand
import skillbill.cli.goal.control.GoalStopCommand
import skillbill.cli.goal.purge.GoalPurgeCommand
import skillbill.cli.goal.run.DEFAULT_GOAL_PROGRESS_IDLE_TIMEOUT_MINUTES
import skillbill.cli.goal.run.GoalFindingsCommand
import skillbill.cli.goal.run.GoalPlanningLogCommand
import skillbill.cli.goal.run.GoalPreflightCommand
import skillbill.cli.goal.run.GoalRunAgentAddonHydrationArgs
import skillbill.cli.goal.run.GoalRunInputPreparation
import skillbill.cli.goal.run.GoalRunInputValidationArgs
import skillbill.cli.goal.run.GoalRunPresenter
import skillbill.cli.goal.run.RUNTIME_CLASSPATH_ENV
import skillbill.cli.goal.run.RUNTIME_EXECUTABLE_ENV
import skillbill.cli.goal.run.RUNTIME_PATH_SEPARATOR_ENV
import skillbill.cli.goal.run.goalIntakeRequestText
import skillbill.cli.goal.run.goalRunText
import skillbill.cli.goal.run.parseCodeReviewMode
import skillbill.cli.goal.run.resolveInvokedAgentId
import skillbill.cli.goal.run.toGoalRunCliMap
import skillbill.cli.goal.status.GoalStatusCommand
import skillbill.cli.goal.status.GoalWatchCommand
import skillbill.cli.kernel.agent.invokingAgentResolutionHelp
import skillbill.cli.kernel.cli.CliRunState
import skillbill.cli.kernel.cli.DocumentedCliCommand
import skillbill.cli.kernel.cli.drainTelemetryOnCompletion
import skillbill.cli.kernel.cli.resolveCliRepositoryRoot
import skillbill.cli.model.CliRunInputs
import skillbill.cli.model.DEFAULT_GOAL_MAX_WALL_CLOCK_MINUTES
import skillbill.contracts.workflow.identity.task.FeatureTaskRuntimeGoalContinuationLaunchTokens
import skillbill.engine.goalrunner.GoalRunner
import skillbill.engine.goalrunner.model.DEFAULT_GOAL_PLANNING_BUDGET
import skillbill.engine.goalrunner.model.GoalIntakeAdmission
import skillbill.engine.goalrunner.model.GoalRunnerRunRequest
import skillbill.ports.diagnostics.RuntimeDiagnostics
import skillbill.ports.system.HostPlatformPort
import java.nio.file.Path
import kotlin.time.Duration.Companion.minutes

@Inject
class GoalControlFlowCommands(
  pause: GoalPauseCommand,
  stop: GoalStopCommand,
  resume: GoalResumeCommand,
  reset: GoalResetCommand,
  purge: GoalPurgeCommand,
) {
  val commands: List<CliktCommand> = listOf(pause, stop, resume, reset, purge)
}

@Inject
class GoalControlOperatorCommands(
  replan: GoalReplanCommand,
  accept: GoalAcceptCommand,
  repair: GoalRepairCommand,
  operatorDecision: GoalOperatorDecisionCommand,
) {
  val commands: List<CliktCommand> = listOf(replan, accept, repair, operatorDecision)
}

@Inject
class GoalControlSubcommands(
  flow: GoalControlFlowCommands,
  operator: GoalControlOperatorCommands,
) {
  val commands: List<CliktCommand> = flow.commands + operator.commands
}

@Inject
class GoalRunSubcommands(
  preflight: GoalPreflightCommand,
  status: GoalStatusCommand,
  watch: GoalWatchCommand,
  controls: GoalControlSubcommands,
  findings: GoalFindingsCommand,
  planningLog: GoalPlanningLogCommand,
) {
  val commands: List<CliktCommand> =
    listOf(preflight, status, watch) + controls.commands + listOf(findings, planningLog)
}

@Inject
class GoalRunCommand(
  private val goalRunner: GoalRunner,
  private val runtimeProvenanceService: RuntimeProvenanceService,
  private val inputPreparation: GoalRunInputPreparation,
  private val telemetryService: TelemetryService,
  private val diagnostics: RuntimeDiagnostics,
  private val state: CliRunState,
  private val inputs: CliRunInputs,
  private val hostPlatform: HostPlatformPort,
  goalRunSubcommands: GoalRunSubcommands,
) : DocumentedCliCommand(
    "goal",
    "Run a decomposed goal in the foreground. Exit codes: complete=0, failed=1, paused=2, blocked=3.",
  ) {
  private val intakeTokens by argument(
    help = "Tracker link or issue key, or an existing spec key or path.",
  ).multiple()
  private val agent by option(
    "--agent",
    help = invokingAgentResolutionHelp("--agent"),
  )
  private val agentOverride by option(
    "--agent-override",
    help = "Agent to use for child subtask runs instead of the invoking agent. Wins over --agent and detection.",
  )
  private val repoRoot by option("--repo-root", help = "Repository root for child agent runs.")
  private val codeReviewMode by option(
    FeatureTaskRuntimeGoalContinuationLaunchTokens.CODE_REVIEW_MODE_FLAG,
    help =
      "Review execution mode for every child: inline (default, one review subagent per " +
        "pass) or auto (also resolves inline).",
  )
  private val agentAddonSelectionJson by option(
    FeatureTaskRuntimeGoalContinuationLaunchTokens.AGENT_ADDON_SELECTION_JSON_FLAG,
    help = "Already-resolved ordered agent add-on selection JSON. Use --agent-addon for raw slugs.",
  )
  private val agentAddonSlugs by option(
    "--agent-addon",
    help = "Raw agent add-on slug. Repeat to preserve caller order; invalid values are rejected before launch.",
  ).multiple()
  private val maxWallClockMinutes by option(
    "--max-wall-clock-minutes",
    "--timeout-minutes",
    help =
      "Per-subtask wall-clock cap in minutes (default " +
        "$DEFAULT_GOAL_MAX_WALL_CLOCK_MINUTES). Hard ceiling even when a child process is still " +
        "alive (progress-idle spares active work). Pass 0 to disable.",
  ).int().default(DEFAULT_GOAL_MAX_WALL_CLOCK_MINUTES)
  private val progressIdleTimeoutMinutes by option(
    "--progress-idle-timeout-minutes",
    help =
      "Per-subtask durable workflow-progress idle timeout in minutes (default " +
        "$DEFAULT_GOAL_PROGRESS_IDLE_TIMEOUT_MINUTES). A subtask with no durable progress and no " +
        "file activity for this long is killed; active work is spared. Pass 0 to disable.",
  ).int().default(DEFAULT_GOAL_PROGRESS_IDLE_TIMEOUT_MINUTES)
  private val planningBudgetMinutes by option(
    "--planning-budget-minutes",
    help =
      "Per-plan wall-clock budget for goal planning in minutes (default " +
        "${DEFAULT_GOAL_PLANNING_BUDGET.inWholeMinutes}). Planning writes no durable progress, so it is " +
        "bounded by this budget rather than the progress-idle timeout. Pass 0 to disable.",
  ).int().default(DEFAULT_GOAL_PLANNING_BUDGET.inWholeMinutes.toInt())
  private val stopAfterSubtask by option(
    "--stop-after-subtask",
    help = "Pause the parent after this positive subtask ID reaches durable terminal success.",
  ).int()
  private val noLiveOutput by option(
    "--no-live-output",
    help = "Do not tee child stdout/stderr or structured observability lines to this terminal.",
  ).flag(default = false)
  private val debugChildOutput by option(
    "--debug-child-output",
    help = "Show full child stdout/stderr. Noisy; default output keeps raw child streams hidden.",
  ).flag(default = false)

  override val invokeWithoutSubcommand: Boolean = true

  init {
    subcommands(goalRunSubcommands.commands)
  }

  override fun run() {
    if (currentContext.invokedSubcommand != null) {
      return
    }
    val effectiveRepoRoot = resolveCliRepositoryRoot(repoRoot, inputs)
    val invokedAgentId = resolveInvokedAgentId(agent, inputs.environment)
    inputPreparation.validate(
      GoalRunInputValidationArgs(
        issueKey = intakeTokens.joinToString(" ").takeIf(String::isNotBlank),
        stopAfterSubtask = stopAfterSubtask,
        agentAddonSlugs = agentAddonSlugs,
        agentAddonSelectionJson = agentAddonSelectionJson,
        agent = agent,
        agentOverride = agentOverride,
      ),
    )
    val intake = intakeTokens.joinToString(" ").trim()
    val runIssueKey =
      when (val admission = goalRunner.admitIntake(intake, effectiveRepoRoot)) {
        is GoalIntakeAdmission.Admitted -> admission.issueKey
        is GoalIntakeAdmission.NeedsInput -> {
          state.appendStderr(goalIntakeRequestText(admission))
          state.completeEmpty(exitCode = 1)
          return
        }
      }
    val receivingAgents =
      listOfNotNull(
        invokedAgentId,
        agentOverride?.takeIf(String::isNotBlank),
      ).distinct()
    val hydratedSelection =
      inputPreparation.hydrateAgentAddonSelection(
        GoalRunAgentAddonHydrationArgs(
          agentAddonSlugs = agentAddonSlugs,
          agentAddonSelectionJson = agentAddonSelectionJson,
          receivingAgents = receivingAgents,
          effectiveRepoRoot = effectiveRepoRoot,
        ),
      )
    val presenter =
      GoalRunPresenter(
        issueKey = runIssueKey,
        inputs = inputs,
        liveOutput = !noLiveOutput,
        repoRoot = effectiveRepoRoot,
        dbOverride = inputs.databasePath,
        runtimeProvenance =
          runtimeProvenanceService.current(
            executablePathHint = inputs.environment[RUNTIME_EXECUTABLE_ENV],
            classPath = inputs.environment[RUNTIME_CLASSPATH_ENV] ?: hostPlatform.jvmClassPath,
            javaCommand = hostPlatform.javaCommand,
            pathSeparator = inputs.environment[RUNTIME_PATH_SEPARATOR_ENV] ?: hostPlatform.pathSeparator,
          ),
      )
    presenter.emitStartupProvenance()
    val request =
      runRequest(runIssueKey, invokedAgentId, hydratedSelection, presenter, effectiveRepoRoot)
        .copy(intake = intake)
    val report = goalRunner.run(request)
    val payload = report.toGoalRunCliMap()
    state.completeText(goalRunText(report), payload, exitCode = report.goalRunExitCode())
    drainTelemetryOnCompletion(telemetryService, diagnostics)
  }

  private fun runRequest(
    runIssueKey: String,
    invokedAgentId: String,
    hydratedSelection: HydratedAgentAddonSelection,
    presenter: GoalRunPresenter,
    effectiveRepoRoot: Path,
  ): GoalRunnerRunRequest =
    GoalRunnerRunRequest(
      issueKey = runIssueKey,
      repoRoot = effectiveRepoRoot,
      invokedAgentId = invokedAgentId,
      configuredAgentOverrideId = agentOverride,
      timeout = maxWallClockMinutes.takeIf { it > 0 }?.minutes,
      progressIdleTimeout = progressIdleTimeoutMinutes.takeIf { it > 0 }?.minutes,
      planningBudget = planningBudgetMinutes.takeIf { it > 0 }?.minutes,
      outputSink = presenter.outputSink(includeRawChildOutput = debugChildOutput),
      eventSink = presenter.eventSink(),
      codeReviewMode = parseCodeReviewMode(codeReviewMode),
      agentAddonSelection = hydratedSelection,
      stopAfterSubtaskId = stopAfterSubtask,
    )
}
