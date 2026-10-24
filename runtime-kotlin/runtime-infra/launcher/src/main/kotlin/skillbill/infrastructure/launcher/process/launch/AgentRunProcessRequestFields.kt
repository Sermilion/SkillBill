package skillbill.infrastructure.launcher.process.launch

import skillbill.ports.agentrun.model.AgentRunActivityStampSink
import skillbill.ports.agentrun.model.AgentRunDeclaredProgressProbe
import skillbill.ports.agentrun.model.AgentRunMcpStartupProbe
import skillbill.ports.agentrun.model.AgentRunOutputSink
import skillbill.ports.agentrun.model.AgentRunProgressEmitter
import skillbill.ports.agentrun.model.AgentRunProgressProbe
import skillbill.ports.agentrun.model.AgentRunSpawnAuthorization
import skillbill.ports.agentrun.model.AgentRunWorktreeEditObserver
import skillbill.ports.review.evidence.GovernedReviewEvidenceEndpointHandle
import skillbill.ports.review.evidence.ReviewEvidenceBroker
import skillbill.review.context.model.launch.ReviewConversationIsolation
import java.nio.file.Path
import kotlin.time.Duration

data class AgentRunProcessLaunchFields(
  val command: List<String>,
  val workingDirectory: Path,
  val stdinText: String? = null,
  val outputSink: AgentRunOutputSink = AgentRunOutputSink.NONE,
  val continuation: ((AgentRunProcessResult) -> AgentRunProcessLaunchFields?)? = null,
)

data class AgentRunProcessTimingFields(
  val timeout: Duration? = null,
  val progressIdleTimeout: Duration? = null,
  val fileActivityGraceTimeout: Duration = DEFAULT_FILE_ACTIVITY_GRACE_TIMEOUT,
  val statusHeartbeatInterval: Duration = DEFAULT_STATUS_HEARTBEAT_INTERVAL,
  val operationDeadline: Duration? = null,
)

data class AgentRunProcessProbeFields(
  val progressProbe: AgentRunProgressProbe = AgentRunProgressProbe.NONE,
  val declaredProgressProbe: AgentRunDeclaredProgressProbe = AgentRunDeclaredProgressProbe.NONE,
  val mcpStartupProbe: AgentRunMcpStartupProbe = AgentRunMcpStartupProbe.NONE,
  val progressEmitter: AgentRunProgressEmitter = AgentRunProgressEmitter.NONE,
  val activityProbe: AgentRunActivityProbe = AgentRunActivityProbe.NONE,
  val activityStampSink: AgentRunActivityStampSink = AgentRunActivityStampSink.NONE,
  val worktreeEditObserver: AgentRunWorktreeEditObserver = AgentRunWorktreeEditObserver.NONE,
  val idlePolicy: AgentRunIdlePolicy = AgentRunIdlePolicy.DB_PROGRESS_ONLY,
)

data class AgentRunProcessEnvironmentFields(
  val environment: Map<String, String> = emptyMap(),
  val inheritEnvironment: Boolean = true,
  val environmentPassthroughKeys: Set<String> = emptySet(),
)

data class AgentRunProcessReviewFields(
  val conversationIsolation: ReviewConversationIsolation? = null,
  val reviewEvidenceBroker: ReviewEvidenceBroker? = null,
  val reviewEvidenceEndpoint: GovernedReviewEvidenceEndpointHandle? = null,
  val spawnAuthorization: AgentRunSpawnAuthorization? = null,
)

data class AgentRunProcessRequest(
  val launch: AgentRunProcessLaunchFields,
  val timing: AgentRunProcessTimingFields = AgentRunProcessTimingFields(),
  val probes: AgentRunProcessProbeFields = AgentRunProcessProbeFields(),
  val environmentFields: AgentRunProcessEnvironmentFields = AgentRunProcessEnvironmentFields(),
  val review: AgentRunProcessReviewFields = AgentRunProcessReviewFields(),
) {
  init {
    require(launch.command.isNotEmpty()) { "Agent run command is required." }
    require(launch.command.first().isNotBlank()) { "Agent run executable is required." }
    timing.timeout?.let { maxWallClockTimeout ->
      require(maxWallClockTimeout.isPositive()) { "Agent run timeout must be positive when provided." }
    }
    timing.progressIdleTimeout?.let { idleTimeout ->
      require(idleTimeout.isPositive()) { "Agent run progress idle timeout must be positive." }
    }
    require(timing.fileActivityGraceTimeout.isPositive()) {
      "Agent run file activity grace timeout must be positive."
    }
    require(timing.statusHeartbeatInterval.isPositive()) {
      "Agent run status heartbeat interval must be positive."
    }
    timing.operationDeadline?.let { deadline ->
      require(deadline.isPositive()) { "Agent run operation deadline must be positive when provided." }
    }
    require(
      review.reviewEvidenceBroker == null ||
        review.conversationIsolation == ReviewConversationIsolation.FRESH,
    ) {
      "A process review evidence transport requires fresh-context isolation."
    }
    require((review.reviewEvidenceBroker == null) == (review.reviewEvidenceEndpoint == null)) {
      "A process review evidence transport and its bound endpoint must be supplied together."
    }
  }
}
