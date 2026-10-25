package skillbill.engine.featuretask.slot.qualitygate

import skillbill.engine.featuretask.model.audit.AcceptanceCriterionRepairRunState
import skillbill.engine.featuretask.model.audit.ValidateProbeCriterion
import skillbill.engine.featuretask.model.audit.ValidateProbeScope
import skillbill.engine.featuretask.model.core.FeatureTaskRuntimeRunFacts
import skillbill.engine.featuretask.model.core.FeatureTaskRuntimeRunRequest
import skillbill.engine.featuretask.runner.skeletonDefinitionFor
import skillbill.error.shellcontent.invalidInteractionScenarioCatalog
import skillbill.workflow.taskruntime.model.audit.AcceptanceCriterionValidationObligation
import skillbill.workflow.taskruntime.model.skeleton.SkeletonDefinition

internal object ValidateProbeActivation {
  fun applies(
    request: FeatureTaskRuntimeRunFacts,
    validationGateTriage: Boolean,
  ): Boolean = applies(skeletonDefinitionFor(request), validationGateTriage)

  fun applies(
    definition: SkeletonDefinition,
    validationGateTriage: Boolean,
  ): Boolean = !validationGateTriage && definition.id == SkeletonDefinition.GOAL_CHILD.id

  fun scopeFor(
    request: FeatureTaskRuntimeRunFacts,
    validationGateTriage: Boolean,
  ): ValidateProbeScope? =
    if (applies(request, validationGateTriage)) {
      scopeOf((request as? FeatureTaskRuntimeRunRequest)?.acceptanceCriterionRepair)
    } else {
      null
    }

  private fun scopeOf(repair: AcceptanceCriterionRepairRunState?): ValidateProbeScope {
    val state = repair?.snapshot ?: return ValidateProbeScope(emptyList(), emptyList())
    val catalog = repair.binding?.interactionScenarios.orEmpty().associateBy { it.scenarioRef }
    return ValidateProbeScope(
      repairedScenarios =
        state.interactionTrace.repairedScenarioRefs.map { ref ->
          catalog[ref] ?: throw invalidInteractionScenarioCatalog(
            ref,
            "repaired scenario is missing from the bound scenario catalog.",
          )
        },
      requiredCriteria =
        state.criteria
          .filter { it.validationObligation == AcceptanceCriterionValidationObligation.REQUIRED }
          .sortedBy { it.ordinal }
          .map { ValidateProbeCriterion(it.canonicalRef, it.originalText) },
    )
  }
}
