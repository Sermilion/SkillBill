package skillbill.engine.featuretask.slot.qa

import skillbill.engine.featuretask.slot.state.PhaseReturnLoopResume
import skillbill.workflow.taskruntime.phase.task.FeatureTaskRuntimePhaseWorkflowDefinition

internal object QaResumeRules : PhaseReturnLoopResume {
  override val completedStepReturnLoopId: String = FeatureTaskRuntimePhaseWorkflowDefinition.QA_FIX_RETURN_LOOP_ID
}
