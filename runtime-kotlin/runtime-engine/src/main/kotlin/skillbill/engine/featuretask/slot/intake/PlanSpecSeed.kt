package skillbill.engine.featuretask.slot.intake

import skillbill.workflow.taskruntime.model.core.FeatureTaskRuntimePlanSpecOrigin
import java.nio.file.Path

public data class PlanSpecSeed(
  val specPath: Path,
  val specOrigin: FeatureTaskRuntimePlanSpecOrigin,
  val suppliedIntake: String? = null,
)
