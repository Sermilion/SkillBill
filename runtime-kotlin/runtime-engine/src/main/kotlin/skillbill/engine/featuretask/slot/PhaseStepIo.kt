package skillbill.engine.featuretask.slot

import skillbill.config.model.PhaseCompactionDirective
import skillbill.engine.featuretask.slot.state.PhaseSettledEnvelopeRead
import skillbill.ports.agentrun.model.AgentRunLaunchOutcome
import skillbill.ports.agentrun.model.AgentRunOutputSink
import skillbill.ports.agentrun.model.AgentRunSpawnAuthorization
import skillbill.ports.agentrun.model.AgentRunTermination
import skillbill.workflow.taskruntime.model.skeleton.PhaseStepPolicy
import java.nio.file.Path
import kotlin.time.Duration

public data class PhaseStepInput(
  val stepName: String,
  val directive: String,
  val priorValues: Map<String, String>,
  val operatorInstructions: String?,
  val facts: PhaseStepFacts,
  val policy: PhaseStepPolicy,
)

public data class PhaseStepFacts(
  val issueKey: String,
  val repoRoot: Path,
  val timeout: Duration?,
  val invokedAgentId: String,
  val configuredAgentOverrideId: String?,
  val modelOverride: String?,
  val effortOverride: String?,
  val compaction: PhaseCompactionDirective?,
  val attempt: Int?,
  val observeLaunch: Boolean,
  val briefingText: String,
  val subtaskId: Int? = null,
  val progressIdleTimeout: Duration? = null,
  val outputSink: AgentRunOutputSink = AgentRunOutputSink.NONE,
  val streamOutputForLiveness: Boolean = false,
  val spawnAuthorization: AgentRunSpawnAuthorization? = null,
  val additionalWritableDirectories: List<Path> = emptyList(),
  val agentWorkingDirectory: Path? = null,
)

public data class PhaseStepOutput(
  val stdout: PhaseStepStdout,
  val stderr: String,
  val processStarted: Boolean,
  val termination: AgentRunTermination?,
  val fileManifest: PhaseStepFileManifest?,
  val settledEnvelope: PhaseSettledEnvelopeRead,
  val launchFailure: PhaseLaunchFailure?,
  val launchOutcome: AgentRunLaunchOutcome? = null,
)

public data class PhaseStepFileManifest(
  val before: List<String>,
  val after: List<String>,
  val reeditedPaths: List<String> = emptyList(),
)

public data class PhaseStepStdout(
  val text: String,
  val bytes: ByteArray,
  val truncated: Boolean,
  val byteSize: Long,
  val sha256: String,
)

public data class PhaseLaunchFailure(
  val kind: PhaseLaunchFailureKind,
  val reason: String,
  val cause: String = reason,
)

public enum class PhaseLaunchFailureKind {
  UNSUPPORTED_AGENT,
  BEFORE_CAPTURE_FAILED,
  PREPARATION_REJECTED,
  AFTER_CAPTURE_FAILED,
  INFRASTRUCTURE,
  PROVIDER_LIMIT,
}
