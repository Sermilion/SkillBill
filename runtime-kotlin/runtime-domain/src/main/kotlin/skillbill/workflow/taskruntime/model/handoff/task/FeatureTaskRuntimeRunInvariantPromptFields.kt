package skillbill.workflow.taskruntime.model.handoff.task

import skillbill.workflow.taskruntime.model.core.unrecognizedHandoffWireValue

enum class FeatureTaskRuntimeRunInvariantFieldCategory {
  IDENTITY,
  ACCEPTANCE_CONTRACT,
  POLICY,
  CEREMONY,
  REVIEW,
  ADD_ON,
  FINALIZATION,
}

enum class FeatureTaskRuntimeRunInvariantPromptField(
  val wireValue: String,
  val category: FeatureTaskRuntimeRunInvariantFieldCategory,
) {
  SPEC_REFERENCE("spec_reference", FeatureTaskRuntimeRunInvariantFieldCategory.IDENTITY),
  FEATURE_SIZE("feature_size", FeatureTaskRuntimeRunInvariantFieldCategory.IDENTITY),
  CEREMONY_SCALING("ceremony_scaling", FeatureTaskRuntimeRunInvariantFieldCategory.CEREMONY),
  ACCEPTANCE_CRITERIA("acceptance_criteria", FeatureTaskRuntimeRunInvariantFieldCategory.ACCEPTANCE_CONTRACT),
  MANDATES_AND_OVERRIDES("mandates_and_overrides", FeatureTaskRuntimeRunInvariantFieldCategory.POLICY),
  REVIEW_POLICY("review_policy", FeatureTaskRuntimeRunInvariantFieldCategory.REVIEW),
  AGENT_ADDONS("agent_addons", FeatureTaskRuntimeRunInvariantFieldCategory.ADD_ON),
  FINALIZATION_CONTEXT("finalization_context", FeatureTaskRuntimeRunInvariantFieldCategory.FINALIZATION),
  ;

  companion object {
    fun fromWire(value: String): FeatureTaskRuntimeRunInvariantPromptField =
      entries.firstOrNull { it.wireValue == value }
        ?: unrecognizedHandoffWireValue("run-invariant prompt field", value)
  }
}

enum class FeatureTaskRuntimeHandoffPromptVisibility(val wireValue: String) {
  PROMPT_VISIBLE("prompt_visible"),
  PRIVATE_EVIDENCE_ONLY("private_evidence_only"),
  ;

  companion object {
    fun fromWire(value: String): FeatureTaskRuntimeHandoffPromptVisibility =
      entries.firstOrNull { it.wireValue == value }
        ?: unrecognizedHandoffWireValue("handoff prompt visibility", value)
  }
}
