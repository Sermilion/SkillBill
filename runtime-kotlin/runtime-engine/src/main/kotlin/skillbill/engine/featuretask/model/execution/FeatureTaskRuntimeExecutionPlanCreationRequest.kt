package skillbill.engine.featuretask.model.execution

import skillbill.review.context.model.execution.CodeReviewExecutionMode
import skillbill.workflow.model.ValidationDepth
import skillbill.workflow.taskruntime.model.skeleton.FeatureTaskRuntimeQualityGateSelection
import skillbill.workflow.taskruntime.model.skeleton.SkeletonDefinition
import java.nio.file.Path
import kotlin.time.Duration

data class FeatureTaskRuntimeExecutionPlanCreationRequest(
  val repoRoot: Path,
  val definition: SkeletonDefinition,
  val reviewMode: CodeReviewExecutionMode,
  val qualityGate: FeatureTaskRuntimeQualityGateSelection?,
  val validationDepth: ValidationDepth,
  val timeout: Duration?,
  val workflowId: String? = null,
)
