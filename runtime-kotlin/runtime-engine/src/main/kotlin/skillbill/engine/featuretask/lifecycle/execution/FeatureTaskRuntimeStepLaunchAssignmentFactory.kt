package skillbill.engine.featuretask.lifecycle.execution

import me.tatarka.inject.annotations.Inject
import skillbill.agentaddon.model.AgentAddonConsumer
import skillbill.agentaddon.model.AgentAddonSelection
import skillbill.config.model.PhaseModelDirective
import skillbill.config.model.ResolvedWorkflowAgentAssignments
import skillbill.config.model.WorkflowAgentAssignmentPolicyMode
import skillbill.engine.diagnostics.RuntimeDiagnosticsBestEffortWarning
import skillbill.engine.featuretask.lifecycle.core.FeatureTaskRuntimeAgentResolver
import skillbill.engine.featuretask.lifecycle.core.FeatureTaskRuntimeModelResolver
import skillbill.engine.featuretask.model.core.FeatureTaskRuntimeAgentAssignment
import skillbill.engine.featuretask.model.core.FeatureTaskRuntimeModelAssignment
import skillbill.engine.featuretask.slot.PhaseQualityGateOperation
import skillbill.engine.featuretask.slot.PhaseStrategyLookup
import skillbill.engine.featuretask.slot.PhaseStrategySelectionFacts
import skillbill.error.featuretask.IncompatibleFeatureTaskRuntimeExecutionPlanError
import skillbill.install.model.SupportedAgent
import skillbill.install.model.unavailableAgentLauncherReason
import skillbill.ports.agentaddon.AgentAddonSelectionPort
import skillbill.ports.agentrun.AgentRunLauncher
import skillbill.ports.agentrun.ExecutableLookup
import skillbill.ports.agentrun.model.AgentRunLaunchCapability
import skillbill.ports.agentrun.model.AgentRunLaunchCapabilityRequest
import skillbill.ports.agentrun.model.AgentRunLaunchModelRequest
import skillbill.ports.diagnostics.RuntimeDiagnostics
import skillbill.workflow.taskruntime.model.skeleton.LaunchEnvironmentKind
import skillbill.workflow.taskruntime.model.skeleton.PhaseSlot
import skillbill.workflow.taskruntime.model.skeleton.ResolvedPhaseExecutionPlan
import skillbill.workflow.taskruntime.model.skeleton.StepLaunchAssignment

public data class StepLaunchAssignmentInputs(
  public val invokedAgentId: String,
  public val agentAssignment: FeatureTaskRuntimeAgentAssignment,
  public val modelAssignment: FeatureTaskRuntimeModelAssignment,
  public val environmentKind: LaunchEnvironmentKind = LaunchEnvironmentKind.INHERITED,
  public val workflowAgentAssignments: ResolvedWorkflowAgentAssignments? = null,
  public val agentAddonSelection: AgentAddonSelection = AgentAddonSelection(),
  public val legacyPausedStepAssignments: Map<String, String> = emptyMap(),
)

private const val MAX_DIAGNOSTIC_ID = 128

@Inject
public class FeatureTaskRuntimeStepLaunchResolver(
  private val launcher: AgentRunLauncher,
  private val lookup: PhaseStrategyLookup,
  private val agentAddonSelectionPort: AgentAddonSelectionPort,
  private val executableLookup: ExecutableLookup,
) {
  public fun resolveDirective(
    stepId: String,
    agentId: String,
    directive: PhaseModelDirective,
    environmentKind: LaunchEnvironmentKind,
  ): StepLaunchAssignment =
    StepLaunchAssignment(
      stepId,
      agentId,
      launcher.resolveLaunchModel(
        AgentRunLaunchModelRequest(agentId, directive.model, directive.effort, environmentKind),
      ),
    )

  public fun resolve(
    facts: PhaseStrategySelectionFacts,
    inputs: StepLaunchAssignmentInputs,
  ): Map<String, StepLaunchAssignment> =
    run {
      verifyAddons(facts, inputs)
      FeatureTaskRuntimeStepLaunchAssignmentFactory.resolve(launcher, lookup, facts, inputs)
    }

  internal fun verifyLaunchCapability(
    assignments: Map<String, StepLaunchAssignment>,
    environmentKind: LaunchEnvironmentKind,
    diagnostics: RuntimeDiagnostics,
  ) {
    assignments.forEach { (phaseId, assignment) ->
      val providerId = assignment.agentId
      val provider = supportedProvider(phaseId, providerId, diagnostics)
      when (
        val support =
          launcher.queryLaunchCapability(AgentRunLaunchCapabilityRequest(provider.wireValue, environmentKind))
      ) {
        AgentRunLaunchCapability.Supported -> {}

        is AgentRunLaunchCapability.Unsupported -> {
          recordRoutingRefusal(diagnostics, phaseId, provider.wireValue, "unsupported_adapter")
          throw IncompatibleFeatureTaskRuntimeExecutionPlanError(
            "${support.reason} Selected for phase '$phaseId'.",
          )
        }
      }
      unavailableAgentLauncherReason(provider.wireValue, executableLookup::onPath)?.let { reason ->
        recordRoutingRefusal(diagnostics, phaseId, provider.wireValue, "executable_unavailable")
        throw IncompatibleFeatureTaskRuntimeExecutionPlanError("$reason Selected for phase '$phaseId'.")
      }
    }
  }

  private fun supportedProvider(
    phaseId: String,
    providerId: String,
    diagnostics: RuntimeDiagnostics,
  ): SupportedAgent =
    SupportedAgent.fromWireOrNull(providerId)
      ?: run {
        recordRoutingRefusal(diagnostics, phaseId, providerId, "unknown_provider")
        throw IncompatibleFeatureTaskRuntimeExecutionPlanError(
          "Unknown provider '$providerId' is selected for phase '$phaseId'.",
        )
      }

  private fun recordRoutingRefusal(
    diagnostics: RuntimeDiagnostics,
    phaseId: String,
    providerId: String,
    reason: String,
  ) {
    RuntimeDiagnosticsBestEffortWarning.record(
      diagnostics,
      "seam=workflow_agent_assignment phase=${phaseId.take(
        MAX_DIAGNOSTIC_ID,
      )} provider=${providerId.take(MAX_DIAGNOSTIC_ID)} " +
        "reason=$reason outcome=refused",
    )
  }

  public fun verifyAddons(
    facts: PhaseStrategySelectionFacts,
    inputs: StepLaunchAssignmentInputs,
  ) {
    if (inputs.agentAddonSelection.entries.isEmpty()) return
    val plan = lookup.executionPlan(facts.copy(stepAssignments = emptyMap()))
    val agents =
      FeatureTaskRuntimeStepLaunchAssignmentFactory
        .agentBackedStepIds(lookup, plan)
        .mapNotNull { stepId ->
          inputs.workflowAgentAssignments
            ?.takeIf { it.policyMode == WorkflowAgentAssignmentPolicyMode.FROZEN }
            ?.phaseAssignments
            ?.get(stepId)
            ?.agentId
            ?.wireValue
            ?: inputs.legacyPausedStepAssignments[stepId]
            ?: FeatureTaskRuntimeAgentResolver
              .resolve(
                stepId,
                inputs.agentAssignment.copy(
                  matrix =
                    inputs.agentAssignment.matrix.takeIf {
                      inputs.workflowAgentAssignments?.policyMode != WorkflowAgentAssignmentPolicyMode.LEGACY
                    },
                ),
                inputs.invokedAgentId,
              ).resolvedAgentId
        }.distinct()
    agentAddonSelectionPort.verifyPersisted(inputs.agentAddonSelection, AgentAddonConsumer.SKILL_BILL, agents)
  }
}

public object FeatureTaskRuntimeStepLaunchAssignmentFactory {
  public fun resolve(
    launcher: AgentRunLauncher,
    lookup: PhaseStrategyLookup,
    facts: PhaseStrategySelectionFacts,
    inputs: StepLaunchAssignmentInputs,
  ): Map<String, StepLaunchAssignment> {
    if (inputs.invokedAgentId.isBlank()) return emptyMap()
    val canonical = lookup.executionPlan(facts.copy(stepAssignments = emptyMap()))
    val assignments = linkedMapOf<String, StepLaunchAssignment>()
    agentBackedStepIds(lookup, canonical).forEach { stepId ->
      val agentId =
        inputs.workflowAgentAssignments
          ?.takeIf { it.policyMode == WorkflowAgentAssignmentPolicyMode.FROZEN }
          ?.phaseAssignments
          ?.get(stepId)
          ?.agentId
          ?.wireValue
          ?: FeatureTaskRuntimeAgentResolver
            .resolve(
              stepId,
              inputs.agentAssignment.copy(
                matrix =
                  inputs.agentAssignment.matrix.takeIf {
                    inputs.workflowAgentAssignments?.policyMode != WorkflowAgentAssignmentPolicyMode.LEGACY
                  },
              ),
              inputs.invokedAgentId,
            ).resolvedAgentId
      val requested = FeatureTaskRuntimeModelResolver.resolve(stepId, agentId, inputs.modelAssignment)
      val launch =
        launcher.resolveLaunchModel(
          AgentRunLaunchModelRequest(
            agentId = agentId,
            requestedModel = requested?.model,
            requestedEffort = requested?.effort,
            environmentKind = inputs.environmentKind,
          ),
        )
      assignments[stepId] = StepLaunchAssignment(stepId, agentId, launch)
    }
    return assignments
  }

  internal fun agentBackedStepIds(
    lookup: PhaseStrategyLookup,
    plan: ResolvedPhaseExecutionPlan,
  ): List<String> =
    plan.selectedStepIds.filter { stepId ->
      val strategy = lookup.strategyFor(stepId, plan)
      when (strategy.slot) {
        PhaseSlot.INTAKE, PhaseSlot.COMMIT_PUSH, PhaseSlot.STANDALONE_REVIEW -> false
        PhaseSlot.QUALITY_GATE -> lookup.qualityGateOperation(plan, stepId) == PhaseQualityGateOperation.AgentValidation
        else -> true
      }
    }
}
