package skillbill.engine.featuretask.runloop.qualitygate

import skillbill.engine.featuretask.model.execution.ValidationGateCommandFamily
import skillbill.engine.featuretask.slot.state.PhaseRunRecords
import skillbill.engine.featuretask.validation.model.ValidationGateProgressStore
import skillbill.workflow.taskruntime.model.validation.FeatureTaskRuntimeValidationGateProgress

internal fun PhaseRunRecords.buildGateProgressStore(
  family: ValidationGateCommandFamily = ValidationGateCommandFamily.BUILD,
): ValidationGateProgressStore =
  object : ValidationGateProgressStore {
    override fun persist(
      workflowId: String,
      progress: FeatureTaskRuntimeValidationGateProgress,
    ) {
      when (family) {
        ValidationGateCommandFamily.BUILD -> persistBuildGateProgress(workflowId, progress)
        ValidationGateCommandFamily.VALIDATION -> persistValidationGateProgress(workflowId, progress)
      }
    }

    override fun load(workflowId: String): FeatureTaskRuntimeValidationGateProgress? =
      when (family) {
        ValidationGateCommandFamily.BUILD -> loadBuildGateProgress(workflowId)
        ValidationGateCommandFamily.VALIDATION -> loadValidationGateProgress(workflowId)
      }
  }
