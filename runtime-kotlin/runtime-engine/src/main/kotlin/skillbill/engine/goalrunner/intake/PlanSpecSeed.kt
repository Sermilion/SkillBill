package skillbill.engine.goalrunner.intake

import skillbill.workflow.taskruntime.model.core.FeatureTaskRuntimePlanSpecOrigin
import java.nio.file.Path

data class PlanSpecSeed(
  val specPath: Path,
  val specOrigin: FeatureTaskRuntimePlanSpecOrigin,
)
