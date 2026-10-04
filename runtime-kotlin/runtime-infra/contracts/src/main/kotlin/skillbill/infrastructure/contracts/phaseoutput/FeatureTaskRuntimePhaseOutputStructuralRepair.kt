package skillbill.infrastructure.contracts.phaseoutput

import skillbill.error.featuretask.FeatureTaskRuntimePhaseOutputFailureCode

internal object FeatureTaskRuntimePhaseOutputStructuralRepair {
  internal fun inspectWholeDocument(
    text: String,
    sourceLabel: String,
  ): FeatureTaskRuntimePhaseOutputStructuralRepairDecision {
    if (text.isBlank()) {
      return StructuralRepairDecisions.reject(
        FeatureTaskRuntimePhaseOutputFailureCode.MALFORMED,
        "Phase output is empty and cannot be parsed as one object.",
      )
    }
    return when (val exact = StrictPhaseOutputParser.parseDocument(text)) {
      is StrictParse.Success ->
        if (exact.node.isObject) {
          StructuralRepairDecisions.accepted(text, exact.node, null)
        } else {
          StructuralRepairDecisions.reject(
            FeatureTaskRuntimePhaseOutputFailureCode.ROOT_NOT_OBJECT,
            "<root> must be an object.",
          )
        }
      is StrictParse.Failure ->
        if (exact.code == FeatureTaskRuntimePhaseOutputFailureCode.DUPLICATE_KEY) {
          StructuralRepairDecisions.reject(
            exact.code,
            "Phase output contains a duplicate key; duplicate keys are never repaired.",
          )
        } else {
          StructuralRepairCandidateEngine.repairExactText(text, sourceLabel)
            ?: StructuralRepairDecisions.reject(exact.code, exact.reason)
        }
    }
  }
}
