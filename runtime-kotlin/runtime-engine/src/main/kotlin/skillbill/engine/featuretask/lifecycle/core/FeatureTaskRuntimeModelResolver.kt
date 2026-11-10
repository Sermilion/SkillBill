package skillbill.engine.featuretask.lifecycle.core

import skillbill.config.model.ExecutionTier
import skillbill.config.model.PhaseModelDirective
import skillbill.engine.featuretask.model.core.FeatureTaskRuntimeModelAssignment

public object FeatureTaskRuntimeModelResolver {
  public fun reasoningTierDirective(
    resolvedAgentId: String,
    assignment: FeatureTaskRuntimeModelAssignment,
  ): PhaseModelDirective? = assignment.matrix?.directiveForTier(resolvedAgentId, ExecutionTier.REASONING)

  public fun resolve(
    phaseId: String,
    resolvedAgentId: String,
    assignment: FeatureTaskRuntimeModelAssignment,
  ): PhaseModelDirective? =
    assignment.perPhaseDirectives[phaseId]
      ?: assignment.matrix?.directiveFor(resolvedAgentId, phaseId)
}
