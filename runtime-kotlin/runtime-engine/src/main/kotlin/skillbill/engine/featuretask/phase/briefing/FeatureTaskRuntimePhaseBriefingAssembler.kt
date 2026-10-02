package skillbill.engine.featuretask.phase.briefing

import skillbill.agentaddon.model.HydratedAgentAddonSelection
import skillbill.engine.featuretask.model.phase.FeatureTaskRuntimeBriefingProjectionInputs
import skillbill.engine.featuretask.model.phase.FeatureTaskRuntimePhaseLaunchBriefing
import skillbill.engine.featuretask.phase.prompt.compose.FeatureTaskRuntimePhasePromptComposer
import skillbill.workflow.taskruntime.handoff.FeatureTaskRuntimeHandoffProjectionValidator
import skillbill.workflow.taskruntime.model.handoff.PhaseHandoffProjectionDeclaration
import skillbill.workflow.taskruntime.model.handoff.PhaseHandoffProjectionShape
import skillbill.workflow.taskruntime.model.handoff.assembly.FeatureTaskRuntimePhaseHandoff
import skillbill.workflow.taskruntime.model.handoff.task.FeatureTaskRuntimeHandoffProjectionBudget
import skillbill.workflow.taskruntime.model.handoff.task.FeatureTaskRuntimeHandoffPromptVisibility
import skillbill.workflow.taskruntime.model.handoff.task.FeatureTaskRuntimeHandoffSourceRef
import skillbill.workflow.taskruntime.model.handoff.task.FeatureTaskRuntimeRunInvariantPromptField
import skillbill.workflow.taskruntime.model.phase.FeatureTaskRuntimeSharedReviewEvidenceReference

object FeatureTaskRuntimeRunInvariantPromptAllowlist {
  val IDENTITY_CEREMONY_AND_POLICY: Set<FeatureTaskRuntimeRunInvariantPromptField> =
    setOf(
      FeatureTaskRuntimeRunInvariantPromptField.SPEC_REFERENCE,
      FeatureTaskRuntimeRunInvariantPromptField.FEATURE_SIZE,
      FeatureTaskRuntimeRunInvariantPromptField.CEREMONY_SCALING,
      FeatureTaskRuntimeRunInvariantPromptField.MANDATES_AND_OVERRIDES,
    )

  val ACCEPTANCE_CONTRACT_PHASES: Set<FeatureTaskRuntimeRunInvariantPromptField> =
    IDENTITY_CEREMONY_AND_POLICY + FeatureTaskRuntimeRunInvariantPromptField.ACCEPTANCE_CRITERIA

  val FINALIZATION: Set<FeatureTaskRuntimeRunInvariantPromptField> =
    IDENTITY_CEREMONY_AND_POLICY + FeatureTaskRuntimeRunInvariantPromptField.FINALIZATION_CONTEXT
}

data class FeatureTaskRuntimeBriefingScope(
  val sharedReviewEvidence: FeatureTaskRuntimeSharedReviewEvidenceReference? = null,
  val invariantFields: Set<FeatureTaskRuntimeRunInvariantPromptField> =
    FeatureTaskRuntimeRunInvariantPromptAllowlist.ACCEPTANCE_CONTRACT_PHASES,
)

object FeatureTaskRuntimePhaseBriefingAssembler {
  fun assemble(
    handoff: FeatureTaskRuntimePhaseHandoff,
    workflowId: String? = null,
    agentAddonSelection: HydratedAgentAddonSelection = HydratedAgentAddonSelection(),
    scope: FeatureTaskRuntimeBriefingScope = FeatureTaskRuntimeBriefingScope(),
  ): FeatureTaskRuntimePhaseLaunchBriefing {
    val invariantFields = scope.invariantFields
    val boundedAddonSelection =
      FeatureTaskRuntimePhasePromptComposer.budgetedAddonsFor(
        agentAddonSelection,
      )
    val promptDeclarations =
      handoff.projectionDeclarations +
        invariantDeclarations(handoff.phaseId, invariantFields) +
        boundedAddonSelection.entries.map { entry ->
          val slug = entry.persisted.slug
          PhaseHandoffProjectionDeclaration(
            consumerPhaseId = handoff.phaseId,
            sourceRef = FeatureTaskRuntimeHandoffSourceRef.AddonContentRef(slug),
            shape =
              PhaseHandoffProjectionShape(
                projectionName = "agent_addon_${slug.replace('-', '_')}",
                projectionContractId = "feature_task_runtime.agent_addon_content",
                projectionContractVersion = "0.1",
                promptVisibility = FeatureTaskRuntimeHandoffPromptVisibility.PROMPT_VISIBLE,
                budget = FeatureTaskRuntimeHandoffProjectionBudget.ADDON_CONTENT,
                declaredFieldNames = listOf(FeatureTaskRuntimeHandoffProjectionValidator.ADDON_CONTENT_FIELD),
              ),
          )
        }
    val envelope =
      FeatureTaskRuntimeHandoffProjectionValidator.validate(
        briefingProjectionInputs(
          FeatureTaskRuntimeBriefingProjectionInputs(
            handoff = handoff,
            declarations = promptDeclarations,
            workflowId = workflowId,
            sharedReviewEvidence = scope.sharedReviewEvidence,
            addonContentBySlug = boundedAddonSelection.entries.associate { it.persisted.slug to it.content },
          ),
        ),
      )
    val projectedHandoff = handoff.copy(projectionDeclarations = promptDeclarations)
    val briefingText = renderFeatureTaskRuntimePhaseBriefing(projectedHandoff, envelope, invariantFields)
    return FeatureTaskRuntimePhaseLaunchBriefing(
      phaseId = handoff.phaseId,
      specReference = handoff.runInvariants.specReference,
      featureSize = handoff.runInvariants.featureSize.name,
      acceptanceCriteria = handoff.runInvariants.acceptanceCriteria,
      mandatesAndOverrides = handoff.runInvariants.mandatesAndOverrides,
      handoffEnvelope = envelope,
      derivedContextKeys = handoff.derivedContextKeys,
      briefingText = briefingText,
      drivingVerdict = handoff.drivingVerdict?.wireValue,
    )
  }

  private fun invariantDeclarations(
    phaseId: String,
    invariantFields: Set<FeatureTaskRuntimeRunInvariantPromptField>,
  ): List<PhaseHandoffProjectionDeclaration> =
    invariantFields.map { field ->
      val source =
        if (field == FeatureTaskRuntimeRunInvariantPromptField.CEREMONY_SCALING) {
          FeatureTaskRuntimeHandoffSourceRef.DerivedCeremonyScaling
        } else {
          FeatureTaskRuntimeHandoffSourceRef.RunInvariantField(field)
        }
      val projectedField =
        if (field == FeatureTaskRuntimeRunInvariantPromptField.CEREMONY_SCALING) {
          FeatureTaskRuntimeHandoffProjectionValidator.CEREMONY_SCALING_FIELD
        } else {
          field.wireValue
        }
      PhaseHandoffProjectionDeclaration(
        consumerPhaseId = phaseId,
        sourceRef = source,
        shape =
          PhaseHandoffProjectionShape(
            projectionName = "run_invariant_${field.wireValue}",
            projectionContractId = "feature_task_runtime.run_invariant",
            projectionContractVersion = "0.1",
            promptVisibility = FeatureTaskRuntimeHandoffPromptVisibility.PROMPT_VISIBLE,
            budget = FeatureTaskRuntimeHandoffProjectionBudget.PHASE_RECEIPT,
            declaredFieldNames = listOf(projectedField),
          ),
      )
    }
}
