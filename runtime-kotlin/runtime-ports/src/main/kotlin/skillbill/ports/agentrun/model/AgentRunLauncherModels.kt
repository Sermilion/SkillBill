
package skillbill.ports.agentrun.model

import skillbill.agentaddon.model.AgentAddonSelection
import skillbill.config.model.PhaseCompactionDirective
import skillbill.goalrunner.model.GoalRunnerLivenessState
import skillbill.goalrunner.model.GoalRunnerProcessState
import skillbill.install.model.SupportedAgent
import skillbill.ports.review.evidence.GovernedReviewEvidenceEndpointHandle
import skillbill.ports.review.evidence.ReviewEvidenceBroker
import skillbill.ports.review.model.ReviewProcessOutcome
import skillbill.ports.workflow.gitops.model.GoalSubtaskReviewBaseline
import skillbill.review.context.model.execution.CodeReviewExecutionMode
import skillbill.workflow.model.ValidationDepth
import skillbill.workflow.model.goalobservability.GoalProgressEvent
import skillbill.workflow.model.goalobservability.GoalProgressEventKind
import skillbill.workflow.model.goalobservability.GoalProgressOutcome
import skillbill.workflow.taskruntime.model.skeleton.FeatureTaskRuntimeQualityGateSelection
import java.nio.file.Path
import kotlin.time.Duration

data class SkillRunRequest(
  val issueKey: String,
  val repoRoot: Path,
  val subtaskId: Int? = null,
  val timeout: Duration? = null,
  val progressIdleTimeout: Duration? = null,
  val progressProbe: AgentRunProgressProbe = AgentRunProgressProbe.NONE,
  val declaredProgressProbe: AgentRunDeclaredProgressProbe = AgentRunDeclaredProgressProbe.NONE,
  val mcpStartupProbe: AgentRunMcpStartupProbe = AgentRunMcpStartupProbe.NONE,
  val progressEmitter: AgentRunProgressEmitter = AgentRunProgressEmitter.NONE,
  val outputSink: AgentRunOutputSink = AgentRunOutputSink.NONE,
  val promptOverride: String? = null,
  val streamProviderOutput: Boolean = false,
  val streamOutputForLiveness: Boolean = false,
  val readOnlyPhase: Boolean = false,
  val modelOverride: String? = null,
  val effortOverride: String? = null,
  val compaction: PhaseCompactionDirective? = null,
  val goalContinuation: SkillRunGoalContinuationContext? = null,
  val reviewEvidenceBroker: ReviewEvidenceBroker? = null,
  val reviewEvidenceEndpoint: GovernedReviewEvidenceEndpointHandle? = null,
  val nativeReviewWorkerName: String? = null,
  val reviewFanOut: Boolean = false,
  val spawnAuthorization: AgentRunSpawnAuthorization? = null,
  val activityStampSink: AgentRunActivityStampSink = AgentRunActivityStampSink.NONE,
  val worktreeEditObserver: AgentRunWorktreeEditObserver = AgentRunWorktreeEditObserver.NONE,
) {
  init {
    require(issueKey.isNotBlank()) { "issueKey is required." }
    promptOverride?.let { prompt -> require(prompt.isNotBlank()) { "promptOverride must be non-blank when provided." } }
    modelOverride?.let { model -> require(model.isNotBlank()) { "modelOverride must be non-blank when provided." } }
    effortOverride?.let { effort -> require(effort.isNotBlank()) { "effortOverride must be non-blank when provided." } }
    subtaskId?.let { id -> require(id > 0) { "subtaskId must be positive when provided." } }
    timeout?.let { maxWallClockTimeout ->
      require(maxWallClockTimeout.isPositive()) { "timeout must be positive when provided." }
    }
    progressIdleTimeout?.let { idleTimeout ->
      require(idleTimeout.isPositive()) { "progressIdleTimeout must be positive." }
    }
    require((reviewEvidenceBroker == null) == (reviewEvidenceEndpoint == null)) {
      "A governed review evidence transport and its bound endpoint must be supplied together."
    }
    require(nativeReviewWorkerName == null || reviewEvidenceBroker != null) {
      "A native review worker name is valid only for a governed review launch."
    }
    require(!reviewFanOut || reviewEvidenceBroker != null) {
      "A review fan-out surface is valid only for a governed review launch."
    }
  }
}

interface AgentRunSpawnAuthorization {
  fun <T> withAuthorization(spawn: () -> T): AgentRunSpawnAuthorizationResult<T>
}

sealed interface AgentRunSpawnAuthorizationResult<out T> {
  data class Authorized<T>(val value: T) : AgentRunSpawnAuthorizationResult<T>

  data class Denied(val pauseReason: String?) : AgentRunSpawnAuthorizationResult<Nothing>
}

data class SkillRunGoalContinuationContext(
  val parentIssueKey: String,
  val subtaskId: Int,
  val goalBranch: String,
  val suppressPr: Boolean,
  val specPath: String,
  val parentWorkflowId: String? = null,
  val lastResumableStep: String? = null,
  val childWorkflowId: String? = null,
  val assignedWorkflowId: String? = null,
  val codeReviewMode: CodeReviewExecutionMode = CodeReviewExecutionMode.DEFAULT,
  val validationDepth: ValidationDepth = ValidationDepth.DEFAULT,
  val qualityGateSelection: FeatureTaskRuntimeQualityGateSelection =
    FeatureTaskRuntimeQualityGateSelection.VALIDATE,
  val reviewBaseline: GoalSubtaskReviewBaseline? = null,
  val agentAddonSelection: AgentAddonSelection = AgentAddonSelection(),
) {
  init {
    require(parentIssueKey.isNotBlank()) { "parentIssueKey is required." }
    require(subtaskId > 0) { "subtaskId must be positive." }
    require(goalBranch.isNotBlank()) { "goalBranch is required." }
    require(specPath.isNotBlank()) { "specPath is required." }
    parentWorkflowId?.let { require(it.isNotBlank()) { "parentWorkflowId must be non-blank when provided." } }
    lastResumableStep?.let { require(it.isNotBlank()) { "lastResumableStep must be non-blank when provided." } }
    childWorkflowId?.let { require(it.isNotBlank()) { "childWorkflowId must be non-blank when provided." } }
    assignedWorkflowId?.let { require(it.isNotBlank()) { "assignedWorkflowId must be non-blank when provided." } }
  }
}

fun interface AgentRunProgressProbe {
  fun progressToken(): String?

  fun progressLabel(): String? = null

  companion object {
    val NONE: AgentRunProgressProbe = AgentRunProgressProbe { null }
  }
}

fun interface AgentRunDeclaredProgressProbe {
  fun latestDeclaredProgress(): AgentRunDeclaredProgressSnapshot?

  companion object {
    val NONE: AgentRunDeclaredProgressProbe = AgentRunDeclaredProgressProbe { null }
  }
}

data class AgentRunDeclaredProgressSnapshot(
  val latestEvent: GoalProgressEvent,
  val processAlive: Boolean,
)

fun interface AgentRunMcpStartupProbe {
  fun startupObserved(): Boolean

  companion object {
    val NONE: AgentRunMcpStartupProbe = AgentRunMcpStartupProbe { false }
  }
}

fun interface AgentRunProgressEmitter {
  fun emit(emission: AgentRunProgressEmission)

  companion object {
    val NONE: AgentRunProgressEmitter = AgentRunProgressEmitter { }
  }
}

data class AgentRunProgressEmission(
  val eventKind: GoalProgressEventKind,
  val processAlive: Boolean,
  val operationName: String,
  val operationKind: String,
  val expectedLong: Boolean = true,
  val outcome: GoalProgressOutcome = GoalProgressOutcome.NONE,
  val authoritative: Boolean = false,
)

enum class AgentRunOutputStream {
  STDOUT,
  STDERR,
}

fun interface AgentRunOutputSink {
  fun write(
    stream: AgentRunOutputStream,
    text: String,
  )

  companion object {
    val NONE: AgentRunOutputSink = AgentRunOutputSink { _, _ -> }
  }
}

data class AgentRunLaunchRequest(
  val agentId: String,
  val skillRunRequest: SkillRunRequest,
) {
  init {
    require(agentId.isNotBlank()) { "agentId is required." }
  }
}

sealed interface AgentRunLaunchOutcome {
  val agent: SupportedAgent
}

data class AgentRunLivenessSnapshot(
  val phase: String,
  val reason: String,
  val processState: GoalRunnerProcessState,
  val workflowId: String? = null,
  val workflowStep: String? = null,
  val lastDurableProgressAt: String? = null,
  val lastDurableProgressLabel: String? = null,
  val lastWorkflowSnapshotAt: String? = null,
  val lastFileActivityAt: String? = null,
  val lastFileActivityLabel: String? = null,
  val lastOutputAt: String? = null,
  val livenessState: GoalRunnerLivenessState? = null,
  val activeOperationName: String? = null,
  val activeOperationKind: String? = null,
  val activeOperationExpectedLong: Boolean = false,
  val operationDeadline: String? = null,
)

/**
 * How an agent process reached its end. A launch either exited with a status or ended without one
 * for exactly one reason, so the status and the reason can never contradict each other.
 */
sealed interface AgentRunTermination {
  data class Exited(val code: Int) : AgentRunTermination

  data object TimedOut : AgentRunTermination

  data object Interrupted : AgentRunTermination

  data object SpawnFailed : AgentRunTermination
}

val AgentRunTermination.exitCode: Int?
  get() =
    when (this) {
      is AgentRunTermination.Exited -> code
      AgentRunTermination.TimedOut, AgentRunTermination.Interrupted, AgentRunTermination.SpawnFailed -> null
    }

data class AgentRunLaunchFacts(
  override val agent: SupportedAgent,
  val termination: AgentRunTermination,
  val stdout: String,
  val stderr: String,
  val stdoutByteSize: Long,
  val stdoutSha256: String,
  val liveness: AgentRunLivenessSnapshot? = null,
  val processStarted: Boolean = termination != AgentRunTermination.SpawnFailed,
  val mcpStartupObserved: Boolean = false,
  val childSessionPath: String? = null,
  val childSessionId: String? = null,
  val assistantEventCount: Int? = null,
  val rawOutputPreview: String? = null,
  val stdoutTruncated: Boolean = false,
) : AgentRunLaunchOutcome {
  init {
    assistantEventCount?.let { count -> require(count >= 0) { "assistantEventCount cannot be negative." } }
  }
}

fun AgentRunLaunchFacts.reviewProcessOutcome(): ReviewProcessOutcome =
  when (termination) {
    AgentRunTermination.TimedOut -> ReviewProcessOutcome.TIMED_OUT
    AgentRunTermination.Interrupted -> ReviewProcessOutcome.INTERRUPTED
    AgentRunTermination.SpawnFailed -> ReviewProcessOutcome.UNAVAILABLE
    is AgentRunTermination.Exited ->
      when {
        stdoutTruncated -> ReviewProcessOutcome.INVALID_OUTPUT
        termination.code != 0 -> ReviewProcessOutcome.NON_ZERO_EXIT
        else -> ReviewProcessOutcome.ZERO_EXIT
      }
  }

data class AgentRunLaunchDenied(
  override val agent: SupportedAgent,
  val pauseReason: String?,
) : AgentRunLaunchOutcome

data class UnsupportedAgentRunLaunch(
  override val agent: SupportedAgent,
  val reason: String,
) : AgentRunLaunchOutcome {
  init {
    require(reason.isNotBlank()) { "Unsupported-agent reason is required." }
  }
}
