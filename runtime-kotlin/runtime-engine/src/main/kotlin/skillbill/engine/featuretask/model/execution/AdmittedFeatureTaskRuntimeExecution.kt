package skillbill.engine.featuretask.model.execution

import skillbill.contracts.JsonCodec
import skillbill.engine.migration.RuntimeMigrationReceipt
import skillbill.review.context.model.execution.CodeReviewExecutionMode
import skillbill.workflow.model.FeatureTaskExecutionIdentity
import skillbill.workflow.taskruntime.model.skeleton.ResolvedPhaseExecutionPlan

class AdmittedFeatureTaskRuntimeExecution internal constructor(
  val identity: FeatureTaskExecutionIdentity,
  val plan: ResolvedPhaseExecutionPlan,
  val effectiveInputs: EffectiveGatePolicyInputs,
  descriptor: Any,
  internal val migrationReceipt: RuntimeMigrationReceipt,
) {
  val reviewMode: CodeReviewExecutionMode?
    get() = plan.reviewSelection?.let { CodeReviewExecutionMode.valueOf(it.name) }

  internal val descriptorJson = JsonCodec.valueToJsonString(descriptor)
}
