package skillbill.engine.featuretask.slot

import skillbill.contracts.JsonCodec
import skillbill.engine.featuretask.lifecycle.execution.FeatureTaskRuntimeExecutionPlanCodec
import skillbill.engine.featuretask.model.execution.EffectiveGatePolicyInputs
import skillbill.engine.featuretask.model.execution.ValidationGateCommandFamily
import skillbill.infrastructure.contracts.workflow.featuretask.FeatureTaskRuntimeExecutionPlanSchemaValidator
import skillbill.ports.taskruntime.model.ValidatedFeatureTaskRuntimeExecutionPlan
import skillbill.review.context.model.execution.CodeReviewExecutionMode
import skillbill.workflow.model.ValidationDepth
import skillbill.workflow.taskruntime.model.skeleton.FeatureTaskRuntimeQualityGateSelection
import skillbill.workflow.taskruntime.model.skeleton.SkeletonDefinition

fun testExecutionPlan(
  definition: SkeletonDefinition = SkeletonDefinition.STANDALONE,
): ValidatedFeatureTaskRuntimeExecutionPlan {
  val validator = FeatureTaskRuntimeExecutionPlanSchemaValidator()
  val plan =
    statusProjectionPhaseStrategies().executionPlan(
      PhaseStrategySelectionFacts(
        definition,
        setOfNotNull(
          CodeReviewExecutionMode.INLINE,
          FeatureTaskRuntimeQualityGateSelection.VALIDATE.takeIf { definition == SkeletonDefinition.GOAL_CHILD },
        ),
      ),
    )
  val inputs =
    EffectiveGatePolicyInputs(
      ValidationGateCommandFamily.VALIDATION,
      null,
      null,
      null,
      ValidationDepth.FULL,
      null,
    )
  val encoded = FeatureTaskRuntimeExecutionPlanCodec(validator).encodeExecution(plan, inputs)
  return ValidatedFeatureTaskRuntimeExecutionPlan.read(encoded, validator)
}

val ValidatedFeatureTaskRuntimeExecutionPlan.artifactValue: Map<String, Any?>
  get() = requireNotNull(JsonCodec.anyToStringAnyMap(JsonCodec.parseValue(encoded().toString(Charsets.UTF_8))))
