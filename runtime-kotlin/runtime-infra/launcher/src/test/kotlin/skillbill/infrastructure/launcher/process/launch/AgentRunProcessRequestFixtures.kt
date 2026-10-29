package skillbill.infrastructure.launcher.process.launch

import skillbill.model.EnvironmentContext
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
import java.nio.file.Files
import java.nio.file.Path
import kotlin.time.Duration

internal fun testAgentRunProcessRequest(
  command: List<String>,
  workingDirectory: Path,
  configure: TestAgentRunProcessRequestBuilder.() -> Unit = {},
): AgentRunProcessRequest = TestAgentRunProcessRequestBuilder().apply(configure).build(command, workingDirectory)

internal class TestAgentRunProcessRequestBuilder {
  var stdinText: String? = null
  var outputSink: AgentRunOutputSink = AgentRunOutputSink.NONE
  var timeout: Duration? = null
  var progressIdleTimeout: Duration? = null
  var fileActivityGraceTimeout: Duration = DEFAULT_FILE_ACTIVITY_GRACE_TIMEOUT
  var statusHeartbeatInterval: Duration = DEFAULT_STATUS_HEARTBEAT_INTERVAL
  var operationDeadline: Duration? = null
  var progressProbe: AgentRunProgressProbe = AgentRunProgressProbe.NONE
  var declaredProgressProbe: AgentRunDeclaredProgressProbe = AgentRunDeclaredProgressProbe.NONE
  var mcpStartupProbe: AgentRunMcpStartupProbe = AgentRunMcpStartupProbe.NONE
  var progressEmitter: AgentRunProgressEmitter = AgentRunProgressEmitter.NONE
  var activityProbe: AgentRunActivityProbe = AgentRunActivityProbe.NONE
  var activityStampSink: AgentRunActivityStampSink = AgentRunActivityStampSink.NONE
  var worktreeEditObserver: AgentRunWorktreeEditObserver = AgentRunWorktreeEditObserver.NONE
  var idlePolicy: AgentRunIdlePolicy = AgentRunIdlePolicy.DB_PROGRESS_ONLY
  var environment: Map<String, String> = emptyMap()
  var inheritEnvironment: Boolean = true
  var environmentPassthroughKeys: Set<String> = emptySet()
  var conversationIsolation: ReviewConversationIsolation? = null
  var reviewEvidenceBroker: ReviewEvidenceBroker? = null
  var reviewEvidenceEndpoint: GovernedReviewEvidenceEndpointHandle? = null
  var spawnAuthorization: AgentRunSpawnAuthorization? = null

  fun build(
    command: List<String>,
    workingDirectory: Path,
  ): AgentRunProcessRequest =
    AgentRunProcessRequest(
      launch =
        AgentRunProcessLaunchFields(
          command = command,
          workingDirectory = workingDirectory,
          stdinText = stdinText,
          outputSink = outputSink,
        ),
      timing =
        AgentRunProcessTimingFields(
          timeout = timeout,
          progressIdleTimeout = progressIdleTimeout,
          fileActivityGraceTimeout = fileActivityGraceTimeout,
          statusHeartbeatInterval = statusHeartbeatInterval,
          operationDeadline = operationDeadline,
        ),
      probes =
        AgentRunProcessProbeFields(
          progressProbe = progressProbe,
          declaredProgressProbe = declaredProgressProbe,
          mcpStartupProbe = mcpStartupProbe,
          progressEmitter = progressEmitter,
          activityProbe = activityProbe,
          activityStampSink = activityStampSink,
          worktreeEditObserver = worktreeEditObserver,
          idlePolicy = idlePolicy,
        ),
      environmentFields =
        AgentRunProcessEnvironmentFields(
          environment = environment,
          inheritEnvironment = inheritEnvironment,
          environmentPassthroughKeys = environmentPassthroughKeys,
        ),
      review =
        AgentRunProcessReviewFields(
          conversationIsolation = conversationIsolation,
          reviewEvidenceBroker = reviewEvidenceBroker,
          reviewEvidenceEndpoint = reviewEvidenceEndpoint,
          spawnAuthorization = spawnAuthorization,
        ),
    )
}

internal fun testAgentRunScratchRoot(
  stateDir: Path = Files.createTempDirectory("agent-run-state"),
): AgentRunScratchRoot =
  AgentRunScratchRoot(
    EnvironmentContext(environment = mapOf("SKILL_BILL_STATE_DIR" to stateDir.toString()), userHome = stateDir),
  )
