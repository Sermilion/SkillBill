package skillbill.engine.featuretask.phaserun

import me.tatarka.inject.annotations.Inject
import skillbill.agentaddon.model.HydratedAgentAddonSelection
import skillbill.application.review.model.ParallelCodeReviewResult
import skillbill.config.model.CompactionSettings
import skillbill.engine.featuretask.model.core.FeatureTaskRuntimeAgentAssignment
import skillbill.engine.featuretask.model.core.FeatureTaskRuntimeGoalContinuationContext
import skillbill.engine.featuretask.model.core.FeatureTaskRuntimeModelAssignment
import skillbill.engine.featuretask.model.core.FeatureTaskRuntimeRunEventSink
import skillbill.engine.featuretask.model.core.FeatureTaskRuntimeRunFacts
import skillbill.engine.featuretask.model.core.PhaseInstructions
import skillbill.engine.featuretask.model.execution.MonitorExecutionContext
import skillbill.engine.featuretask.model.execution.QaExecutionContext
import skillbill.engine.featuretask.model.review.ReviewInvocation
import skillbill.engine.featuretask.phaserun.monitor.MonitorPreparation
import skillbill.engine.featuretask.phaserun.monitor.MonitorPreparationRequest
import skillbill.engine.featuretask.phaserun.monitor.StandaloneMonitorExecutionPreparer
import skillbill.engine.featuretask.phaserun.qa.QaStandalonePhaseRun
import skillbill.goalrunner.model.history.GoalHistoryWriterInput
import skillbill.goalrunner.model.quality.GoalQualityPhaseProgress
import skillbill.ports.agentrun.model.AgentRunActivityStampSink
import skillbill.ports.featuretask.model.FeatureTaskQaOutcome
import skillbill.ports.idestatus.model.StandaloneQaOutcome
import skillbill.review.context.model.execution.CodeReviewExecutionMode
import skillbill.workflow.decomposition.model.SpecSource
import skillbill.workflow.taskruntime.model.handoff.task.FeatureTaskRuntimeRunInvariants
import skillbill.workflow.taskruntime.model.phase.FeatureTaskRuntimeTransitionDeclaration
import skillbill.workflow.taskruntime.model.skeleton.FeatureTaskRuntimeQaSelection
import skillbill.workflow.taskruntime.model.skeleton.ResolvedPhaseExecutionPlan
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
  val modelAssignment: FeatureTaskRuntimeModelAssignment = FeatureTaskRuntimeModelAssignment(),
  val eventSink: FeatureTaskRuntimeRunEventSink = FeatureTaskRuntimeRunEventSink.NONE,
  val activityOutput: ((String, String) -> Unit)? = null,
  val activityStampSink: AgentRunActivityStampSink = AgentRunActivityStampSink.NONE,
  val launchArguments: List<String>? = null,
  val recoverStaleOwner: String? = null,
  val qaExecution: QaExecutionContext? = null,
  val qaSelection: FeatureTaskRuntimeQaSelection? = null,
  val acceptanceCriteria: List<String>? = null,
  val recordedGoalQaOutcome: FeatureTaskQaOutcome? = null,
  val goalHistoryWriterInput: GoalHistoryWriterInput? = null,
  val acceptedGoalRepairOutput: ((String, String, Int, String) -> Unit)? = null,
  val goalQualityPhaseProgress: GoalQualityPhaseProgress? = null,
  val persistGoalQualityPhaseProgress: ((GoalQualityPhaseProgress) -> Boolean)? = null,
  val latestGoalQualityPhaseProgress: (() -> GoalQualityPhaseProgress)? = null,
  val acceptedGoalQualityExecutionPlan: ResolvedPhaseExecutionPlan? = null,
  val goalQualityInvocationId: String? = null,
  val acceptedGoalQualityPlanText: String? = null,
) {
  init {
    require(definitionId.isNotBlank()) { "PhaseRunRequest.definitionId is required." }
    require(invokedAgentId.isNotBlank()) { "PhaseRunRequest.invokedAgentId is required." }
    require(recoverStaleOwner == null || recoverStaleOwner.isNotBlank()) {
      "PhaseRunRequest.recoverStaleOwner must not be blank."
    }
  }
}

sealed interface PhaseRunResult {
  val invocationId: String
  val completedStepIds: List<String>
  val reviewResult: ParallelCodeReviewResult?
  val targetReport: List<String>
  val identifiedCompletedOutputs: List<PhaseRunCompletedOutput>

  data class Completed(
    override val invocationId: String,
    override val completedStepIds: List<String>,
    override val reviewResult: ParallelCodeReviewResult?,
    val value: String?,
    val specBundle: PhaseRunSpecBundle? = null,
    val completedOutputs: List<String> = emptyList(),
    val acceptedOutput: String? = null,
    override val targetReport: List<String> = emptyList(),
    val qaOutcome: StandaloneQaOutcome? = null,
    val qaReportText: String? = null,
    override val identifiedCompletedOutputs: List<PhaseRunCompletedOutput> = emptyList(),
  ) : PhaseRunResult

  data class Blocked(
    override val invocationId: String,
    override val completedStepIds: List<String>,
    override val reviewResult: ParallelCodeReviewResult?,
    val stepId: String,
    val reason: String,
    val completedOutputs: List<String> = emptyList(),
    override val targetReport: List<String> = emptyList(),
    override val identifiedCompletedOutputs: List<PhaseRunCompletedOutput> = emptyList(),
  ) : PhaseRunResult
}

data class PhaseRunCompletedOutput(
  val phaseId: String,
  val occurrence: Int,
  val output: String,
)

@Inject
class StandalonePhaseRunners(
  private val monitor: StandaloneMonitorExecutionPreparer,
  private val qa: QaStandalonePhaseRun,
) {
  internal fun prepareMonitor(request: MonitorPreparationRequest): MonitorPreparation = monitor.prepare(request)

  fun runQa(
    request: PhaseRunRequest,
    invocationId: String,
    launch: (PhaseRunRequest) -> PhaseRunResult,
  ): PhaseRunResult = qa.run(request, invocationId, launch)
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
  override val workflowId: String = "",
  override val monitorExecution: MonitorExecutionContext? = null,
) : FeatureTaskRuntimeRunFacts {
  override val issueKey: String = intake.issueKey
  override val qaSelection: FeatureTaskRuntimeQaSelection? = request.qaSelection
  override val runInvariants: FeatureTaskRuntimeRunInvariants =
    request.acceptanceCriteria?.let { intake.runInvariants.copy(acceptanceCriteria = it) } ?: intake.runInvariants
  override val invokedAgentId: String = request.invokedAgentId
  override val agentAssignment: FeatureTaskRuntimeAgentAssignment = FeatureTaskRuntimeAgentAssignment()
  override val modelAssignment: FeatureTaskRuntimeModelAssignment = request.modelAssignment
  override val compactionSettings: CompactionSettings = CompactionSettings.DEFAULT
  override val environment: Map<String, String> = emptyMap()
  override val repoRoot: Path = request.repoRoot
  override val timeout: Duration? = request.timeout
  override val requestedCodeReviewMode: CodeReviewExecutionMode? = request.codeReviewMode
  override val goalContinuation: FeatureTaskRuntimeGoalContinuationContext? = null
  override val agentAddonSelection: HydratedAgentAddonSelection = request.agentAddonSelection
  override val eventSink: FeatureTaskRuntimeRunEventSink = request.eventSink
  override val transitionsOverride: FeatureTaskRuntimeTransitionDeclaration? = null
  override val skeletonDefinition: SkeletonDefinition = definition
  override val reviewInvocation: ReviewInvocation = request.reviewInvocation
  override val phaseInstructions: PhaseInstructions? = request.instructions
  override val qaExecution: QaExecutionContext? = request.qaExecution
  override val goalHistoryWriterInput = request.goalHistoryWriterInput
  override val goalQualityAutomaticRecovery: Boolean = request.goalQualityPhaseProgress != null
  override val acceptedGoalQualityExecutionPlan: ResolvedPhaseExecutionPlan? = request.acceptedGoalQualityExecutionPlan
  override val qaOutcomeWorkflowId: String = request.goalQualityInvocationId?.let { "$workflowId:$it" } ?: workflowId
}
