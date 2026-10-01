package skillbill.cli.featuretask

import com.github.ajalt.clikt.core.UsageError
import me.tatarka.inject.annotations.Inject
import skillbill.agentaddon.model.AgentAddonConsumer
import skillbill.agentaddon.model.HydratedAgentAddonSelection
import skillbill.application.config.ConfigResolutionService
import skillbill.cli.kernel.agent.parseAgentAddonSelection
import skillbill.cli.kernel.agent.refuseUnavailableAgentLaunchers
import skillbill.cli.kernel.agent.refuseUnsupportedModelDirectives
import skillbill.cli.kernel.cli.resolveCliRepositoryRoot
import skillbill.cli.model.CliRunInputs
import skillbill.engine.featuretask.lifecycle.continuation.FeatureTaskContinuationLookupService
import skillbill.engine.featuretask.lifecycle.core.FeatureTaskRuntimeAgentResolver
import skillbill.engine.featuretask.lifecycle.core.FeatureTaskRuntimeModelResolver
import skillbill.engine.featuretask.model.continuation.FeatureTaskContinuationLookupResult
import skillbill.engine.featuretask.model.core.FeatureTaskRuntimeAgentAssignment
import skillbill.engine.featuretask.model.core.FeatureTaskRuntimeModelAssignment
import skillbill.ports.agentaddon.AgentAddonSelectionPort
import skillbill.ports.agentrun.ExecutableLookup
import skillbill.ports.featurespec.FeatureSpecPathResolverPort
import skillbill.ports.featurespec.model.FeatureSpecPathResolveInput
import skillbill.ports.featurespec.model.FeatureSpecPathResolveResult
import skillbill.ports.repository.RepositoryEnclosingRootPort
import skillbill.workflow.model.FeatureTaskWorkflowMode
import skillbill.workflow.taskruntime.phase.task.FeatureTaskRuntimePhaseWorkflowDefinition
import java.nio.file.Path

@Inject
class FeatureTaskRuntimeRunPreparation(
  private val specPathResolver: FeatureSpecPathResolverPort,
  private val configResolutionService: ConfigResolutionService,
  private val agentAddonSelectionPort: AgentAddonSelectionPort,
  private val executableLookup: ExecutableLookup,
  private val lookupService: FeatureTaskContinuationLookupService,
  private val repositoryEnclosingRootPort: RepositoryEnclosingRootPort,
  private val inputs: CliRunInputs,
) {
  internal fun prepareRun(
    options: FeatureTaskRuntimePhaseAgentCommand,
    issueKey: String?,
    explicitSpecPath: String?,
  ): PreparedRuntimeRun {
    val runIssueKey = issueKey ?: throw UsageError("issue_key is required for feature-task run.")
    val resolvedRepoRoot = resolveCliRepositoryRoot(options.repoRoot, inputs)
    val runSpecPath = resolveSpecPath(runIssueKey, explicitSpecPath, resolvedRepoRoot)
    val prepared = prepare(options, resolvedRepoRoot, runIssueKey, runSpecPath)
    repositoryEnclosingRootPort.governedSpecPathForCli(prepared.repoRoot, Path.of(prepared.specPath))
    return prepared
  }

  internal fun prepareResume(
    options: FeatureTaskRuntimePhaseAgentCommand,
    workflowId: String,
    issueKey: String,
    specPath: String,
  ): PreparedRuntimeRun {
    val resolvedRepoRoot = resolveCliRepositoryRoot(options.repoRoot, inputs)
    val prepared = prepare(options, resolvedRepoRoot, issueKey, specPath)
    verifyRuntimeResume(workflowId, issueKey, specPath, prepared.repoRoot, options.goalParentIssueKey != null)
    return prepared
  }

  private fun verifyRuntimeResume(
    workflowId: String,
    issueKey: String,
    specPath: String,
    repoRoot: Path,
    goalChild: Boolean,
  ) {
    val identity = repositoryEnclosingRootPort.repositoryIdentity(repoRoot)
    val result =
      if (goalChild) {
        lookupService.lookupGoalChild(issueKey, identity, workflowId)
      } else {
        lookupService.lookup(issueKey, identity, workflowId)
      }
    val candidate = resumableRuntimeCandidate(workflowId, result)
    requireRuntimeMode(workflowId, candidate.mode)
    requireMatchingGovernedSpec(workflowId, candidate.governedSpecPath, repoRoot, Path.of(specPath))
  }

  private fun requireMatchingGovernedSpec(
    workflowId: String,
    persistedPath: String,
    repoRoot: Path,
    specPath: Path,
  ) {
    if (persistedPath != repositoryEnclosingRootPort.governedSpecPathForCli(repoRoot, specPath)) {
      throw UsageError("Workflow '$workflowId' was persisted with a different governed spec path.")
    }
  }

  private fun resolveSpecPath(
    issueKey: String,
    explicitSpecPath: String?,
    repositoryRoot: Path,
  ): String {
    val result =
      specPathResolver.resolve(
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

  private fun prepare(
    options: FeatureTaskRuntimePhaseAgentCommand,
    resolvedRepoRoot: Path,
    issueKey: String,
    specPath: String,
  ): PreparedRuntimeRun {
    val environment = inputs.environment
    val goalContinuation = options.parseGoalContinuationContext(environment)
    val operatorDecision = options.requestedOperatorDecision()
    val invokedAgentId = resolveInvokedRuntimeAgentId(options.agent, environment)
    val phaseAgentMap = parsePhaseAgents(options.phaseAgents).toMutableMap()
    val agentOverride = options.agentOverride?.takeIf(String::isNotBlank)
    val agentAssignment =
      FeatureTaskRuntimeAgentAssignment(
        perPhaseAgentIds = phaseAgentMap,
        override = agentOverride,
      )
    val modelAssignment =
      FeatureTaskRuntimeModelAssignment(
        perPhaseDirectives = parsePhaseModels(options.phaseModels),
        matrix = configResolutionService.resolveExecutionMatrix(),
      )
    val compactionSettings = configResolutionService.resolveCompactionSettings()
    val resolvedAgentIds =
      FeatureTaskRuntimePhaseWorkflowDefinition.definition.stepIds.associateWith { phaseId ->
        FeatureTaskRuntimeAgentResolver.resolve(phaseId, agentAssignment, invokedAgentId).resolvedAgentId
      }
    val directives =
      resolvedAgentIds.mapNotNull { (phaseId, resolvedAgentId) ->
        FeatureTaskRuntimeModelResolver.resolve(phaseId, resolvedAgentId, modelAssignment)?.let { directive ->
          phaseId to directive
        }
      }.toMap()
    refuseUnsupportedModelDirectives(directives, resolvedAgentIds)
    val receivingAgents =
      buildList {
        addAll(resolvedAgentIds.values)
        addAll(phaseAgentMap.values)
        agentOverride?.let(::add)
      }.distinct()
    refuseUnavailableAgentLaunchers(receivingAgents, executableLookup)
    val persistedSelection = parseAgentAddonSelection(options.agentAddonSelectionJson)
    val hydratedSelection =
      if (persistedSelection.entries.isEmpty()) {
        HydratedAgentAddonSelection()
      } else {
        agentAddonSelectionPort.verifyPersisted(
          persistedSelection,
          AgentAddonConsumer.SKILL_BILL,
          receivingAgents,
        )
      }
    return PreparedRuntimeRun(
      issueKey,
      specPath,
      resolvedRepoRoot,
      invokedAgentId,
      agentAssignment,
      modelAssignment,
      compactionSettings,
      hydratedSelection,
      goalContinuation,
      operatorDecision,
    )
  }
}

private fun resumableRuntimeCandidate(
  workflowId: String,
  result: FeatureTaskContinuationLookupResult,
) = when (result) {
  is FeatureTaskContinuationLookupResult.Resumable -> result.candidate
  is FeatureTaskContinuationLookupResult.AlreadyRunning -> result.candidate
  is FeatureTaskContinuationLookupResult.TerminalOnly ->
    throw UsageError("Workflow '$workflowId' is terminal and cannot be resumed; no phase was launched.")
  FeatureTaskContinuationLookupResult.NoMatch,
  is FeatureTaskContinuationLookupResult.Ambiguous,
  is FeatureTaskContinuationLookupResult.GoalContinuation,
  is FeatureTaskContinuationLookupResult.NeedsIdentityRepair,
  -> throw UsageError("Workflow '$workflowId' is not a resumable runtime workflow.")
}

private fun requireRuntimeMode(
  workflowId: String,
  mode: FeatureTaskWorkflowMode,
) {
  if (mode != FeatureTaskWorkflowMode.RUNTIME) {
    throw UsageError("Workflow '$workflowId' was persisted in ${mode.wireValue} mode.")
  }
}
