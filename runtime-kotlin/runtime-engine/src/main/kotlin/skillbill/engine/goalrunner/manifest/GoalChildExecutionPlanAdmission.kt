package skillbill.engine.goalrunner.manifest

import skillbill.engine.featuretask.lifecycle.execution.FeatureTaskRuntimeExecutionAdmission
import skillbill.engine.goalrunner.execution.core.workflowIdFor
import skillbill.engine.goalrunner.model.GoalRunnerChildExecutionPlanAdmission
import skillbill.error.featuretask.FeatureTaskRuntimeExecutionPlanConflictError
import skillbill.ports.persistence.UnitOfWork
import skillbill.ports.workflow.model.WorkflowFamily
import skillbill.workflow.decomposition.runtime.decompositionRuntime

internal fun requireCompatibleChildPlan(
  unitOfWork: UnitOfWork,
  parentWorkflowId: String,
  admission: GoalRunnerChildExecutionPlanAdmission?,
  executionAdmission: FeatureTaskRuntimeExecutionAdmission,
) {
  val parent = unitOfWork.workflowStates.get(WorkflowFamily.TASK_RUNTIME, parentWorkflowId)
  val manifest = parent?.decompositionRuntime()
  val workflowId = manifest?.workflowIdFor(manifest.currentSubtaskIntent.subtaskId)?.takeIf(String::isNotBlank)
  if (workflowId != admission?.workflowId) throw FeatureTaskRuntimeExecutionPlanConflictError()
  if (admission == null) return
  executionAdmission.requireCompatibleDescriptor(unitOfWork.workflowStates, admission.workflowId, admission.expected)
}
