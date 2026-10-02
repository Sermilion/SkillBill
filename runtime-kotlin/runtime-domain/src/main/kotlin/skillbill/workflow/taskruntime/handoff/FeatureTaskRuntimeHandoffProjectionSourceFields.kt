package skillbill.workflow.taskruntime.handoff

import skillbill.contracts.JsonCodec
import skillbill.workflow.model.goalreview.FeatureTaskRuntimeRepairLedger
import skillbill.workflow.taskruntime.model.handoff.PhaseHandoffProjectionDeclaration
import skillbill.workflow.taskruntime.model.handoff.assembly.FeatureTaskRuntimeHandoffProjectionInputs
import skillbill.workflow.taskruntime.model.handoff.task.FeatureTaskRuntimeHandoffProjectionField
import skillbill.workflow.taskruntime.model.handoff.task.FeatureTaskRuntimeHandoffProjectionValue
import skillbill.workflow.taskruntime.model.handoff.task.FeatureTaskRuntimeHandoffSourceRef
import skillbill.workflow.taskruntime.model.handoff.task.FeatureTaskRuntimeRunInvariantPromptField
import skillbill.workflow.taskruntime.model.handoff.task.FeatureTaskRuntimeRunInvariants
import skillbill.workflow.taskruntime.phase.task.FeatureTaskRuntimePhaseWorkflowDefinition
import skillbill.workflow.taskruntime.phase.task.FeatureTaskRuntimePhaseWorkflowQueries

internal fun upstreamPhaseOutputFields(
  inputs: FeatureTaskRuntimeHandoffProjectionInputs,
  declaration: PhaseHandoffProjectionDeclaration,
  sourceRef: FeatureTaskRuntimeHandoffSourceRef.UpstreamPhaseOutput,
): List<FeatureTaskRuntimeHandoffProjectionField>? =
  inputs.resolvedUpstream.outputsByPhaseId[sourceRef.producingPhaseId]?.let { output ->
    FeatureTaskRuntimeHandoffProjectionValueBuilder.phaseProjectionFields(inputs, declaration, output)
      ?: listOf(
        FeatureTaskRuntimeHandoffProjectionField(
          name = FeatureTaskRuntimeHandoffProjectionValidator.PHASE_OUTPUT_RECEIPT_FIELD,
          value =
            declaration.inlineAlternative?.let { kind ->
              FeatureTaskRuntimeHandoffProjectionValue.CompactReference(
                kind = kind,
                value =
                  FeatureTaskRuntimeHandoffProjectionValidator.privateEvidenceReference(
                    sourceRef.producingPhaseId,
                    output.iteration,
                  ),
              )
            } ?: FeatureTaskRuntimeHandoffProjectionValue.Text(output.output.value),
        ),
      )
  }

internal fun derivedCeremonyScalingFields(
  inputs: FeatureTaskRuntimeHandoffProjectionInputs,
): List<FeatureTaskRuntimeHandoffProjectionField> =
  listOf(
    FeatureTaskRuntimeHandoffProjectionField(
      name = FeatureTaskRuntimeHandoffProjectionValidator.CEREMONY_SCALING_FIELD,
      value =
        FeatureTaskRuntimeHandoffProjectionValue.TextList(
          FeatureTaskRuntimePhaseWorkflowQueries
            .ceremonyScaling(inputs.runInvariants.featureSize)
            .toBriefingLines(),
        ),
    ),
  )

internal fun repairLedgerProjectionFields(
  inputs: FeatureTaskRuntimeHandoffProjectionInputs,
): List<FeatureTaskRuntimeHandoffProjectionField>? =
  inputs.repairLedger
    ?.takeUnless(FeatureTaskRuntimeRepairLedger::isEmpty)
    ?.let { ledger ->
      listOf(
        FeatureTaskRuntimeHandoffProjectionField(
          name = FeatureTaskRuntimePhaseWorkflowDefinition.REPAIR_LEDGER_PROJECTION_NAME,
          value =
            FeatureTaskRuntimeHandoffProjectionValue.Text(
              JsonCodec.mapToJsonString(ledger.boundedProjection().toProjectionMap()),
            ),
        ),
      )
    }

internal fun runInvariantProjectionFields(
  runInvariants: FeatureTaskRuntimeRunInvariants,
  field: FeatureTaskRuntimeRunInvariantPromptField,
): List<FeatureTaskRuntimeHandoffProjectionField> {
  val value =
    when (field) {
      FeatureTaskRuntimeRunInvariantPromptField.SPEC_REFERENCE ->
        FeatureTaskRuntimeHandoffProjectionValue.Text(runInvariants.specReference)
      FeatureTaskRuntimeRunInvariantPromptField.FEATURE_SIZE ->
        FeatureTaskRuntimeHandoffProjectionValue.Text(runInvariants.featureSize.name)
      FeatureTaskRuntimeRunInvariantPromptField.ACCEPTANCE_CRITERIA ->
        FeatureTaskRuntimeHandoffProjectionValue.TextList(runInvariants.acceptanceCriteria)
      FeatureTaskRuntimeRunInvariantPromptField.MANDATES_AND_OVERRIDES ->
        FeatureTaskRuntimeHandoffProjectionValue.TextList(runInvariants.mandatesAndOverrides)
      FeatureTaskRuntimeRunInvariantPromptField.REVIEW_POLICY ->
        FeatureTaskRuntimeHandoffProjectionValue.Text(runInvariants.codeReviewMode.name)
      FeatureTaskRuntimeRunInvariantPromptField.AGENT_ADDONS ->
        FeatureTaskRuntimeHandoffProjectionValue.TextList(
          runInvariants.agentAddonSelection.entries.map { it.slug },
        )
      FeatureTaskRuntimeRunInvariantPromptField.CEREMONY_SCALING,
      FeatureTaskRuntimeRunInvariantPromptField.FINALIZATION_CONTEXT,
      -> FeatureTaskRuntimeHandoffProjectionValue.TextList(emptyList())
    }
  return listOf(FeatureTaskRuntimeHandoffProjectionField(name = field.wireValue, value = value))
}

internal fun addonContentProjectionFields(
  inputs: FeatureTaskRuntimeHandoffProjectionInputs,
  slug: String,
): List<FeatureTaskRuntimeHandoffProjectionField>? =
  inputs.addonContentBySlug[slug]?.let { content ->
    listOf(
      FeatureTaskRuntimeHandoffProjectionField(
        name = FeatureTaskRuntimeHandoffProjectionValidator.ADDON_CONTENT_FIELD,
        value = FeatureTaskRuntimeHandoffProjectionValue.Text(content),
      ),
    )
  }
