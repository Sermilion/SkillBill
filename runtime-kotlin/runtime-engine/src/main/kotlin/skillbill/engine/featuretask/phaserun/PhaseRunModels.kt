package skillbill.engine.featuretask.phaserun

import skillbill.agentaddon.model.HydratedAgentAddonSelection
import skillbill.application.review.model.ParallelCodeReviewResult
import skillbill.config.model.CompactionSettings
import skillbill.engine.featuretask.model.core.FeatureTaskRuntimeAgentAssignment
import skillbill.engine.featuretask.model.core.FeatureTaskRuntimeGoalContinuationContext
import skillbill.engine.featuretask.model.core.FeatureTaskRuntimeModelAssignment
import skillbill.engine.featuretask.model.core.FeatureTaskRuntimeRunEventSink
import skillbill.engine.featuretask.model.core.FeatureTaskRuntimeRunFacts
import skillbill.engine.featuretask.model.core.PhaseInstructions
import skillbill.engine.featuretask.model.review.ReviewInvocation
import skillbill.review.context.model.execution.CodeReviewExecutionMode
import skillbill.workflow.decomposition.model.SpecSource
import skillbill.workflow.taskruntime.model.handoff.task.FeatureTaskRuntimeRunInvariants
import skillbill.workflow.taskruntime.model.phase.FeatureTaskRuntimeTransitionDeclaration
import skillbill.workflow.taskruntime.model.skeleton.PhaseSlot
import skillbill.workflow.taskruntime.model.skeleton.SkeletonDefinition
import java.nio.file.Path
import kotlin.time.Duration

data class PhaseRunRequest(
  val definitionId: String,
  val repoRoot: Path,
  val invokedAgentId: String,
  val intake: String? = null,
  val codeReviewMode: CodeReviewExecutionMode? = null,
  val reviewInvocation: ReviewInvocation = ReviewInvocation(),
  val instructions: PhaseInstructions? = null,
  val agentAddonSelection: HydratedAgentAddonSelection = HydratedAgentAddonSelection(),
  val timeout: Duration? = null,
  val specSource: SpecSource = SpecSource.LOCAL,
) {
  init {
    require(definitionId.isNotBlank()) { "PhaseRunRequest.definitionId is required." }
    require(invokedAgentId.isNotBlank()) { "PhaseRunRequest.invokedAgentId is required." }
  }
}

sealed interface PhaseRunResult {
  val invocationId: String
  val completedStepIds: List<String>
  val reviewResult: ParallelCodeReviewResult?

  data class Completed(
    override val invocationId: String,
    override val completedStepIds: List<String>,
    override val reviewResult: ParallelCodeReviewResult?,
    val value: String?,
    val specBundle: PhaseRunSpecBundle? = null,
  ) : PhaseRunResult

  data class Blocked(
    override val invocationId: String,
    override val completedStepIds: List<String>,
    override val reviewResult: ParallelCodeReviewResult?,
    val stepId: String,
    val reason: String,
  ) : PhaseRunResult
}

data class PhaseRunSpecBundle(
  val parentSpecPath: String,
  val decompositionManifestPath: String,
  val subtaskSpecPaths: List<String>,
)

internal data class InMemoryPhaseRunFacts(
  val request: PhaseRunRequest,
  val definition: SkeletonDefinition,
  val intake: PhaseRunIntake,
) : FeatureTaskRuntimeRunFacts {
  override val issueKey: String = intake.issueKey
  override val workflowId: String = ""
  override val runInvariants: FeatureTaskRuntimeRunInvariants = intake.runInvariants
  override val specBundleRequired: Boolean = definition.slots.last() == PhaseSlot.PLAN
  override val invokedAgentId: String = request.invokedAgentId
  override val agentAssignment: FeatureTaskRuntimeAgentAssignment = FeatureTaskRuntimeAgentAssignment()
  override val modelAssignment: FeatureTaskRuntimeModelAssignment = FeatureTaskRuntimeModelAssignment()
  override val compactionSettings: CompactionSettings = CompactionSettings.DEFAULT
  override val environment: Map<String, String> = emptyMap()
  override val repoRoot: Path = request.repoRoot
  override val timeout: Duration? = request.timeout
  override val requestedCodeReviewMode: CodeReviewExecutionMode? = request.codeReviewMode
  override val goalContinuation: FeatureTaskRuntimeGoalContinuationContext? = null
  override val agentAddonSelection: HydratedAgentAddonSelection = request.agentAddonSelection
  override val eventSink: FeatureTaskRuntimeRunEventSink = FeatureTaskRuntimeRunEventSink.NONE
  override val transitionsOverride: FeatureTaskRuntimeTransitionDeclaration? = null
  override val skeletonDefinition: SkeletonDefinition = definition
  override val reviewInvocation: ReviewInvocation = request.reviewInvocation
  override val phaseInstructions: PhaseInstructions? = request.instructions
}
