package skillbill.engine.featuretask.model.core

import skillbill.config.model.ExecutionMatrix
import skillbill.config.model.PhaseModelDirective

public data class FeatureTaskRuntimeModelAssignment(
  public val perPhaseDirectives: Map<String, PhaseModelDirective> = emptyMap(),
  public val matrix: ExecutionMatrix? = null,
) {
  init {
    perPhaseDirectives.forEach { (phaseId, directive) ->
      require(phaseId.isNotBlank()) {
        "FeatureTaskRuntimeModelAssignment.perPhaseDirectives must not contain a blank phase id."
      }
      require(directive.model.isNotBlank()) {
        "FeatureTaskRuntimeModelAssignment.perPhaseDirectives['$phaseId'] must not contain a blank model."
      }
    }
  }
}
