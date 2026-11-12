package skillbill.infrastructure.launcher.agentrun

import skillbill.contracts.review.GovernedReviewEvidenceContracts
import skillbill.contracts.system.AgentHomeEnvironmentKeys
import skillbill.contracts.workflow.identity.task.FeatureTaskRuntimeGoalContinuationLaunchTokens
import skillbill.infrastructure.launcher.mcp.GovernedReviewMcpConfigWriter
import skillbill.infrastructure.launcher.mcp.claudeRuntimeMcpArguments
import skillbill.infrastructure.launcher.mcp.claudeRuntimeSettlementToolArguments
import skillbill.infrastructure.launcher.mcp.codexRuntimeMcpArguments
import skillbill.infrastructure.launcher.process.codex.CODEX_OUTPUT_EXHAUSTED
import skillbill.infrastructure.launcher.process.launch.AgentRunIdlePolicy
import skillbill.infrastructure.launcher.process.launch.AgentRunProcessLaunchFields
import skillbill.infrastructure.launcher.process.launch.AgentRunProcessResult
import skillbill.infrastructure.skills.install.mcp.McpRegistrationOperations
import skillbill.install.model.AGENT_LAUNCHER_CLIS
import skillbill.install.model.AgentLauncherCli
import skillbill.install.model.SupportedAgent
import skillbill.ports.agentrun.model.AgentRunApprovalRequirement
import skillbill.ports.agentrun.model.SkillRunRequest
import skillbill.ports.diagnostics.RuntimeDiagnostics
import skillbill.ports.review.model.ReviewLaunchIsolationStrategy
import skillbill.review.context.model.launch.ReviewConversationIsolation
import java.nio.file.Path
import kotlin.io.path.invariantSeparatorsPathString
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
  val kind: AgentRunCommandKind = AgentRunCommandKind.AGENT,
  val streamsProviderEvents: Boolean = false,
  val approvalProtocol: AgentRunApprovalProtocol? = null,
  val sessionModel: String? = null,
)

internal enum class AgentRunApprovalProtocol {
  CLAUDE_HOST,
  CODEX_APP_SERVER,
  CURSOR_ACP,
  JUNIE_ACP,
}

internal enum class AgentRunCommandKind {
  AGENT,
  RUNTIME_HARNESS,
}

internal interface AgentRunCommandBuilder {
  val agent: SupportedAgent
  val outputDecoder: AgentRunOutputDecoder get() = AgentRunOutputDecoder.PLAIN
  val reviewIsolation: ReviewLaunchIsolationStrategy get() = ReviewLaunchIsolationStrategy.UNSUPPORTED
  val governedReviewLaunchCapability: GovernedReviewLaunchCapability

  /** The approval protocol this CLI can host; null means it cannot run card-required work at all. */
  val approvalProtocol: AgentRunApprovalProtocol?

  val approvalUnavailableReason: String get() =
    "Agent '${agent.id}' cannot host operator approval cards. Select a supported provider explicitly."

  /** The headless CLI this builder's command execs, resolved against PATH before every spawn. */
  val launcherCli: AgentLauncherCli get() =
    requireNotNull(AGENT_LAUNCHER_CLIS[agent]) {
      "Agent '${agent.id}' has a headless command builder but no declared launcher CLI."
    }

  fun build(request: SkillRunRequest): AgentRunCommand

  fun continuation(
    command: AgentRunCommand,
    diagnostics: RuntimeDiagnostics,
  ): ((AgentRunProcessResult) -> AgentRunProcessLaunchFields?)?
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
    AgentHomeEnvironmentKeys.CODEX_HOME,
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
        put(tokens.QA_SELECTION_ENV, context.qaSelection?.wireValue.orEmpty())
      }
  }.orEmpty()

internal class ClaudeAgentRunCommandBuilder(
  internal val providerEnvironment: Map<String, String> = System.getenv(),
  override val governedReviewLaunchCapability: GovernedReviewLaunchCapability =
    GovernedReviewLaunchCapability(
      governedOnlyTooling = true,
      mcpIsolation = true,
      configFormat = McpRegistrationOperations.configFormatFor(SupportedAgent.CLAUDE),
    ),
  private val databasePath: Path? = null,
  private val runtimeMcpBin: Path? = null,
) : AgentRunCommandBuilder {
  override val agent: SupportedAgent = SupportedAgent.CLAUDE
  override val outputDecoder: AgentRunOutputDecoder = AgentRunOutputDecoder.CLAUDE_JSON
  override val reviewIsolation: ReviewLaunchIsolationStrategy = ReviewLaunchIsolationStrategy.FRESH_PROCESS
  override val approvalProtocol: AgentRunApprovalProtocol = AgentRunApprovalProtocol.CLAUDE_HOST

  override fun continuation(
    command: AgentRunCommand,
    diagnostics: RuntimeDiagnostics,
  ): ((AgentRunProcessResult) -> AgentRunProcessLaunchFields?)? = null

  override fun build(request: SkillRunRequest): AgentRunCommand {
    requireProcessLaunch(request, reviewIsolation)
    requireGovernedReviewLaunch(request, agent, governedReviewLaunchCapability)
    val hosted = request.approval is AgentRunApprovalRequirement.Owned
    val streaming = hosted || request.streamProviderOutput || request.streamOutputForLiveness
    return goalContinuationCommand(request, agent, databasePath) ?: AgentRunCommand(
      command =
        buildList {
          add(SupportedAgent.CLAUDE.wireValue)
          add("--print")
          if (hosted) {
            add("--input-format")
            add("stream-json")
          }
          add("--output-format")
          add(if (streaming) "stream-json" else "json")
          if (streaming) add("--verbose")
          addAll(claudeModelArguments(request))
          addAll(claudeGovernedReviewArguments(request))
          addAll(claudeRuntimeMcpArguments(request, runtimeMcpBin, databasePath))
          addAll(claudePermissionArguments(request, hosted, runtimeMcpBin))
          val writableDirectories = listOf(request.repoRoot) + request.additionalWritableDirectories
          writableDirectories.forEach { directory ->
            add("--add-dir")
            add(directory.toString())
          }
          if (!hosted) addAll(claudeWriteSettingsArguments(request, writableDirectories))
        },
      workingDirectory = request.repoRoot,
      timeout = request.timeout,
      stdinText = launchPrompt(request),
      environment =
        CLAUDE_FOREGROUND_ONLY_ENVIRONMENT + goalContinuationEnvironment(request) + compactionEnvironment(request) +
          runtimeDatabaseEnvironment(request.reviewEvidenceBroker == null, databasePath),
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
      approvalProtocol = approvalProtocol.takeIf { hosted },
    )
  }
}

private fun claudeModelArguments(request: SkillRunRequest): List<String> =
  buildList {
    request.modelOverride?.let {
      add("--model")
      add(it)
    }
    request.effortOverride?.let {
      add("--effort")
      add(it)
    }
  }

private fun claudeGovernedReviewArguments(request: SkillRunRequest): List<String> =
  buildList {
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
  }

private fun claudePermissionArguments(
  request: SkillRunRequest,
  hosted: Boolean,
  runtimeMcpBin: Path?,
): List<String> =
  if (hosted) {
    listOf("--permission-prompts", "host", "--dangerously-skip-permissions") +
      claudeRuntimeSettlementToolArguments(request, runtimeMcpBin)
  } else {
    listOf("--dangerously-skip-permissions")
  }

private fun claudeWriteSettingsArguments(
  request: SkillRunRequest,
  writableDirectories: List<Path>,
): List<String> =
  if (request.reviewEvidenceEndpoint == null && !request.readOnlyPhase) {
    listOf("--settings", claudeAgentConfigWriteSettings(writableDirectories))
  } else {
    emptyList()
  }

internal fun claudeAgentConfigWriteSettings(writableDirectories: List<Path>): String {
  val rules =
    buildList {
      writableDirectories.distinct().forEach { directory ->
        val root = directory.toAbsolutePath().normalize().invariantSeparatorsPathString.trimEnd('/')
        CLAUDE_AGENT_CONFIG_FOLDERS.forEach { folder ->
          add("Edit(//${root.removePrefix("/")}/.claude/$folder/**)")
          add("Bash(mkdir -p $root/.claude/$folder*)")
          add("Bash(cp * $root/.claude/$folder/*)")
        }
      }
      CLAUDE_AGENT_CONFIG_FOLDERS.forEach { folder ->
        add("Bash(mkdir -p .claude/$folder*)")
        add("Bash(cp * .claude/$folder/*)")
      }
    }
  return structuredOutputMapper.writeValueAsString(mapOf("permissions" to mapOf("allow" to rules)))
}

private val CLAUDE_AGENT_CONFIG_FOLDERS = listOf("agents", "commands", "skills")

internal class CodexAgentRunCommandBuilder(
  override val governedReviewLaunchCapability: GovernedReviewLaunchCapability =
    GovernedReviewLaunchCapability(
      governedOnlyTooling = true,
      mcpIsolation = true,
      configFormat = McpRegistrationOperations.configFormatFor(SupportedAgent.CODEX),
    ),
  private val databasePath: Path? = null,
  private val runtimeMcpBin: Path? = null,
) : AgentRunCommandBuilder {
  override val agent: SupportedAgent = SupportedAgent.CODEX
  override val outputDecoder: AgentRunOutputDecoder = AgentRunOutputDecoder.CODEX_JSONL
  override val approvalProtocol: AgentRunApprovalProtocol = AgentRunApprovalProtocol.CODEX_APP_SERVER
  override val reviewIsolation: ReviewLaunchIsolationStrategy =
    ReviewLaunchIsolationStrategy.CODEX_NATIVE_FORK_TURNS_NONE

  override fun continuation(
    command: AgentRunCommand,
    diagnostics: RuntimeDiagnostics,
  ): ((AgentRunProcessResult) -> AgentRunProcessLaunchFields?)? =
    if (command.stdinText == null || command.approvalProtocol == AgentRunApprovalProtocol.CODEX_APP_SERVER) {
      null
    } else {
      CodexOutputTokenContinuation(command, diagnostics)::nextLaunch
    }

  override fun build(request: SkillRunRequest): AgentRunCommand {
    requireProcessLaunch(request, reviewIsolation)
    requireGovernedReviewLaunch(request, agent, governedReviewLaunchCapability)
    val continuation = goalContinuationCommand(request, agent, databasePath)
    return when {
      continuation != null -> continuation
      request.approval is AgentRunApprovalRequirement.Owned && request.reviewEvidenceBroker == null ->
        appServerCommand(
          request,
        )
      else -> execCommand(request)
    }
  }

  private fun execCommand(request: SkillRunRequest): AgentRunCommand =
    AgentRunCommand(
      command =
        buildList {
          add(SupportedAgent.CODEX.wireValue)
          add("exec")
          add("--json")
          add("--cd")
          add(request.repoRoot.toString())
          request.additionalWritableDirectories.forEach { directory ->
            add("--add-dir")
            add(directory.toString())
          }
          add("--dangerously-bypass-approvals-and-sandbox")
          if (request.reviewEvidenceBroker == null) {
            add("--config")
            add("shell_environment_policy.inherit=all")
            addAll(codexRuntimeMcpArguments(request, runtimeMcpBin, databasePath))
          } else {
            add("--skip-git-repo-check")
            add("--ignore-user-config")
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
      environment =
        goalContinuationEnvironment(request) +
          runtimeDatabaseEnvironment(request.reviewEvidenceBroker == null, databasePath),
      inheritEnvironment = request.reviewEvidenceBroker == null,
      conversationIsolation = governedReviewConversationIsolation(request),
      idlePolicy = codexLivenessPolicy(request),
      streamsProviderEvents = true,
      environmentPassthroughKeys =
        if (request.reviewEvidenceBroker != null) CODEX_PROVIDER_PASSTHROUGH_KEYS else emptySet(),
    )

  private fun appServerCommand(request: SkillRunRequest): AgentRunCommand =
    AgentRunCommand(
      command =
        listOf(agent.wireValue, "app-server", "--listen", "stdio://") +
          codexRuntimeMcpArguments(request, runtimeMcpBin, databasePath),
      workingDirectory = request.repoRoot,
      timeout = request.timeout,
      stdinText = launchPrompt(request),
      environment = goalContinuationEnvironment(request) + runtimeDatabaseEnvironment(true, databasePath),
      idlePolicy = codexLivenessPolicy(request),
      outputDecoder = AgentRunOutputDecoder.PLAIN,
      approvalProtocol = AgentRunApprovalProtocol.CODEX_APP_SERVER,
    )
}

internal val GOVERNED_REVIEW_TOOLS: List<String> =
  GovernedReviewEvidenceContracts.OPERATIONS.map { operation ->
    "mcp__${GovernedReviewEvidenceContracts.SERVER_NAME}__$operation"
  }

internal val REVIEW_FAN_OUT_TOOLS = (listOf("Agent", "Task") + GOVERNED_REVIEW_TOOLS).joinToString(",")

internal fun governedReviewToolList(fanOut: Boolean): String =
  if (fanOut) REVIEW_FAN_OUT_TOOLS else GOVERNED_REVIEW_TOOLS.joinToString(",")

internal const val CODEX_OUTPUT_TOKEN_LIMIT_MESSAGE = CODEX_OUTPUT_EXHAUSTED

private val CODEX_SESSION_ID = Regex("[0-9a-fA-F]{8}(?:-[0-9a-fA-F]{4}){3}-[0-9a-fA-F]{12}")

private class CodexOutputTokenContinuation(
  private val command: AgentRunCommand,
  private val diagnostics: RuntimeDiagnostics,
) {
  private var sessionId: String? = null

  fun nextLaunch(result: AgentRunProcessResult): AgentRunProcessLaunchFields? {
    val decoded = AgentRunOutputDecoder.CODEX_JSONL.decode(result.stdout)
    if (decoded.failureDiagnostic != CODEX_OUTPUT_TOKEN_LIMIT_MESSAGE) return null
    val observedSession = decoded.continuationSessionId?.takeIf(CODEX_SESSION_ID::matches)
    if (observedSession == null || sessionId != null && observedSession != sessionId) {
      diagnostics.warning(
        "seam=codex_output_continuation reason=max_output_tokens " +
          "outcome=session_unavailable",
      )
      return null
    }
    sessionId = observedSession
    diagnostics.warning(
      "seam=codex_output_continuation reason=max_output_tokens " +
        "outcome=continuation_requested",
    )
    return AgentRunProcessLaunchFields(
      command = command.command + listOf("resume", observedSession, "-"),
      workingDirectory = command.workingDirectory,
      stdinText =
        "Continue the same task from the current session and worktree. The " +
          "previous response reached its " +
          "output-token limit. Preserve completed edits and continue the unfinished " +
          "work. " +
          "Return the required final output only after the work is complete.",
    )
  }
}
