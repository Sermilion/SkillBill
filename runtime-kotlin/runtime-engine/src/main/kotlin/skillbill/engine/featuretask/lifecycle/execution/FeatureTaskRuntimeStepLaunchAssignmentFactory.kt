package skillbill.engine.featuretask.lifecycle.execution

import me.tatarka.inject.annotations.Inject
import skillbill.agentaddon.model.AgentAddonConsumer
import skillbill.agentaddon.model.AgentAddonSelection
import skillbill.config.model.PhaseModelDirective
import skillbill.config.model.ResolvedWorkflowAgentAssignments
import skillbill.config.model.WorkflowAgentAssignmentPolicyMode
import skillbill.engine.featuretask.lifecycle.core.FeatureTaskRuntimeAgentResolver
import skillbill.engine.featuretask.lifecycle.core.FeatureTaskRuntimeModelResolver
import skillbill.engine.featuretask.model.core.FeatureTaskRuntimeAgentAssignment
import skillbill.engine.featuretask.model.core.FeatureTaskRuntimeModelAssignment
import skillbill.engine.featuretask.slot.PhaseStrategyLookup
import skillbill.engine.featuretask.slot.PhaseStrategySelectionFacts
import skillbill.ports.agentaddon.AgentAddonSelectionPort
import skillbill.ports.agentrun.AgentRunLauncher
import skillbill.ports.agentrun.model.AgentRunLaunchModelRequest
import skillbill.workflow.taskruntime.model.skeleton.LaunchEnvironmentKind
import skillbill.workflow.taskruntime.model.skeleton.PhaseSlot
import skillbill.workflow.taskruntime.model.skeleton.StepLaunchAssignment

public data class StepLaunchAssignmentInputs(
  public val invokedAgentId: String,
  public val agentAssignment: FeatureTaskRuntimeAgentAssignment,
  public val modelAssignment: FeatureTaskRuntimeModelAssignment,
  public val environmentKind: LaunchEnvironmentKind = LaunchEnvironmentKind.INHERITED,
  public val workflowAgentAssignments: ResolvedWorkflowAgentAssignments? = null,
  public val agentAddonSelection: AgentAddonSelection = AgentAddonSelection(),
)

@Inject
public class FeatureTaskRuntimeStepLaunchResolver(
  private val launcher: AgentRunLauncher,
  private val lookup: PhaseStrategyLookup,
  private val agentAddonSelectionPort: AgentAddonSelectionPort,
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

  public fun verifyAddons(
    facts: PhaseStrategySelectionFacts,
    inputs: StepLaunchAssignmentInputs,
  ) {
    if (inputs.agentAddonSelection.entries.isEmpty()) return
    val selected = lookup.executionPlan(facts.copy(stepAssignments = emptyMap()))
    val agents =
      selected.selectedStepIds.mapNotNull { stepId ->
        inputs.workflowAgentAssignments
          ?.takeIf { it.policyMode == WorkflowAgentAssignmentPolicyMode.FROZEN }
          ?.phaseAssignments?.get(stepId)?.agentId?.wireValue
          ?: FeatureTaskRuntimeAgentResolver.resolve(
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
    canonical.selectedStepIds.forEach { stepId ->
      val strategy = lookup.strategyFor(stepId, canonical)
      if (strategy.slot == PhaseSlot.INTAKE || strategy.slot == PhaseSlot.COMMIT_PUSH) return@forEach
      val agentId =
        inputs.workflowAgentAssignments
          ?.takeIf { it.policyMode == WorkflowAgentAssignmentPolicyMode.FROZEN }
          ?.phaseAssignments?.get(stepId)?.agentId?.wireValue
          ?: FeatureTaskRuntimeAgentResolver.resolve(
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
}
