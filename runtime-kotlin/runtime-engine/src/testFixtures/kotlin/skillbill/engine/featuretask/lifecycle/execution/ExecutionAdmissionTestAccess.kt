package skillbill.engine.featuretask.lifecycle.execution

import skillbill.engine.featuretask.persist.FeatureTaskRuntimeWorkflowPersistence
import skillbill.ports.taskruntime.model.ValidatedFeatureTaskRuntimeExecutionPlan

fun FeatureTaskRuntimeExecutionPlanCompatibility.requireSupportedCompositionForTest(encoded: ByteArray?) =
  requireSupportedComposition(encoded)

fun FeatureTaskRuntimeWorkflowPersistence.ensureWorkflowOpenForTest(
  workflowId: String,
  sessionId: String,
  issueKey: String? = null,
  executionPlan: ValidatedFeatureTaskRuntimeExecutionPlan? = null,
): Boolean = ensureWorkflowOpen(workflowId, sessionId, issueKey, executionPlan)
