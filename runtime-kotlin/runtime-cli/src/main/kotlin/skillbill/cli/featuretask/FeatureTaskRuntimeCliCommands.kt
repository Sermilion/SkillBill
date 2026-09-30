package skillbill.cli.featuretask

import com.github.ajalt.clikt.core.UsageError
import com.github.ajalt.clikt.core.subcommands
import com.github.ajalt.clikt.parameters.arguments.argument
import com.github.ajalt.clikt.parameters.arguments.optional
import com.github.ajalt.clikt.parameters.options.default
import com.github.ajalt.clikt.parameters.options.flag
import com.github.ajalt.clikt.parameters.options.multiple
import com.github.ajalt.clikt.parameters.options.option
import com.github.ajalt.clikt.parameters.types.int
import me.tatarka.inject.annotations.Inject
import skillbill.application.workflow.model.WorkflowFamilyKind
import skillbill.application.workflow.model.WorkflowServiceOpenFeatureTaskArgs
import skillbill.application.workflow.service.WorkflowService
import skillbill.cli.kernel.agent.invokingAgentResolutionHelp
import skillbill.cli.kernel.cli.DocumentedCliCommand
import skillbill.cli.kernel.cli.drainTelemetryOnCompletion
import skillbill.cli.kernel.cli.resolveCliRepositoryRoot
import skillbill.cli.model.DEFAULT_GOAL_MAX_WALL_CLOCK_MINUTES
import skillbill.contracts.workflow.identity.task.FeatureTaskRuntimeGoalContinuationLaunchTokens
import skillbill.engine.featuretask.model.core.FeatureTaskRuntimeRunRequest
import skillbill.engine.featuretask.model.execution.FeatureTaskRuntimeExecutionPlanCreationRequest
import skillbill.ports.featurespec.model.FeatureSpecPathResolveInput
import skillbill.ports.featurespec.model.FeatureSpecPathResolveResult
import skillbill.workflow.model.FeatureTaskExecutionIdentity
import skillbill.workflow.model.FeatureTaskRouteScope
import skillbill.workflow.model.FeatureTaskWorkflowMode
import skillbill.workflow.model.ValidationDepth
import skillbill.workflow.model.goalreview.GoalSubtaskOperatorDecision
import skillbill.workflow.taskruntime.model.skeleton.RuntimeReviewSelection
import skillbill.workflow.taskruntime.model.skeleton.SkeletonDefinition
import skillbill.workflow.taskruntime.phase.task.FeatureTaskRuntimePhaseWorkflowDefinition
import java.nio.file.Path
import kotlin.time.Duration.Companion.minutes

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

  internal fun resolveRunWorkflowId(
    workflowService: WorkflowService,
    deps: FeatureTaskRuntimeRunDependencies,
    prepared: PreparedRuntimeRun,
    issueKey: String,
    specPath: String,
  ): String =
    explicitWorkflowId?.takeIf(String::isNotBlank)
      ?: workflowService.openRuntimeWorkflowId(
        WorkflowServiceOpenFeatureTaskArgs(
          kind = WorkflowFamilyKind.TASK_RUNTIME,
          sessionId = "",
          currentStepId = null,
          issueKey = issueKey,
          repositoryIdentity = deps.inputs.repositoryEnclosingRootPort.repositoryIdentity(prepared.repoRoot),
          governedSpecPath =
            deps.inputs.repositoryEnclosingRootPort.governedSpecPathForCli(
              prepared.repoRoot,
              Path.of(specPath),
            ),
          routeScope =
            if (goalParentIssueKey != null) FeatureTaskRouteScope.GOAL_CHILD else FeatureTaskRouteScope.STANDALONE,
          executionPlan =
            deps.executionPlans.resolveCreation(
              FeatureTaskRuntimeExecutionPlanCreationRequest(
                repoRoot = prepared.repoRoot,
                definition = SkeletonDefinition.forRun(prepared.goalContinuation != null),
                reviewMode =
                  prepared.goalContinuation?.codeReviewMode ?: requestedCodeReviewMode()
                    ?: deps.runInvariantsSource.read(Path.of(specPath)).codeReviewMode,
                qualityGate = prepared.goalContinuation?.qualityGateSelection,
                validationDepth = prepared.goalContinuation?.validationDepth ?: ValidationDepth.DEFAULT,
                timeout = maxWallClockMinutes.takeIf { it > 0 }?.minutes,
              ),
            ),
        ),
      )

  internal fun executeRuntimeRun(
    deps: FeatureTaskRuntimeRunDependencies,
    issueKey: String,
    specPath: String,
    prepared: PreparedRuntimeRun,
    workflowId: () -> String,
  ) {
    val state = deps.state
    val resolvedWorkflowId = workflowId()
    val report =
      deps.workerCoordinator.runOwned(
        resolvedWorkflowId,
        deps.executionPlans.resolveInputs(
          prepared.repoRoot,
          prepared.goalContinuation?.qualityGateSelection,
          prepared.goalContinuation?.validationDepth ?: ValidationDepth.DEFAULT,
          maxWallClockMinutes.takeIf { it > 0 }?.minutes,
          resolvedWorkflowId,
        ),
        FeatureTaskExecutionIdentity(
          workflowId = resolvedWorkflowId,
          normalizedIssueKey = issueKey.trim().uppercase(),
          repositoryIdentity = deps.inputs.repositoryEnclosingRootPort.repositoryIdentity(prepared.repoRoot),
          governedSpecPath =
            deps.inputs.repositoryEnclosingRootPort.governedSpecPathForCli(
              prepared.repoRoot,
              Path.of(specPath),
            ),
          mode = FeatureTaskWorkflowMode.RUNTIME,
          routeScope =
            if (prepared.goalContinuation != null) {
              FeatureTaskRouteScope.GOAL_CHILD
            } else {
              FeatureTaskRouteScope.STANDALONE
            },
        ),
        requestedReviewSelection =
          (prepared.goalContinuation?.codeReviewMode ?: requestedCodeReviewMode())
            ?.let { RuntimeReviewSelection.valueOf(it.name) },
      ) { admittedExecution ->
        val sourceInvariants = deps.runInvariantsSource.read(Path.of(specPath))
        val request =
          FeatureTaskRuntimeRunRequest(
            issueKey = issueKey,
            workflowId = resolvedWorkflowId,
            admittedExecution = admittedExecution,
            sessionId =
              "${FeatureTaskRuntimePhaseWorkflowDefinition.definition.defaultSessionPrefix}-$resolvedWorkflowId",
            runInvariants =
              sourceInvariants.copy(
                codeReviewMode =
                  admittedExecution.reviewMode
                    ?: sourceInvariants.codeReviewMode,
                agentAddonSelection = prepared.agentAddonSelection.persisted,
              ),
            invokedAgentId = prepared.invokedAgentId,
            agentAssignment = prepared.agentAssignment,
            modelAssignment = prepared.modelAssignment,
            compactionSettings = prepared.compactionSettings,
            environment = deps.inputs.environment,
            repoRoot = prepared.repoRoot,
            timeout = maxWallClockMinutes.takeIf { it > 0 }?.minutes,
            requestedCodeReviewMode = requestedCodeReviewMode(),
            goalContinuation = prepared.goalContinuation,
            operatorDecision = prepared.operatorDecision,
            agentAddonSelection = prepared.agentAddonSelection,
            eventSink = runtimeRunEventSink(deps.inputs, monitor),
          )
        deps.inputs.featureTaskRuntimeRunOverride?.invoke(request) ?: deps.runner.run(request)
      }
    val payload = report.toRuntimeRunCliMap()
    state.completeText(runtimeRunText(report), payload, exitCode = report.runtimeRunExitCode())
    drainTelemetryOnCompletion(deps.telemetryService, deps.diagnostics)
  }

  internal fun resolveSpecPath(
    deps: FeatureTaskRuntimeRunDependencies,
    issueKey: String,
    explicitSpecPath: String?,
    repositoryRoot: Path,
  ): String {
    val result =
      deps.specPathResolver.resolve(
        FeatureSpecPathResolveInput(
          issueKey = issueKey,
          explicitSpecPath = explicitSpecPath,
          repoRoot = repositoryRoot,
        ),
      )
    return when (result) {
      is FeatureSpecPathResolveResult.Explicit -> result.specPath
      is FeatureSpecPathResolveResult.SingleMatch -> result.specPath
      is FeatureSpecPathResolveResult.NoMatch -> throw UsageError(
        "spec_path is required for feature-task run; no .feature-specs match found for '${result.issueKey}' " +
          "under ${result.specsRoot}.",
      )
      is FeatureSpecPathResolveResult.Ambiguous -> throw UsageError(
        "spec_path is required for feature-task run; multiple .feature-specs matches found for '${result.issueKey}': " +
          result.matches.joinToString(", "),
      )
    }
  }
}

@Inject
class FeatureTaskRuntimeRunCommand(
  private val deps: FeatureTaskRuntimeRunDependencies,
  private val workflowService: WorkflowService,
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
    subcommands(
      featureTaskRuntimeExplicitRunCommand,
      control.status,
      control.resume,
      control.abandon,
      control.retryBlocked,
      control.repairIdentity,
      control.lookup,
      rejectedOutput.inspect,
      rejectedOutput.cleanup,
    )
  }

  override fun run() {
    if (currentContext.invokedSubcommand != null) {
      return
    }
    val runIssueKey = issueKey ?: throw UsageError("issue_key is required for feature-task run.")
    val resolvedRepoRoot = resolveCliRepositoryRoot(repoRoot, deps.inputs)
    val runSpecPath = resolveSpecPath(deps, runIssueKey, specPath, resolvedRepoRoot)
    val prepared = prepareRuntimeRun(deps, resolvedRepoRoot)
    executeRuntimeRun(
      deps = deps,
      issueKey = runIssueKey,
      specPath = runSpecPath,
      prepared = prepared,
      workflowId = {
        resolveRunWorkflowId(
          workflowService,
          deps,
          prepared,
          runIssueKey,
          runSpecPath,
        )
      },
    )
  }
}

@Inject
class FeatureTaskRuntimeExplicitRunCommand(
  private val deps: FeatureTaskRuntimeRunDependencies,
  private val workflowService: WorkflowService,
) : FeatureTaskRuntimePhaseAgentCommand(
    FeatureTaskRuntimeGoalContinuationLaunchTokens.RUN_SUBCOMMAND,
    "Run the feature-task phase loop (explicit form of the parent command's default run).",
  ) {
  private val issueKey by argument(help = "Issue key the run implements.")
  private val specPath by argument(help = "Path to the governed spec the run implements.").optional()

  override fun run() {
    val resolvedRepoRoot = resolveCliRepositoryRoot(repoRoot, deps.inputs)
    val runSpecPath = resolveSpecPath(deps, issueKey, specPath, resolvedRepoRoot)
    val prepared = prepareRuntimeRun(deps, resolvedRepoRoot)
    executeRuntimeRun(
      deps = deps,
      issueKey = issueKey,
      specPath = runSpecPath,
      prepared = prepared,
      workflowId = {
        resolveRunWorkflowId(
          workflowService,
          deps,
          prepared,
          issueKey,
          runSpecPath,
        )
      },
    )
  }
}
