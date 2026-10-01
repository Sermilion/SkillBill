package skillbill.cli.featuretask

import com.github.ajalt.clikt.parameters.arguments.argument
import com.github.ajalt.clikt.parameters.options.option
import com.github.ajalt.clikt.parameters.options.required
import me.tatarka.inject.annotations.Inject
import skillbill.application.workflow.model.RepairFeatureTaskRuntimeIdentityArgs
import skillbill.application.workflow.model.WorkflowUpdateResult
import skillbill.application.workflow.service.WorkflowService
import skillbill.cli.kernel.cli.CliRunState
import skillbill.cli.kernel.cli.DocumentedCliCommand
import skillbill.cli.kernel.cli.formatOption
import skillbill.cli.kernel.cli.resolveCliRepositoryRoot
import skillbill.cli.kernel.payload.toFeatureTaskContinuationCliMap
import skillbill.cli.kernel.payload.toGoalContinuationCliMap
import skillbill.cli.kernel.payload.toPayload
import skillbill.cli.model.CliRunInputs
import skillbill.contracts.SharedPayloadKeys
import skillbill.contracts.workflow.identity.task.FeatureTaskRuntimeGoalContinuationLaunchTokens
import skillbill.engine.featuretask.lifecycle.continuation.FeatureTaskContinuationLookupService
import skillbill.engine.featuretask.model.continuation.FeatureTaskContinuationLookupResult
import skillbill.engine.featuretask.model.core.FeatureTaskRuntimeStatusRequest
import skillbill.engine.featuretask.runner.FeatureTaskRuntimeStatusService
import skillbill.goalrunner.model.GoalContinuation
import skillbill.ports.repository.RepositoryEnclosingRootPort
import java.nio.file.Path

@Inject
class FeatureTaskLookupCommand(
  private val lookupService: FeatureTaskContinuationLookupService,
  private val repositoryEnclosingRootPort: RepositoryEnclosingRootPort,
  private val state: CliRunState,
  private val inputs: CliRunInputs,
) : DocumentedCliCommand(
    "lookup",
    "Read-only, repository-scoped lookup of DB-authoritative feature-task continuation state.",
  ) {
  private val issueKey by argument(help = "Issue key to find.")
  private val repoRoot by option(
    "--repo-root",
    help = "Path within the Git worktree. Defaults to the invocation repository root.",
  )
  private val workflowId by option(
    FeatureTaskRuntimeGoalContinuationLaunchTokens.WORKFLOW_ID_FLAG,
    help = "Explicit matching workflow selection.",
  )
  private val format by formatOption()

  override fun run() {
    val result =
      lookupService.lookup(
        issueKey,
        repositoryEnclosingRootPort.repositoryIdentity(resolveCliRepositoryRoot(repoRoot, inputs)),
        workflowId,
      )
    val payload = result.toCliPayload()
    state.complete(payload, format, if (result is FeatureTaskContinuationLookupResult.Ambiguous) 2 else 0)
  }
}

private fun FeatureTaskContinuationLookupResult.toCliPayload(): Map<String, Any?> =
  when (this) {
    FeatureTaskContinuationLookupResult.NoMatch -> mapOf("result" to "no_match")
    is FeatureTaskContinuationLookupResult.Resumable ->
      mapOf("result" to "resumable", "candidate" to candidate.toFeatureTaskContinuationCliMap())
    is FeatureTaskContinuationLookupResult.AlreadyRunning ->
      mapOf("result" to "already_running", "candidate" to candidate.toFeatureTaskContinuationCliMap())
    is FeatureTaskContinuationLookupResult.Ambiguous ->
      mapOf("result" to "ambiguous", "candidates" to candidates.map { it.toFeatureTaskContinuationCliMap() })
    is FeatureTaskContinuationLookupResult.TerminalOnly ->
      mapOf("result" to "terminal_only", "candidates" to candidates.map { it.toFeatureTaskContinuationCliMap() })
    is FeatureTaskContinuationLookupResult.GoalContinuation ->
      mapOf("result" to "goal_continuation", "goal" to candidate.toGoalContinuationCliMap())
    is FeatureTaskContinuationLookupResult.NeedsIdentityRepair ->
      mapOf(
        "result" to "needs_identity_repair",
        SharedPayloadKeys.WORKFLOW_ID to workflowId,
        SharedPayloadKeys.SUMMARY to summary,
      )
  }

@Inject
class FeatureTaskRuntimeStatusCommand(
  private val statusService: FeatureTaskRuntimeStatusService,
  private val state: CliRunState,
) : DocumentedCliCommand("status", "Show read-only feature-task phase status.") {
  private val workflowId by argument(help = "Runtime workflow id whose phase status to show.")

  override fun run() {
    val projection =
      statusService.status(
        FeatureTaskRuntimeStatusRequest(workflowId = workflowId),
      )
    val payload = projection.toRuntimeStatusCliMap(workflowId)
    state.completeText(
      runtimeStatusText(projection, workflowId),
      payload,
      exitCode = runtimeStatusExitCode(projection),
    )
  }
}

@Inject
class FeatureTaskRuntimeResumeCommand(
  private val preparation: FeatureTaskRuntimeRunPreparation,
  private val execution: FeatureTaskRuntimeRunExecution,
) : FeatureTaskRuntimePhaseAgentCommand(
    FeatureTaskRuntimeGoalContinuationLaunchTokens.RESUME_SUBCOMMAND,
    "Resume a feature-task run against an existing workflow id.",
  ) {
  private val workflowId by argument(help = "Existing runtime workflow id to resume.")
  private val issueKey by argument(help = "Issue key the resumed run implements.")
  private val specPath by argument(help = "Path to the governed spec the run implements.")

  override fun run() {
    execution.run(this, preparation.prepareResume(this, workflowId, issueKey, specPath), workflowId)
  }
}

@Inject
class FeatureTaskRuntimeAbandonCommand(
  private val workflowService: WorkflowService,
  private val state: CliRunState,
) : DocumentedCliCommand(
    "abandon",
    "Explicitly terminalize a nonterminal feature-task workflow while preserving its durable history.",
  ) {
  private val workflowId by argument(help = "Exact feature-task workflow id to abandon.")
  private val reason by option("--reason", help = "Required operator reason recorded with the workflow.").required()
  private val format by formatOption()

  override fun run() {
    val result = workflowService.abandonFeatureTaskRuntime(workflowId, reason)
    state.complete(result.toPayload(), format, exitCode = if (result is WorkflowUpdateResult.Error) 1 else 0)
  }
}

@Inject
class FeatureTaskRuntimeRetryBlockedCommand(
  private val workflowService: WorkflowService,
  private val state: CliRunState,
) : DocumentedCliCommand(
    "retry-blocked",
    "Reopen one blocked runtime phase after an operator-applied fix.",
  ) {
  private val workflowId by argument(help = "Exact runtime workflow id whose blocked phase should be retried.")
  private val phaseId by option("--phase", help = "Blocked runtime phase to reopen.").required()
  private val reason by option("--reason", help = "Required operator reason recorded with the retry.").required()
  private val format by formatOption()

  override fun run() {
    val result = workflowService.retryBlockedFeatureTaskRuntimePhase(workflowId, phaseId, reason)
    state.complete(result.toPayload(), format, exitCode = if (result is WorkflowUpdateResult.Error) 1 else 0)
  }
}

@Inject
class FeatureTaskRuntimeRepairIdentityCommand(
  private val workflowService: WorkflowService,
  private val repositoryEnclosingRootPort: RepositoryEnclosingRootPort,
  private val state: CliRunState,
  private val inputs: CliRunInputs,
) : DocumentedCliCommand(
    "repair-identity",
    "Explicitly supply missing immutable execution identity for a legacy runtime workflow.",
  ) {
  private val workflowId by argument(help = "Exact runtime workflow id whose identity is missing.")
  private val issueKey by argument(help = "Issue key persisted by the workflow.")
  private val specPath by argument(help = "Governed spec path for the workflow.")
  private val repoRoot by option(
    "--repo-root",
    help = "Canonical repository root. Defaults to the invocation repository root.",
  )
  private val reason by option("--reason", help = "Required operator reason recorded with the repair.").required()
  private val format by formatOption()

  override fun run() {
    val root = resolveCliRepositoryRoot(repoRoot, inputs)
    val result =
      workflowService.repairFeatureTaskRuntimeIdentity(
        RepairFeatureTaskRuntimeIdentityArgs(
          workflowId = workflowId,
          issueKey = issueKey,
          repositoryIdentity = repositoryEnclosingRootPort.repositoryIdentity(root),
          governedSpecPath = repositoryEnclosingRootPort.governedSpecPathForCli(root, Path.of(specPath)),
          reason = reason,
        ),
      )
    state.complete(result.toPayload(), format, exitCode = if (result is WorkflowUpdateResult.Error) 1 else 0)
  }
}
