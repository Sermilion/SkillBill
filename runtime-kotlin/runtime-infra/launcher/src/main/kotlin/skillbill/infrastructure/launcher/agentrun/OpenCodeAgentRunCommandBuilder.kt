package skillbill.infrastructure.launcher.agentrun

import skillbill.contracts.system.AgentHomeEnvironmentKeys
import skillbill.contracts.system.OpenCodeEnvironmentKeys
import skillbill.contracts.system.OpenCodeEnvironmentSelection
import skillbill.contracts.system.OpenCodeLaunchIdentity
import skillbill.error.core.SkillBillRuntimeException
import skillbill.error.core.failureCodeLabel
import skillbill.error.shellcontent.GovernedReviewFailureCode
import skillbill.infrastructure.launcher.mcp.OpenCodeReviewLaunchScope
import skillbill.infrastructure.launcher.mcp.OpenCodeReviewPreparation
import skillbill.infrastructure.launcher.mcp.RUNTIME_MCP_SERVER_NAME
import skillbill.infrastructure.launcher.mcp.runtimeMcpServerEnvironment
import skillbill.infrastructure.launcher.process.cursor.AcpEnvVariable
import skillbill.infrastructure.launcher.process.cursor.AcpProvider
import skillbill.infrastructure.launcher.process.cursor.AcpStdioMcpServer
import skillbill.infrastructure.launcher.process.launch.AgentRunProcessLaunchFields
import skillbill.infrastructure.launcher.process.launch.AgentRunProcessResult
import skillbill.infrastructure.launcher.process.launch.captureLaunchResourceOutcome
import skillbill.infrastructure.skills.install.mcp.McpRegistrationOperations
import skillbill.install.model.SupportedAgent
import skillbill.ports.agentrun.model.AgentRunApprovalRequirement
import skillbill.ports.agentrun.model.SkillRunRequest
import skillbill.ports.diagnostics.RuntimeDiagnostics
import skillbill.ports.review.model.ReviewLaunchIsolationStrategy
import skillbill.ports.system.HostPlatformPort
import java.io.IOException
import java.nio.file.Path
import java.util.concurrent.CancellationException

internal class OpenCodeAgentRunCommandBuilder(
  private val databasePath: Path? = null,
  private val runtimeMcpBin: Path? = null,
  private val bindingEnvironment: Map<String, String> = emptyMap(),
  private val userHome: Path? = null,
  private val hostPlatform: HostPlatformPort? = null,
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

  override fun launchRefusal(request: SkillRunRequest): String? = null

  override val reviewIsolation: ReviewLaunchIsolationStrategy = ReviewLaunchIsolationStrategy.UNSUPPORTED

  override fun continuation(
    command: AgentRunCommand,
    diagnostics: RuntimeDiagnostics,
  ): ((AgentRunProcessResult) -> AgentRunProcessLaunchFields?)? = null

  override fun build(request: SkillRunRequest): AgentRunCommand {
    if (isGovernedReviewIntent(request)) return governedReviewCommand(request)
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
        environment =
          bindingEnvironment + continuation.environment + identity.environment + AcpProvider.OpenCode.launchEnvironment,
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
          identity.environment + AcpProvider.OpenCode.launchEnvironment,
      removedEnvironmentKeys = identity.removedKeys,
      inheritEnvironment = true,
      approvalProtocol = approvalProtocol,
      sessionModel = request.modelOverride,
      sessionEffort = request.effortOverride,
      acpMcpServers =
        binary?.let {
          listOf(
            AcpStdioMcpServer(
              name = RUNTIME_MCP_SERVER_NAME,
              command = it.toString(),
              args = emptyList(),
              env =
                runtimeMcpServerEnvironment(databasePath, request.validationCheckInvocationId)
                  .map { (name, value) -> AcpEnvVariable(name, value) },
            ),
          )
        }.orEmpty(),
      preflightFailure =
        identity.failure
          ?: if (binary == null) "The runtime MCP executable is unavailable; OpenCode was not started." else null,
      idlePolicy = unstreamedLivenessPolicy(request),
    )
  }

  private fun governedReviewCommand(request: SkillRunRequest): AgentRunCommand {
    governedReviewRefusal(request)?.let { return failedGovernedReviewCommand(request, it) }
    val prompt = launchPrompt(request)
    val admitted =
      when (val prepared = prepareGovernedReview(request)) {
        is OpenCodeReviewPreparation.Ready -> prepared.scope
        is OpenCodeReviewPreparation.Refused -> return failedGovernedReviewCommand(request, prepared.reason)
      }
    return AgentRunCommand(
      command = listOf(SupportedAgent.OPENCODE.wireValue) + AcpProvider.OpenCode.acpArguments,
      workingDirectory = admitted.cwd,
      timeout = request.timeout,
      stdinText = prompt,
      environment = admitted.environment + AcpProvider.OpenCode.launchEnvironment,
      inheritEnvironment = false,
      approvalProtocol = approvalProtocol,
      sessionModel = request.modelOverride,
      sessionEffort = request.effortOverride,
      idlePolicy = unstreamedLivenessPolicy(request),
      conversationIsolation = governedReviewConversationIsolation(request),
      launchScope = admitted,
      explicitEnvironment = true,
    )
  }

  private fun prepareGovernedReview(request: SkillRunRequest): OpenCodeReviewPreparation {
    val home = userHome ?: return OpenCodeReviewPreparation.Refused("The OpenCode user home cannot be verified.")
    return captureLaunchResourceOutcome {
      OpenCodeReviewLaunchScope.prepare(request, bindingEnvironment, home, hostPlatform)
    }.getOrElse { failure ->
      if (failure is CancellationException) throw failure
      governedPreparationFailure(failure)
    }
  }

  private fun governedPreparationFailure(failure: Throwable): OpenCodeReviewPreparation.Refused {
    if (failure.suppressed.isNotEmpty()) throw failure
    val reason =
      when (failure) {
        is SkillBillRuntimeException ->
          (
            failure.failureCodeLabel()?.let { "$it: " }.orEmpty() +
              (failure.message ?: "OpenCode review isolation could not be established.")
          ).take(MAX_PREPARATION_DIAGNOSTIC_CHARS)
        is IOException -> "OpenCode review isolation files could not be read or prepared."
        is SecurityException -> "OpenCode review isolation files could not be inspected."
        is UnsupportedOperationException -> "The host cannot enforce private OpenCode review permissions."
        else -> throw failure
      }
    return OpenCodeReviewPreparation.Refused(reason)
  }

  private fun failedGovernedReviewCommand(
    request: SkillRunRequest,
    reason: String,
  ): AgentRunCommand =
    AgentRunCommand(
      command = listOf(SupportedAgent.OPENCODE.wireValue) + AcpProvider.OpenCode.acpArguments,
      workingDirectory = request.repoRoot,
      timeout = request.timeout,
      inheritEnvironment = false,
      approvalProtocol = approvalProtocol,
      sessionModel = request.modelOverride,
      sessionEffort = request.effortOverride,
      preflightFailure = "${GovernedReviewFailureCode.LAUNCH_CAPABILITY.name}: $reason",
      idlePolicy = unstreamedLivenessPolicy(request),
      conversationIsolation = governedReviewConversationIsolation(request),
      explicitEnvironment = true,
    )

  private fun governedReviewRefusal(request: SkillRunRequest): String? =
    when {
      request.approval != AgentRunApprovalRequirement.GovernedReview ->
        "governed review authority is missing"
      request.reviewEvidenceBroker == null || request.reviewEvidenceEndpoint == null ->
        "lane-bound review evidence broker and endpoint are required"
      request.nativeReviewWorkerName.isNullOrBlank() -> "the selected native worker is missing"
      request.openCodeIdentity == null -> "the resolved OpenCode source identity is missing"
      request.modelOverride?.let {
        it.substringBefore('/').isNotBlank() && '/' in it && it.substringAfter('/').isNotBlank()
      } != true ->
        "the selected provider and model are missing"
      request.promptOverride.isNullOrBlank() -> "the immutable parent review packet is missing"
      request.reviewFanOut -> "OpenCode governed review requires a single-lane assignment"
      request.goalContinuation != null -> "a governed review cannot use goal continuation"
      else -> null
    }

  private fun isGovernedReviewIntent(request: SkillRunRequest): Boolean =
    request.approval == AgentRunApprovalRequirement.GovernedReview ||
      request.reviewEvidenceBroker != null || request.reviewEvidenceEndpoint != null
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

private const val MAX_PREPARATION_DIAGNOSTIC_CHARS = 240
