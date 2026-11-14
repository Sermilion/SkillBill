package skillbill.engine.featuretask.slot

import skillbill.contracts.JsonCodec
import skillbill.contracts.config.projectchecks.ProjectCheckCategoryConfig
import skillbill.contracts.config.projectchecks.ProjectCheckCommand
import skillbill.contracts.config.projectchecks.ProjectCheckCommandSnapshot
import skillbill.engine.featuretask.lifecycle.execution.featureTaskRuntimeExecutionPlanCodecForTest
import skillbill.engine.featuretask.model.execution.EffectiveGatePolicyInputs
import skillbill.engine.featuretask.model.execution.ValidationGateCommandFamily
import skillbill.infrastructure.contracts.workflow.featuretask.FeatureTaskRuntimeExecutionPlanSchemaValidator
import skillbill.ports.taskruntime.model.ValidatedFeatureTaskRuntimeExecutionPlan
import skillbill.review.context.model.execution.CodeReviewExecutionMode
import skillbill.workflow.model.ValidationDepth
import skillbill.workflow.taskruntime.model.skeleton.FeatureTaskRuntimeQaSelection
import skillbill.workflow.taskruntime.model.skeleton.FeatureTaskRuntimeQualityGateSelection
import skillbill.workflow.taskruntime.model.skeleton.PhaseSlot
import skillbill.workflow.taskruntime.model.skeleton.ResolvedPhaseExecutionPlan
import skillbill.workflow.taskruntime.model.skeleton.SkeletonDefinition

fun testAdmittedProjectCheckSnapshot(
  qualityGate: FeatureTaskRuntimeQualityGateSelection?,
): ProjectCheckCommandSnapshot? {
  if (qualityGate == null) return null
  val command = ProjectCheckCommand(listOf("echo", qualityGate.wireValue), ".")
  val category = ProjectCheckCategoryConfig(listOf(command))
  return ProjectCheckCommandSnapshot(
    projectId = "test-project",
    buildCommands = category.takeIf { qualityGate == FeatureTaskRuntimeQualityGateSelection.BUILD },
    validationCommands = category.takeIf { qualityGate == FeatureTaskRuntimeQualityGateSelection.VALIDATE },
    parserMetadata = null,
    gradleWrapper = null,
  ).frozen()
}

fun ResolvedPhaseExecutionPlan.withTestProjectCheckSnapshot(): ResolvedPhaseExecutionPlan {
  val gate =
    qualityGateSelection
      ?: FeatureTaskRuntimeQualityGateSelection.VALIDATE.takeIf { PhaseSlot.QUALITY_GATE in selectedSlots }
  return withProjectCheckCommands(testAdmittedProjectCheckSnapshot(gate))
}

fun testExecutionPlan(
  definition: SkeletonDefinition = SkeletonDefinition.STANDALONE,
): ValidatedFeatureTaskRuntimeExecutionPlan {
  val validator = FeatureTaskRuntimeExecutionPlanSchemaValidator()
  val qualityGate =
    FeatureTaskRuntimeQualityGateSelection.VALIDATE.takeIf { definition.id == SkeletonDefinition.GOAL_CHILD.id }
  val plan =
    statusProjectionPhaseStrategies().executionPlan(
      PhaseStrategySelectionFacts(
        definition,
        setOfNotNull(
          CodeReviewExecutionMode.INLINE,
          FeatureTaskRuntimeQaSelection.AUTO.takeIf { PhaseSlot.QA in definition.slots },
          qualityGate,
        ),
      ),
    ).withTestProjectCheckSnapshot()
  val inputs =
    EffectiveGatePolicyInputs(
      ValidationGateCommandFamily.VALIDATION,
      null,
      null,
      null,
      ValidationDepth.FULL,
      null,
      projectCheckSnapshot = testAdmittedProjectCheckSnapshot(qualityGate),
    )
  val encoded = featureTaskRuntimeExecutionPlanCodecForTest(validator).encodeExecution(plan, inputs)
  return ValidatedFeatureTaskRuntimeExecutionPlan.read(encoded, validator)
}

val ValidatedFeatureTaskRuntimeExecutionPlan.artifactValue: Map<String, Any?>
  get() = requireNotNull(JsonCodec.anyToStringAnyMap(JsonCodec.parseValue(encoded().toString(Charsets.UTF_8))))
