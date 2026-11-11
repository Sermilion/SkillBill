package skillbill.infrastructure.launcher.agentrun

import skillbill.contracts.system.AgentHomeEnvironmentKeys
import skillbill.contracts.system.OpenCodeEnvironmentKeys
import skillbill.contracts.system.OpenCodeEnvironmentSelection
import skillbill.contracts.system.OpenCodeLaunchIdentity
import skillbill.infrastructure.launcher.mcp.RUNTIME_MCP_SERVER_NAME
import skillbill.infrastructure.launcher.mcp.runtimeMcpServerEnvironment
import skillbill.infrastructure.launcher.process.cursor.AcpProvider
import skillbill.infrastructure.launcher.process.cursor.CursorAcpMcpServer
import skillbill.infrastructure.launcher.process.launch.AgentRunProcessLaunchFields
import skillbill.infrastructure.launcher.process.launch.AgentRunProcessResult
import skillbill.infrastructure.skills.install.mcp.McpRegistrationOperations
import skillbill.install.model.SupportedAgent
import skillbill.ports.agentrun.model.SkillRunRequest
import skillbill.ports.diagnostics.RuntimeDiagnostics
import skillbill.ports.review.model.ReviewLaunchIsolationStrategy
import java.nio.file.Path

internal class OpenCodeAgentRunCommandBuilder(
  private val databasePath: Path? = null,
  private val runtimeMcpBin: Path? = null,
  private val bindingEnvironment: Map<String, String> = emptyMap(),
  private val userHome: Path? = null,
) : AgentRunCommandBuilder {
  override val agent: SupportedAgent = SupportedAgent.OPENCODE
  override val outputDecoder: AgentRunOutputDecoder =
    object : AgentRunOutputDecoder {
      override fun decode(stdout: String): DecodedAgentRunOutput =
        DecodedAgentRunOutput(text = "", rawOutputPreview = stdout.take(RAW_OUTPUT_PREVIEW_MAX_CHARS))

      override fun undecodable(error: Throwable): Boolean = false
    }
  override val governedReviewLaunchCapability: GovernedReviewLaunchCapability =
    GovernedReviewLaunchCapability(
      governedOnlyTooling = false,
      mcpIsolation = false,
      configFormat = McpRegistrationOperations.configFormatFor(SupportedAgent.OPENCODE),
    )
  override val approvalProtocol: AgentRunApprovalProtocol = AgentRunApprovalProtocol.OPENCODE_ACP
  override val reviewIsolation: ReviewLaunchIsolationStrategy = ReviewLaunchIsolationStrategy.FRESH_PROCESS

  override fun continuation(
    command: AgentRunCommand,
    diagnostics: RuntimeDiagnostics,
  ): ((AgentRunProcessResult) -> AgentRunProcessLaunchFields?)? = null

  override fun build(request: SkillRunRequest): AgentRunCommand {
    requireProcessLaunch(request, reviewIsolation)
    requireGovernedReviewLaunch(request, agent, governedReviewLaunchCapability)
    val continuation = goalContinuationCommand(request, agent, databasePath)
    if (continuation != null) {
      val identity =
        openCodeEnvironment(
          request.openCodeIdentity,
          request.repoRoot,
          userHome,
          bindingEnvironment,
          preserveSnapshotSelections = true,
        )
      return continuation.copy(
        environment = bindingEnvironment + continuation.environment + identity.environment,
        removedEnvironmentKeys = identity.removedKeys,
        preflightFailure = identity.failure,
        outputDecoder = AgentRunOutputDecoder.PLAIN,
      )
    }
    val identity = openCodeEnvironment(request.openCodeIdentity, request.repoRoot, userHome, bindingEnvironment)
    val binary = runtimeMcpBin?.toAbsolutePath()?.normalize()
    return AgentRunCommand(
      command = listOf(SupportedAgent.OPENCODE.wireValue) + AcpProvider.OpenCode.acpArguments,
      workingDirectory = request.repoRoot,
      timeout = request.timeout,
      stdinText = launchPrompt(request),
      environment =
        bindingEnvironment + goalContinuationEnvironment(request) + runtimeDatabaseEnvironment(true, databasePath) +
          identity.environment,
      removedEnvironmentKeys = identity.removedKeys,
      inheritEnvironment = true,
      approvalProtocol = approvalProtocol,
      sessionModel = request.modelOverride,
      sessionEffort = request.effortOverride,
      sessionMcpServers =
        binary?.let {
          listOf(
            CursorAcpMcpServer(
              name = RUNTIME_MCP_SERVER_NAME,
              command = it.toString(),
              args = emptyList(),
              environment = runtimeMcpServerEnvironment(databasePath, request.validationCheckInvocationId),
            ),
          )
        }.orEmpty(),
      preflightFailure =
        identity.failure
          ?: if (binary == null) "The runtime MCP executable is unavailable; OpenCode was not started." else null,
      idlePolicy = unstreamedLivenessPolicy(request),
    )
  }
}

private data class OpenCodeChildEnvironment(
  val environment: Map<String, String> = emptyMap(),
  val removedKeys: Set<String> = emptySet(),
  val failure: String? = null,
)

private fun openCodeEnvironment(
  accepted: OpenCodeLaunchIdentity?,
  workingDirectory: Path,
  userHome: Path?,
  currentEnvironment: Map<String, String>,
  preserveSnapshotSelections: Boolean = false,
): OpenCodeChildEnvironment {
  accepted ?: return OpenCodeChildEnvironment()
  val effectiveHome =
    userHome ?: currentEnvironment[OpenCodeEnvironmentKeys.HOME]?.let(Path::of)
      ?: return OpenCodeChildEnvironment(
        failure =
          "The current OpenCode home cannot be verified. " +
            "Restore the accepted runtime environment and resume again.",
      )
  val current = OpenCodeLaunchIdentity.capture(currentEnvironment, effectiveHome, workingDirectory)
  val conflict = accepted.conflictsWith(current)
  val currentInline = currentEnvironment[OpenCodeEnvironmentKeys.CONFIG_CONTENT]
  val inlineMatches =
    !accepted.inlineConfigPresent ||
      (currentInline != null && current.inlineConfigFingerprint == accepted.inlineConfigFingerprint)
  val refusal =
    when {
      conflict != null ->
        "The accepted OpenCode identity conflicts at $conflict. Restore the accepted setting and resume again."
      !inlineMatches ->
        "The accepted OpenCode inline configuration is unavailable or changed. " +
          "Restore OPENCODE_CONFIG_CONTENT and resume again."
      else -> null
    }
  return if (refusal != null) {
    OpenCodeChildEnvironment(failure = refusal)
  } else {
    OpenCodeChildEnvironment(
      childValues(accepted, preserveSnapshotSelections, currentInline),
      childRemovals(accepted, preserveSnapshotSelections),
    )
  }
}

private fun identityRoots(
  identity: OpenCodeLaunchIdentity,
): List<Triple<String, OpenCodeEnvironmentSelection, String>> =
  listOf(
    Triple(OpenCodeEnvironmentKeys.HOME, identity.home, identity.effectiveHome),
    Triple(OpenCodeEnvironmentKeys.XDG_CONFIG_HOME, identity.configHome, identity.effectiveConfigHome),
    Triple(OpenCodeEnvironmentKeys.XDG_DATA_HOME, identity.dataHome, identity.effectiveDataHome),
    Triple(OpenCodeEnvironmentKeys.XDG_CACHE_HOME, identity.cacheHome, identity.effectiveCacheHome),
    Triple(OpenCodeEnvironmentKeys.XDG_STATE_HOME, identity.stateHome, identity.effectiveStateHome),
    Triple(AgentHomeEnvironmentKeys.OPENCODE_CONFIG_DIR, identity.configDirectory, identity.effectiveConfigDirectory),
  )

private fun childValues(
  accepted: OpenCodeLaunchIdentity,
  preserveSnapshotSelections: Boolean,
  currentInline: String?,
): Map<String, String> =
  buildMap {
    identityRoots(accepted).forEach { (key, selection, effective) ->
      if (!preserveSnapshotSelections || selection.supplied) {
        put(key, if (preserveSnapshotSelections) selection.value.orEmpty() else effective)
      }
    }
    if (accepted.configFile.supplied) {
      val configFile =
        if (preserveSnapshotSelections) {
          accepted.configFile.value.orEmpty()
        } else {
          accepted.effectiveConfigFile ?: accepted.configFile.value.orEmpty()
        }
      put(OpenCodeEnvironmentKeys.CONFIG_FILE, configFile)
    }
    if (accepted.disableProjectConfig.supplied) {
      put(OpenCodeEnvironmentKeys.DISABLE_PROJECT_CONFIG, accepted.disableProjectConfig.value.orEmpty())
    }
    if (accepted.inlineConfigPresent) put(OpenCodeEnvironmentKeys.CONFIG_CONTENT, requireNotNull(currentInline))
  }

private fun childRemovals(
  accepted: OpenCodeLaunchIdentity,
  preserveSnapshotSelections: Boolean,
): Set<String> =
  buildSet {
    if (!accepted.configFile.supplied) add(OpenCodeEnvironmentKeys.CONFIG_FILE)
    if (!accepted.disableProjectConfig.supplied) add(OpenCodeEnvironmentKeys.DISABLE_PROJECT_CONFIG)
    if (!accepted.inlineConfigPresent) add(OpenCodeEnvironmentKeys.CONFIG_CONTENT)
    if (preserveSnapshotSelections) {
      identityRoots(accepted).forEach { (key, selection) -> if (!selection.supplied) add(key) }
    }
  }
