package skillbill.workflow.taskruntime.handoff

import skillbill.error.featuretask.FeatureTaskRuntimeHandoffProjectionFailureKind
import skillbill.workflow.taskruntime.model.handoff.PhaseHandoffProjectionDeclaration
import skillbill.workflow.taskruntime.model.handoff.assembly.FeatureTaskRuntimeHandoffProjectionInputs
import skillbill.workflow.taskruntime.model.handoff.task.FeatureTaskRuntimeHandoffProjectionField
import skillbill.workflow.taskruntime.model.handoff.task.FeatureTaskRuntimeHandoffSourceRef
import skillbill.workflow.taskruntime.model.handoff.task.FeatureTaskRuntimeProducerIteration

internal object FeatureTaskRuntimeHandoffProjectionFieldResolver {
  fun resolvedProducerIteration(
    inputs: FeatureTaskRuntimeHandoffProjectionInputs,
    declaration: PhaseHandoffProjectionDeclaration,
  ): FeatureTaskRuntimeProducerIteration =
    when (val source = declaration.sourceRef) {
      is FeatureTaskRuntimeHandoffSourceRef.UpstreamPhaseOutput -> {
        val output = inputs.resolvedUpstream.outputsByPhaseId[source.producingPhaseId]
        if (output == null) {
          declaration.producerIteration
        } else {
          FeatureTaskRuntimeProducerIteration(source.producingPhaseId, output.iteration)
        }
      }
      is FeatureTaskRuntimeHandoffSourceRef.RunInvariantField -> declaration.producerIteration
      FeatureTaskRuntimeHandoffSourceRef.DerivedCeremonyScaling -> declaration.producerIteration
      FeatureTaskRuntimeHandoffSourceRef.SharedReviewEvidence -> declaration.producerIteration
      FeatureTaskRuntimeHandoffSourceRef.RepairLedger -> declaration.producerIteration
      is FeatureTaskRuntimeHandoffSourceRef.AddonContentRef -> declaration.producerIteration
    }

  fun resolveFields(
    inputs: FeatureTaskRuntimeHandoffProjectionInputs,
    declaration: PhaseHandoffProjectionDeclaration,
  ): FeatureTaskRuntimeHandoffProjectionStep<List<FeatureTaskRuntimeHandoffProjectionField>?> {
    val fields =
      when (val result = fieldsFor(inputs, declaration)) {
        is FeatureTaskRuntimeHandoffProjectionStep.Rejected -> return result
        is FeatureTaskRuntimeHandoffProjectionStep.Value -> result.value
      }
    if (fields == null && declaration.required) {
      return FeatureTaskRuntimeHandoffProjectionStep.Rejected(
        rejectedFeatureTaskRuntimeHandoffProjectionContext(
          inputs,
          declaration,
          FeatureTaskRuntimeHandoffProjectionFailureKind.MISSING_REQUIRED_SOURCE,
          "declared source '${declaration.sourceRef.wireValue}' has no recorded value.",
        ),
      )
    }
    return FeatureTaskRuntimeHandoffProjectionStep.Value(fields)
  }

  private fun fieldsFor(
    inputs: FeatureTaskRuntimeHandoffProjectionInputs,
    declaration: PhaseHandoffProjectionDeclaration,
  ): FeatureTaskRuntimeHandoffProjectionStep<List<FeatureTaskRuntimeHandoffProjectionField>?> =
    when (val sourceRef = declaration.sourceRef) {
      is FeatureTaskRuntimeHandoffSourceRef.UpstreamPhaseOutput ->
        upstreamPhaseOutputFields(inputs, declaration, sourceRef)
      is FeatureTaskRuntimeHandoffSourceRef.RunInvariantField ->
        FeatureTaskRuntimeHandoffProjectionStep.Value(
          runInvariantProjectionFields(inputs.runInvariants, sourceRef.invariantField),
        )
      FeatureTaskRuntimeHandoffSourceRef.DerivedCeremonyScaling ->
        FeatureTaskRuntimeHandoffProjectionStep.Value(derivedCeremonyScalingFields(inputs))
      FeatureTaskRuntimeHandoffSourceRef.SharedReviewEvidence ->
        FeatureTaskRuntimeHandoffProjectionStep.Value(inputs.sharedReviewEvidence?.toProjectionFields())
      FeatureTaskRuntimeHandoffSourceRef.RepairLedger ->
        FeatureTaskRuntimeHandoffProjectionStep.Value(repairLedgerProjectionFields(inputs))
      is FeatureTaskRuntimeHandoffSourceRef.AddonContentRef ->
        FeatureTaskRuntimeHandoffProjectionStep.Value(addonContentProjectionFields(inputs, sourceRef.slug))
    }
}
