package skillbill.infrastructure.launcher.agentrun

import skillbill.contracts.review.GovernedReviewEvidenceContracts
import skillbill.contracts.workflow.identity.task.FeatureTaskRuntimeGoalContinuationLaunchTokens
import skillbill.infrastructure.launcher.mcp.GovernedReviewMcpConfigWriter
import skillbill.infrastructure.launcher.process.launch.AgentRunIdlePolicy
import skillbill.infrastructure.skills.install.mcp.McpRegistrationOperations
import skillbill.install.model.AGENT_LAUNCHER_CLIS
import skillbill.install.model.AgentLauncherCli
import skillbill.install.model.SupportedAgent
import skillbill.ports.agentrun.model.SkillRunRequest
import skillbill.ports.review.model.ReviewLaunchIsolationStrategy
import skillbill.review.context.model.launch.ReviewConversationIsolation
import java.nio.file.Path
import kotlin.time.Duration

internal data class AgentRunCommand(
  val command: List<String>,
  val workingDirectory: Path,
  val timeout: Duration?,
  val stdinText: String? = null,
  val environment: Map<String, String> = emptyMap(),
  val inheritEnvironment: Boolean = true,
  val idlePolicy: AgentRunIdlePolicy = AgentRunIdlePolicy.DB_PROGRESS_ONLY,
  val conversationIsolation: ReviewConversationIsolation? = null,
  val outputDecoder: AgentRunOutputDecoder? = null,
  val environmentPassthroughKeys: Set<String> = emptySet(),
)

internal interface AgentRunCommandBuilder {
  val agent: SupportedAgent
  val outputDecoder: AgentRunOutputDecoder get() = AgentRunOutputDecoder.PLAIN
  val reviewIsolation: ReviewLaunchIsolationStrategy get() = ReviewLaunchIsolationStrategy.UNSUPPORTED
  val governedReviewLaunchCapability: GovernedReviewLaunchCapability

  /** The headless CLI this builder's command execs, resolved against PATH before every spawn. */
  val launcherCli: AgentLauncherCli get() =
    requireNotNull(AGENT_LAUNCHER_CLIS[agent]) {
      "Agent '${agent.id}' has a headless command builder but no declared launcher CLI."
    }

  fun build(request: SkillRunRequest): AgentRunCommand
}

internal val GoalContinuationEnvironment: Map<String, String> =
  mapOf(
    FeatureTaskRuntimeGoalContinuationLaunchTokens.GOAL_CONTINUATION_ENV to "1",
  )

internal val PROXY_PASSTHROUGH_KEYS: Set<String> =
  setOf(
    "HTTP_PROXY",
    "HTTPS_PROXY",
    "NO_PROXY",
    "http_proxy",
    "https_proxy",
    "no_proxy",
  )

internal val CLAUDE_PROVIDER_PASSTHROUGH_KEYS: Set<String> =
  setOf(
    "ANTHROPIC_API_KEY",
    "ANTHROPIC_AUTH_TOKEN",
    "ANTHROPIC_BASE_URL",
    "CLAUDE_CODE_USE_BEDROCK",
    "AWS_ACCESS_KEY_ID",
    "AWS_SECRET_ACCESS_KEY",
    "AWS_SESSION_TOKEN",
    "AWS_REGION",
    "CLAUDE_CODE_USE_VERTEX",
    "ANTHROPIC_VERTEX_PROJECT_ID",
    "CLOUD_ML_REGION",
    "GOOGLE_APPLICATION_CREDENTIALS",
  ) + PROXY_PASSTHROUGH_KEYS

internal val CODEX_PROVIDER_PASSTHROUGH_KEYS: Set<String> =
  setOf(
    "OPENAI_API_KEY",
    "OPENAI_BASE_URL",
    "CODEX_HOME",
  ) + PROXY_PASSTHROUGH_KEYS

internal val JUNIE_PROVIDER_PASSTHROUGH_KEYS: Set<String> = PROXY_PASSTHROUGH_KEYS

internal val CURSOR_PROVIDER_PASSTHROUGH_KEYS: Set<String> =
  setOf(
    "CURSOR_API_KEY",
  ) + PROXY_PASSTHROUGH_KEYS

internal val CLAUDE_FOREGROUND_ONLY_ENVIRONMENT: Map<String, String> =
  mapOf("CLAUDE_CODE_DISABLE_BACKGROUND_TASKS" to "1")

internal fun compactionEnvironment(request: SkillRunRequest): Map<String, String> =
  request.compaction?.let { directive ->
    mapOf(
      "CLAUDE_CODE_AUTO_COMPACT_WINDOW" to directive.windowTokens.toString(),
      "CLAUDE_AUTOCOMPACT_PCT_OVERRIDE" to directive.triggerPct.toString(),
    )
  }.orEmpty()

internal fun goalContinuationEnvironment(request: SkillRunRequest): Map<String, String> =
  request.goalContinuation?.let { context ->
    val tokens = FeatureTaskRuntimeGoalContinuationLaunchTokens
    GoalContinuationEnvironment +
      buildMap {
        put(tokens.GOAL_PARENT_ISSUE_KEY_ENV, context.parentIssueKey)
        put(tokens.GOAL_SUBTASK_ID_ENV, context.subtaskId.toString())
        put(tokens.GOAL_BRANCH_ENV, context.goalBranch)
        put(tokens.SUPPRESS_PR_ENV, context.suppressPr.toString())
        context.parentWorkflowId?.let { put(tokens.GOAL_PARENT_WORKFLOW_ID_ENV, it) }
        context.lastResumableStep?.let { put(tokens.GOAL_LAST_RESUMABLE_STEP_ENV, it) }
        put(tokens.CODE_REVIEW_MODE_ENV, context.codeReviewMode.wireValue)
        put(tokens.VALIDATION_DEPTH_ENV, context.validationDepth.wireValue)
        put(tokens.QUALITY_GATE_SELECTION_ENV, context.qualityGateSelection.wireValue)
      }
  }.orEmpty()

internal fun resolveClaudeModelDirective(
  directive: String?,
  providerEnvironment: Map<String, String>,
): String? {
  if (directive == null) return null
  val endpoint = providerEnvironment["ANTHROPIC_BASE_URL"]
  if (endpoint != null && !isOfficialAnthropicEndpoint(endpoint) && isAnthropicModelReference(directive)) {
    return providerEnvironment["ANTHROPIC_MODEL"]?.takeIf(String::isNotBlank) ?: directive
  }
  return directive
}

internal fun isOfficialAnthropicEndpoint(baseUrl: String): Boolean = baseUrl.contains("anthropic.com")

internal val ANTHROPIC_MODEL_ALIASES = setOf("opus", "sonnet", "haiku")

internal fun isAnthropicModelReference(model: String): Boolean =
  model.startsWith("claude-") || model in ANTHROPIC_MODEL_ALIASES

internal class ClaudeAgentRunCommandBuilder(
  internal val providerEnvironment: Map<String, String> = System.getenv(),
  override val governedReviewLaunchCapability: GovernedReviewLaunchCapability =
    GovernedReviewLaunchCapability(
      governedOnlyTooling = true,
      mcpIsolation = true,
      configFormat = McpRegistrationOperations.configFormatFor(SupportedAgent.CLAUDE),
    ),
  private val databasePath: Path? = null,
) : AgentRunCommandBuilder {
  override val agent: SupportedAgent = SupportedAgent.CLAUDE
  override val outputDecoder: AgentRunOutputDecoder = AgentRunOutputDecoder.CLAUDE_JSON
  override val reviewIsolation: ReviewLaunchIsolationStrategy = ReviewLaunchIsolationStrategy.FRESH_PROCESS

  override fun build(request: SkillRunRequest): AgentRunCommand {
    requireProcessLaunch(request, reviewIsolation)
    requireGovernedReviewLaunch(request, agent, governedReviewLaunchCapability)
    val streaming = request.streamProviderOutput || request.streamOutputForLiveness
    return goalContinuationCommand(request, agent, databasePath) ?: AgentRunCommand(
      command =
        buildList {
          add(SupportedAgent.CLAUDE.wireValue)
          add("--print")
          add("--output-format")

          add(if (streaming) "stream-json" else "json")
          if (streaming) add("--verbose")
          resolveClaudeModelDirective(request.modelOverride, providerEnvironment)?.let {
            add("--model")
            add(it)
          }
          request.effortOverride?.let {
            add("--effort")
            add(it)
          }
          request.reviewEvidenceEndpoint?.let { endpoint ->
            request.nativeReviewWorkerName?.let { worker ->
              add("--agent")
              add(worker)
            }
            add("--mcp-config")
            add(endpoint.descriptor.mcpConfigPath.toString())
            add("--strict-mcp-config")
            add("--tools")
            add(governedReviewToolList(request.reviewFanOut))
          }
          add("--dangerously-skip-permissions")
          add("--add-dir")
          add(request.repoRoot.toString())
        },
      workingDirectory = request.repoRoot,
      timeout = request.timeout,
      stdinText = launchPrompt(request),
      environment =
        CLAUDE_FOREGROUND_ONLY_ENVIRONMENT + goalContinuationEnvironment(request) + compactionEnvironment(request),
      inheritEnvironment = request.reviewEvidenceBroker == null,
      conversationIsolation = governedReviewConversationIsolation(request),
      idlePolicy =
        when {
          request.streamOutputForLiveness -> AgentRunIdlePolicy.OUTPUT_EXTENDED
          request.readOnlyPhase -> AgentRunIdlePolicy.HEARTBEAT_EXTENDED
          else -> AgentRunIdlePolicy.DB_PROGRESS_ONLY
        },
      outputDecoder = AgentRunOutputDecoder.CLAUDE_STREAM_JSON.takeIf { streaming },
      environmentPassthroughKeys =
        if (request.reviewEvidenceBroker != null) CLAUDE_PROVIDER_PASSTHROUGH_KEYS else emptySet(),
    )
  }
}

internal class CodexAgentRunCommandBuilder(
  override val governedReviewLaunchCapability: GovernedReviewLaunchCapability =
    GovernedReviewLaunchCapability(
      governedOnlyTooling = true,
      mcpIsolation = true,
      configFormat = McpRegistrationOperations.configFormatFor(SupportedAgent.CODEX),
    ),
  private val databasePath: Path? = null,
) : AgentRunCommandBuilder {
  override val agent: SupportedAgent = SupportedAgent.CODEX
  override val outputDecoder: AgentRunOutputDecoder = AgentRunOutputDecoder.CODEX_JSONL
  override val reviewIsolation: ReviewLaunchIsolationStrategy =
    ReviewLaunchIsolationStrategy.CODEX_NATIVE_FORK_TURNS_NONE

  override fun build(request: SkillRunRequest): AgentRunCommand {
    requireProcessLaunch(request, reviewIsolation)
    requireGovernedReviewLaunch(request, agent, governedReviewLaunchCapability)
    return goalContinuationCommand(request, agent, databasePath) ?: AgentRunCommand(
      command =
        buildList {
          add(SupportedAgent.CODEX.wireValue)
          add("exec")
          add("--json")
          add("--cd")
          add(request.repoRoot.toString())
          if (request.reviewEvidenceBroker == null) {
            add("--dangerously-bypass-approvals-and-sandbox")
            add("--config")
            add("shell_environment_policy.inherit=all")
          } else {
            add("--skip-git-repo-check")
            add("--ignore-user-config")
            add("--sandbox")
            add("read-only")
            add("--config")
            add("shell_environment_policy.inherit=none")
            add("--config")
            add("fork_turns=none")
            add("--config")
            add("tools.web_search=false")
            add("--config")
            add("tools.shell=false")
            request.reviewEvidenceEndpoint?.let { endpoint ->
              GovernedReviewMcpConfigWriter.codexConfigOverrides(
                mcpConfigPath = endpoint.descriptor.mcpConfigPath,
                socketPath = endpoint.descriptor.socketPath,
                token = endpoint.descriptor.token,
                lane = endpoint.descriptor.lane,
              ).forEach { override ->
                add("--config")
                add(override)
              }
            }
          }
          request.modelOverride?.let {
            add("--model")
            add(it)
          }
          request.effortOverride?.let {
            add("--config")
            add("model_reasoning_effort=$it")
          }
        },
      workingDirectory = request.repoRoot,
      timeout = request.timeout,
      stdinText = launchPrompt(request),
      environment = goalContinuationEnvironment(request),
      inheritEnvironment = request.reviewEvidenceBroker == null,
      conversationIsolation = governedReviewConversationIsolation(request),
      idlePolicy = codexLivenessPolicy(request),
      environmentPassthroughKeys =
        if (request.reviewEvidenceBroker != null) CODEX_PROVIDER_PASSTHROUGH_KEYS else emptySet(),
    )
  }
}

internal val GOVERNED_REVIEW_TOOLS: List<String> =
  GovernedReviewEvidenceContracts.OPERATIONS.map { operation ->
    "mcp__${GovernedReviewEvidenceContracts.SERVER_NAME}__$operation"
  }

internal val REVIEW_FAN_OUT_TOOLS = (listOf("Agent", "Task") + GOVERNED_REVIEW_TOOLS).joinToString(",")

internal fun governedReviewToolList(fanOut: Boolean): String =
  if (fanOut) REVIEW_FAN_OUT_TOOLS else GOVERNED_REVIEW_TOOLS.joinToString(",")
