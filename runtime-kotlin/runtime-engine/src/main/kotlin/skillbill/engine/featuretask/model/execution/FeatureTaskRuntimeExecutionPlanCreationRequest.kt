package skillbill.engine.featuretask.model.execution

import skillbill.review.context.model.execution.CodeReviewExecutionMode
import skillbill.workflow.model.ValidationDepth
import skillbill.workflow.taskruntime.model.skeleton.FeatureTaskRuntimeQaSelection
import skillbill.workflow.taskruntime.model.skeleton.FeatureTaskRuntimeQualityGateSelection
import skillbill.workflow.taskruntime.model.skeleton.SkeletonDefinition
import skillbill.workflow.taskruntime.model.skeleton.StepLaunchAssignment
import java.nio.file.Path
import kotlin.time.Duration

public data class FeatureTaskRuntimeExecutionPlanCreationRequest(
  public val repoRoot: Path,
  public val definition: SkeletonDefinition,
  public val reviewMode: CodeReviewExecutionMode,
  public val qualityGate: FeatureTaskRuntimeQualityGateSelection?,
  public val validationDepth: ValidationDepth,
  public val timeout: Duration?,
  public val workflowId: String? = null,
  public val stepLaunchAssignments: Map<String, StepLaunchAssignment> = emptyMap(),
  public val qaSelection: FeatureTaskRuntimeQaSelection? = null,
)
