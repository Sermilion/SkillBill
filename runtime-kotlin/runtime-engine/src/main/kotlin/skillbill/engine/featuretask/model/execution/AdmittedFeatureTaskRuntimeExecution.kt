package skillbill.engine.featuretask.model.execution

import skillbill.contracts.JsonCodec
import skillbill.engine.migration.RuntimeMigrationReceipt
import skillbill.ports.featuretask.model.FeatureTaskRuntimeWorkerOwnership
import skillbill.review.context.model.execution.CodeReviewExecutionMode
import skillbill.workflow.model.FeatureTaskExecutionIdentity
import skillbill.workflow.taskruntime.model.skeleton.ResolvedPhaseExecutionPlan

public class AdmittedFeatureTaskRuntimeExecution internal constructor(
  public val identity: FeatureTaskExecutionIdentity,
  public val plan: ResolvedPhaseExecutionPlan,
  internal val effectiveInputs: EffectiveGatePolicyInputs,
  descriptor: Any,
  public val recordedMonitorSemantics: RecordedMonitorExecutionSemantics,
  internal val migrationReceipt: RuntimeMigrationReceipt,
  public val workerOwnership: FeatureTaskRuntimeWorkerOwnership? = null,
) {
  private val descriptorValue = descriptor

  internal val reviewMode: CodeReviewExecutionMode?
    get() = plan.reviewSelection?.let { CodeReviewExecutionMode.valueOf(it.name) }

  internal val descriptorJson = JsonCodec.valueToJsonString(descriptor)

  internal fun withWorkerOwnership(ownership: FeatureTaskRuntimeWorkerOwnership) =
    AdmittedFeatureTaskRuntimeExecution(
      identity,
      plan,
      effectiveInputs,
      descriptorValue,
      recordedMonitorSemantics,
      migrationReceipt,
      ownership,
    )
}
