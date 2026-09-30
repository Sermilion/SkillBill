package skillbill.engine

import skillbill.engine.featuretask.phase.record.FeatureTaskRuntimePhaseRecorder
import skillbill.ports.taskruntime.model.ValidatedFeatureTaskRuntimeExecutionPlan

internal fun FeatureTaskRuntimePhaseRecorder.openTestWorkflow(
  workflowId: String,
  sessionId: String,
  issueKey: String? = null,
): Boolean {
  val executionPlan =
    if (loadPhaseRecords(workflowId) == null) {
      val fixture = ExecutionPlanAdmissionFixture()
      ValidatedFeatureTaskRuntimeExecutionPlan.read(fixture.encoded, fixture.validator)
    } else {
      null
    }
  return ensureWorkflowOpen(workflowId, sessionId, issueKey, executionPlan)
}
