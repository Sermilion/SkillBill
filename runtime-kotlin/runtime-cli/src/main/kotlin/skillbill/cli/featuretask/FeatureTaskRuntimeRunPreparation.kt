package skillbill.cli.featuretask

import com.github.ajalt.clikt.core.UsageError
import me.tatarka.inject.annotations.Inject
import skillbill.agentaddon.model.AgentAddonConsumer
import skillbill.agentaddon.model.HydratedAgentAddonSelection
import skillbill.application.config.ConfigResolutionService
import skillbill.cli.kernel.agent.parseAgentAddonSelection
import skillbill.cli.kernel.agent.refuseUnsupportedModelDirectives
import skillbill.cli.kernel.cli.resolveCliRepositoryRoot
import skillbill.cli.model.CliRunInputs
import skillbill.config.model.FrozenWorkflowAgentOptionCheck
import skillbill.config.model.PhaseModelDirective
import skillbill.config.model.WorkflowAgentAssignmentPolicyMode
import skillbill.config.model.checkFrozenWorkflowAgentOptions
import skillbill.engine.diagnostics.RuntimeDiagnosticsBestEffortWarning
import skillbill.engine.featuretask.lifecycle.assignment.WorkflowAgentAssignmentInspection
import skillbill.engine.featuretask.lifecycle.continuation.FeatureTaskContinuationLookupService
import skillbill.engine.featuretask.lifecycle.core.FeatureTaskRuntimeAgentResolver
import skillbill.engine.featuretask.lifecycle.core.FeatureTaskRuntimeResumeLaunchAssignment
import skillbill.engine.featuretask.lifecycle.execution.FeatureTaskRuntimeExecutionPlanResolver
import skillbill.engine.featuretask.lifecycle.execution.FeatureTaskRuntimeResumeEvidence
import skillbill.engine.featuretask.model.continuation.FeatureTaskContinuationLookupResult
import skillbill.engine.featuretask.model.core.FeatureTaskRuntimeAgentAssignment
import skillbill.engine.featuretask.model.core.FeatureTaskRuntimeModelAssignment
import skillbill.engine.goal.goalRepositoryIdentityCandidates
import skillbill.install.model.SupportedAgent
import skillbill.install.model.unavailableAgentLauncherReason
import skillbill.ports.agentaddon.AgentAddonSelectionPort
import skillbill.ports.agentrun.ExecutableLookup
import skillbill.ports.diagnostics.RuntimeDiagnostics
import skillbill.ports.projectstore.ProjectStorePort
import skillbill.ports.repository.RepositoryEnclosingRootPort
import skillbill.workflow.model.FeatureTaskWorkflowMode
import java.nio.file.Path

private const val MAX_DIAGNOSTIC_ID = 128

@Inject
class FeatureTaskRuntimeRunPreparation(
  private val configResolutionService: ConfigResolutionService,
  private val agentAddonSelectionPort: AgentAddonSelectionPort,
  private val executableLookup: ExecutableLookup,
  private val lookupService: FeatureTaskContinuationLookupService,
  private val repositoryEnclosingRootPort: RepositoryEnclosingRootPort,
  private val projectStorePort: ProjectStorePort,
  private val inputs: CliRunInputs,
  private val executionPlans: FeatureTaskRuntimeExecutionPlanResolver,
  private val diagnostics: RuntimeDiagnostics,
) {
  internal fun prepareResume(
    options: FeatureTaskRuntimePhaseAgentCommand,
    workflowId: String,
    issueKey: String,
    specPath: String,
  ): PreparedRuntimeRun {
    options.requestedOperatorDecision()
    options.requestedCodeReviewMode()
    val resolvedRepoRoot = resolveCliRepositoryRoot(options.repoRoot, inputs)
    verifyRuntimeResume(workflowId, issueKey, specPath, resolvedRepoRoot, options.goalParentIssueKey != null)
    val phaseAgents = parsePhaseAgents(options.phaseAgents)
    val evidence = executionPlans.inspectResumeEvidence(workflowId)
    val frozen =
      (evidence.routing as? WorkflowAgentAssignmentInspection.Present)
        ?.assignments
        ?.takeIf { it.policyMode == WorkflowAgentAssignmentPolicyMode.FROZEN }
    if (frozen != null) {
      when (val check = checkFrozenWorkflowAgentOptions(frozen, options.agentOverride, phaseAgents)) {
        FrozenWorkflowAgentOptionCheck.Accepted -> {}

        is FrozenWorkflowAgentOptionCheck.Refused -> {
          recordRoutingOutcome(
            check.phaseId,
            check.requestedAgentId,
            "explicit_provider_refused",
            check.retainedAgentId,
          )
          throw UsageError(
            if (check.reason == FrozenWorkflowAgentOptionCheck.Reason.UNKNOWN_PROVIDER) {
              "Provider '${check.requestedAgentId}' is not supported for phase '${check.phaseId}'. " +
                "Select a supported provider id and retry."
            } else {
              "Cannot resume frozen phase '${check.phaseId}' with provider '${check.requestedAgentId}'. " +
                "The recorded provider is '${check.retainedAgentId}'. " +
                "Remove the conflicting option or use the recorded provider; " +
                "frozen assignments cannot be retargeted."
            },
          )
        }
      }
    }
    return prepare(options, resolvedRepoRoot, issueKey, specPath, evidence)
  }

  private fun verifyRuntimeResume(
    workflowId: String,
    issueKey: String,
    specPath: String,
    repoRoot: Path,
    goalChild: Boolean,
  ) {
    val result =
      if (goalChild) {
        val candidates = goalRepositoryIdentityCandidates(repoRoot, repositoryEnclosingRootPort)
        lookupService.lookupGoalChild(issueKey, lookupService.selectorIdentity(workflowId, candidates), workflowId)
      } else {
        lookupService.lookup(issueKey, repositoryEnclosingRootPort.repositoryIdentity(repoRoot), workflowId)
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
    if (persistedPath != repositoryEnclosingRootPort.governedSpecPathForCli(projectStorePort, repoRoot, specPath)) {
      throw UsageError("Workflow '$workflowId' was persisted with a different governed spec path.")
    }
  }

  private fun prepare(
    options: FeatureTaskRuntimePhaseAgentCommand,
    resolvedRepoRoot: Path,
    issueKey: String,
    specPath: String,
    evidence: FeatureTaskRuntimeResumeEvidence,
  ): PreparedRuntimeRun {
    val environment = inputs.environment
    val qaSelection = options.requestedQaSelection(environment)
    val goalContinuation = options.parseGoalContinuationContext(environment)
    val operatorDecision = options.requestedOperatorDecision()
    val invokedAgentId = resolveInvokedRuntimeAgentId(options.agent, environment)
    val phaseAgentMap = parsePhaseAgents(options.phaseAgents)
    val agentOverride = options.agentOverride?.takeIf(String::isNotBlank)
    val parsedAgentAssignment =
      FeatureTaskRuntimeAgentAssignment(
        perPhaseAgentIds = phaseAgentMap,
        override = agentOverride,
      )
    val frozen =
      (evidence.routing as? WorkflowAgentAssignmentInspection.Present)
        ?.assignments
        ?.takeIf { it.policyMode == WorkflowAgentAssignmentPolicyMode.FROZEN }
    val legacy =
      evidence.routing == WorkflowAgentAssignmentInspection.Legacy ||
        (evidence.routing as? WorkflowAgentAssignmentInspection.Present)
          ?.assignments
          ?.policyMode == WorkflowAgentAssignmentPolicyMode.LEGACY
    val modelAssignment =
      FeatureTaskRuntimeModelAssignment(
        perPhaseDirectives = parsePhaseModels(options.phaseModels),
        matrix = configResolutionService.resolveExecutionMatrix(),
      )
    val agentAssignment = parsedAgentAssignment.copy(matrix = modelAssignment.matrix.takeIf { frozen != null })
    val compactionSettings = configResolutionService.resolveCompactionSettings()
    val selectedPhaseIds = executionPlans.agentBackedStepIds(evidence.plan).toSet()
    val resolvedAgentIds =
      selectedPhaseIds.associateWith { phaseId ->
        val legacyPausedProvider =
          if (legacy && phaseId in evidence.pausedStepIds) {
            evidence.plan.stepLaunchAssignments[phaseId]?.agentId
          } else {
            null
          }
        frozen
          ?.phaseAssignments
          ?.get(phaseId)
          ?.agentId
          ?.wireValue
          ?: legacyPausedProvider
          ?: FeatureTaskRuntimeAgentResolver.resolve(phaseId, agentAssignment, invokedAgentId).resolvedAgentId
      }
    val directives = resumeModelDirectives(evidence, resolvedAgentIds, modelAssignment, issueKey)
    refuseUnsupportedModelDirectives(directives, resolvedAgentIds)
    val receivingAgents = resolvedAgentIds.values.distinct()
    validateResumeProviders(resolvedAgentIds)
    return PreparedRuntimeRun(
      issueKey,
      specPath,
      resolvedRepoRoot,
      invokedAgentId,
      agentAssignment,
      modelAssignment,
      compactionSettings,
      hydrateResumeAddons(options.agentAddonSelectionJson, receivingAgents),
      goalContinuation,
      qaSelection,
      operatorDecision,
    )
  }

  private fun validateResumeProviders(resolvedAgentIds: Map<String, String>) {
    resolvedAgentIds.values.firstOrNull { SupportedAgent.fromWireOrNull(it) == null }?.let { provider ->
      val phaseId = resolvedAgentIds.entries.firstOrNull { it.value == provider }?.key ?: "resume"
      recordRoutingOutcome(phaseId, provider, "unknown_provider")
      throw UsageError("Provider '$provider' is not supported. Select a supported provider id and retry.")
    }
    resolvedAgentIds.forEach { (phaseId, providerId) ->
      unavailableAgentLauncherReason(providerId, executableLookup::onPath)?.let { reason ->
        recordRoutingOutcome(phaseId, providerId, "executable_unavailable")
        throw UsageError("$reason Selected for phase '$phaseId'.")
      }
    }
  }

  private fun hydrateResumeAddons(
    selectionJson: String?,
    receivingAgents: List<String>,
  ): HydratedAgentAddonSelection {
    val persistedSelection = parseAgentAddonSelection(selectionJson)
    return if (persistedSelection.entries.isEmpty()) {
      HydratedAgentAddonSelection()
    } else {
      agentAddonSelectionPort.verifyPersisted(persistedSelection, AgentAddonConsumer.SKILL_BILL, receivingAgents)
    }
  }

  private fun resumeModelDirectives(
    evidence: FeatureTaskRuntimeResumeEvidence,
    resolvedAgentIds: Map<String, String>,
    modelAssignment: FeatureTaskRuntimeModelAssignment,
    issueKey: String,
  ): Map<String, PhaseModelDirective> {
    val frozen =
      (evidence.routing as? WorkflowAgentAssignmentInspection.Present)
        ?.assignments
        ?.policyMode == WorkflowAgentAssignmentPolicyMode.FROZEN
    return resolvedAgentIds
      .mapNotNull { (phaseId, resolvedAgentId) ->
        val recorded = evidence.plan.stepLaunchAssignments[phaseId]
        if (frozen && recorded != null && recorded.agentId != resolvedAgentId) {
          recordRoutingOutcome(phaseId, resolvedAgentId, "contradictory_retained_provider", recorded.agentId)
          throw UsageError("Workflow '$issueKey' has contradictory retained provider metadata for phase '$phaseId'.")
        }
        FeatureTaskRuntimeResumeLaunchAssignment
          .modelDirective(
            recorded,
            resolvedAgentId,
            phaseId,
            modelAssignment,
          )?.let { phaseId to it }
      }.toMap()
  }

  private fun recordRoutingOutcome(
    phaseId: String,
    providerId: String,
    reason: String,
    retainedProvider: String? = null,
  ) {
    val retained = retainedProvider?.let { "retained=${it.take(MAX_DIAGNOSTIC_ID)} " }.orEmpty()
    RuntimeDiagnosticsBestEffortWarning.record(
      diagnostics,
      "seam=workflow_agent_assignment phase=${phaseId.take(MAX_DIAGNOSTIC_ID)} " +
        "provider=${providerId.take(MAX_DIAGNOSTIC_ID)} " +
        "${retained}reason=$reason outcome=refused",
    )
  }
}

private fun resumableRuntimeCandidate(
  workflowId: String,
  result: FeatureTaskContinuationLookupResult,
) = when (result) {
  is FeatureTaskContinuationLookupResult.Resumable -> {
    result.candidate
  }

  is FeatureTaskContinuationLookupResult.AlreadyRunning -> {
    result.candidate
  }

  is FeatureTaskContinuationLookupResult.TerminalOnly -> {
    throw UsageError("Workflow '$workflowId' is terminal and cannot be resumed; no phase was launched.")
  }

  FeatureTaskContinuationLookupResult.NoMatch,
  is FeatureTaskContinuationLookupResult.GoalContinuation,
  is FeatureTaskContinuationLookupResult.NeedsIdentityRepair,
  -> {
    throw UsageError("Workflow '$workflowId' is not a resumable runtime workflow.")
  }
}

private fun requireRuntimeMode(
  workflowId: String,
  mode: FeatureTaskWorkflowMode,
) {
  if (mode != FeatureTaskWorkflowMode.RUNTIME) {
    throw UsageError("Workflow '$workflowId' was persisted in ${mode.wireValue} mode.")
  }
}
