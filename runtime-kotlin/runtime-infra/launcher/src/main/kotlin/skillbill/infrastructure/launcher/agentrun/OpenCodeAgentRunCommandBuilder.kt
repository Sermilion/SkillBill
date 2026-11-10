package skillbill.infrastructure.launcher.agentrun

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
      return continuation.copy(
        environment = bindingEnvironment + continuation.environment,
        outputDecoder = AgentRunOutputDecoder.PLAIN,
      )
    }
    val binary = runtimeMcpBin?.toAbsolutePath()?.normalize()
    return AgentRunCommand(
      command = listOf(SupportedAgent.OPENCODE.wireValue) + AcpProvider.OpenCode.acpArguments,
      workingDirectory = request.repoRoot,
      timeout = request.timeout,
      stdinText = launchPrompt(request),
      environment =
        bindingEnvironment + goalContinuationEnvironment(request) + runtimeDatabaseEnvironment(true, databasePath),
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
        if (binary == null) "The runtime MCP executable is unavailable; OpenCode was not started." else null,
      idlePolicy = unstreamedLivenessPolicy(request),
    )
  }
}
