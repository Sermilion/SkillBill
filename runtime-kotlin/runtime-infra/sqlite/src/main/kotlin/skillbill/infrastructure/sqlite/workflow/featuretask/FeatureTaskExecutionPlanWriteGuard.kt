package skillbill.infrastructure.sqlite.workflow.featuretask

import skillbill.error.featuretask.FeatureTaskRuntimeExecutionPlanConflictError
import skillbill.ports.workflow.model.WorkflowStateRecord
import skillbill.ports.workflow.model.toSnapshot
import skillbill.workflow.engine.model.DurableWorkflowArtifactFamily

internal fun requireUnchangedExecutionPlan(
  existing: WorkflowStateRecord?,
  proposed: WorkflowStateRecord,
) {
  if (existing == null) return
  val family = DurableWorkflowArtifactFamily.FEATURE_TASK_RUNTIME_EXECUTION_PLAN
  val before = existing.toSnapshot().artifacts
  val after = proposed.toSnapshot().artifacts
  if (family.contains(before) != family.contains(after) || family.value(before) != family.value(after)) {
    throw FeatureTaskRuntimeExecutionPlanConflictError()
  }
}
