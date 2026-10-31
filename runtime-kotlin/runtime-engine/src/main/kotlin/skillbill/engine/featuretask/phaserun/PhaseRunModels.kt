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
import skillbill.engine.featuretask.model.execution.AdmittedFeatureTaskRuntimeExecution
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

public data class PhaseRunRequest(
  public val definitionId: String,
  public val repoRoot: Path,
  public val invokedAgentId: String,
  public val intake: String? = null,
  public val codeReviewMode: CodeReviewExecutionMode? = null,
  public val reviewInvocation: ReviewInvocation = ReviewInvocation(),
  public val instructions: PhaseInstructions? = null,
  public val agentAddonSelection: HydratedAgentAddonSelection = HydratedAgentAddonSelection(),
  public val timeout: Duration? = null,
  public val specSource: SpecSource = SpecSource.LOCAL,
  public val modelAssignment: FeatureTaskRuntimeModelAssignment = FeatureTaskRuntimeModelAssignment(),
  public val eventSink: FeatureTaskRuntimeRunEventSink = FeatureTaskRuntimeRunEventSink.NONE,
  public val activityOutput: ((String, String) -> Unit)? = null,
  public val activityStampSink: AgentRunActivityStampSink = AgentRunActivityStampSink.NONE,
  public val launchArguments: List<String>? = null,
  public val recoverStaleOwner: String? = null,
  public val qaExecution: QaExecutionContext? = null,
  public val qaSelection: FeatureTaskRuntimeQaSelection? = null,
  public val acceptanceCriteria: List<String>? = null,
  public val recordedGoalQaOutcome: FeatureTaskQaOutcome? = null,
  public val goalHistoryWriterInput: GoalHistoryWriterInput? = null,
  internal val acceptedGoalRepairOutput: ((String, String, Int, String) -> Unit)? = null,
  public val goalQualityPhaseProgress: GoalQualityPhaseProgress? = null,
  public val persistGoalQualityPhaseProgress: ((GoalQualityPhaseProgress) -> Boolean)? = null,
  internal val latestGoalQualityPhaseProgress: (() -> GoalQualityPhaseProgress)? = null,
  internal val acceptedGoalQualityExecutionPlan: ResolvedPhaseExecutionPlan? = null,
  internal val goalQualityInvocationId: String? = null,
  internal val acceptedGoalQualityPlanText: String? = null,
) {
  init {
    require(definitionId.isNotBlank()) { "PhaseRunRequest.definitionId is required." }
    require(invokedAgentId.isNotBlank()) { "PhaseRunRequest.invokedAgentId is required." }
    require(recoverStaleOwner == null || recoverStaleOwner.isNotBlank()) {
      "PhaseRunRequest.recoverStaleOwner must not be blank."
    }
  }
}

public sealed interface PhaseRunResult {
  public val invocationId: String
  public val completedStepIds: List<String>
  public val reviewResult: ParallelCodeReviewResult?
  public val targetReport: List<String>
  public val identifiedCompletedOutputs: List<PhaseRunCompletedOutput>

  public data class Completed(
    public override val invocationId: String,
    public override val completedStepIds: List<String>,
    public override val reviewResult: ParallelCodeReviewResult?,
    public val value: String?,
    public val specBundle: PhaseRunSpecBundle? = null,
    public val completedOutputs: List<String> = emptyList(),
    public val acceptedOutput: String? = null,
    public override val targetReport: List<String> = emptyList(),
    public val qaOutcome: StandaloneQaOutcome? = null,
    public val qaReportText: String? = null,
    public override val identifiedCompletedOutputs: List<PhaseRunCompletedOutput> = emptyList(),
  ) : PhaseRunResult

  public data class Blocked(
    public override val invocationId: String,
    public override val completedStepIds: List<String>,
    public override val reviewResult: ParallelCodeReviewResult?,
    public val stepId: String,
    public val reason: String,
    public val completedOutputs: List<String> = emptyList(),
    public override val targetReport: List<String> = emptyList(),
    public override val identifiedCompletedOutputs: List<PhaseRunCompletedOutput> = emptyList(),
  ) : PhaseRunResult
}

public data class PhaseRunCompletedOutput(
  val phaseId: String,
  val occurrence: Int,
  val output: String,
)

@Inject
public class StandalonePhaseRunners(
  private val monitor: StandaloneMonitorExecutionPreparer,
  private val qa: QaStandalonePhaseRun,
) {
  internal fun prepareMonitor(request: MonitorPreparationRequest): MonitorPreparation = monitor.prepare(request)

  internal fun runQa(
    request: PhaseRunRequest,
    invocationId: String,
    launch: (PhaseRunRequest) -> PhaseRunResult,
  ): PhaseRunResult = qa.run(request, invocationId, launch)
}

public data class PhaseRunSpecBundle(
  public val parentSpecPath: String,
  public val decompositionManifestPath: String,
  public val subtaskSpecPaths: List<String>,
)

public data class InMemoryPhaseRunFacts(
  val request: PhaseRunRequest,
  val definition: SkeletonDefinition,
  val intake: PhaseRunIntake,
  override val workflowId: String = "",
  override val monitorExecution: MonitorExecutionContext? = null,
) : FeatureTaskRuntimeRunFacts {
  override val admittedExecution: AdmittedFeatureTaskRuntimeExecution? = null
  override val activityParentWorkflowId: String? = null
  override val protectedSpecSha256: String? = null
  override val planIntake: String? = null
  override val stopAfterIntake: Boolean = false
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
  override val goalHistoryWriterInput: GoalHistoryWriterInput? = request.goalHistoryWriterInput
  override val goalQualityAutomaticRecovery: Boolean = request.goalQualityPhaseProgress != null
  override val acceptedGoalQualityExecutionPlan: ResolvedPhaseExecutionPlan? = request.acceptedGoalQualityExecutionPlan
  override val qaOutcomeWorkflowId: String = request.goalQualityInvocationId?.let { "$workflowId:$it" } ?: workflowId
}
