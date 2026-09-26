package skillbill.workflow.taskruntime.model.core

import skillbill.error.featuretask.UnknownQualityGateSelectionError

enum class FeatureTaskRuntimeQualityGateSelection(val wireValue: String, val stepId: String) {
  BUILD("build", FeatureTaskRuntimePhaseIds.BUILD),
  VALIDATE("validate", FeatureTaskRuntimePhaseIds.VALIDATE),
  ;

  val omittedStepIds: Set<String>
    get() = PhaseSlot.QUALITY_GATE.steps.toSet() - stepId

  companion object {
    fun fromWire(value: String): FeatureTaskRuntimeQualityGateSelection =
      entries.firstOrNull { it.wireValue == value }
        ?: throw UnknownQualityGateSelectionError(value, entries.map(FeatureTaskRuntimeQualityGateSelection::wireValue))
  }
}
