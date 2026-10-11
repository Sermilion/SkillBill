package skillbill.ports.workflow.model

import skillbill.workflow.model.FeatureTaskWorkflowMode

data class FeatureTaskRuntimeWorkflowHeader(val mode: FeatureTaskWorkflowMode, val currentStepId: String)
