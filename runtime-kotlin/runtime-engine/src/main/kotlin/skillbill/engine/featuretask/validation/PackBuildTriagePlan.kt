package skillbill.engine.featuretask.validation

import skillbill.engine.featuretask.validation.model.ValidationGateTriageResult
import skillbill.workflow.taskruntime.model.handoff.assembly.FeatureTaskRuntimePhaseOutput

internal object PackBuildTriagePlan {
  internal fun extract(output: FeatureTaskRuntimePhaseOutput): ValidationGateTriageResult =
    output.output.value
      .takeIf(String::isNotBlank)
      ?.let { ValidationGateTriageResult.Captured(it) }
      ?: ValidationGateTriageResult.Empty
}
