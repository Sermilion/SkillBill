package skillbill.infrastructure.launcher.agentrun

import skillbill.infrastructure.launcher.mcp.acpRuntimeMcpServer
import skillbill.infrastructure.launcher.mcp.attachesRuntimeMcp
import skillbill.infrastructure.launcher.process.cursor.AcpProvider
import skillbill.infrastructure.launcher.process.launch.AgentRunProcessLaunchFields
import skillbill.infrastructure.launcher.process.launch.AgentRunProcessResult
import skillbill.infrastructure.skills.install.mcp.McpRegistrationOperations
import skillbill.install.model.SupportedAgent
import skillbill.ports.agentrun.model.SkillRunRequest
import skillbill.ports.diagnostics.RuntimeDiagnostics
import skillbill.ports.review.model.ReviewLaunchIsolationStrategy
import java.nio.file.Path

internal class DevinAgentRunCommandBuilder(
  override val governedReviewLaunchCapability: GovernedReviewLaunchCapability =
    GovernedReviewLaunchCapability(
      governedOnlyTooling = false,
      mcpIsolation = false,
      configFormat = McpRegistrationOperations.configFormatFor(SupportedAgent.DEVIN),
    ),
  private val databasePath: Path? = null,
  private val runtimeMcpBin: Path? = null,
) : AgentRunCommandBuilder {
  override val agent: SupportedAgent = SupportedAgent.DEVIN
  override val reviewIsolation: ReviewLaunchIsolationStrategy = ReviewLaunchIsolationStrategy.FRESH_PROCESS
  override val approvalProtocol: AgentRunApprovalProtocol = AgentRunApprovalProtocol.DEVIN_ACP

  override fun continuation(
    command: AgentRunCommand,
    diagnostics: RuntimeDiagnostics,
  ): ((AgentRunProcessResult) -> AgentRunProcessLaunchFields?)? = null

  override fun launchRefusal(request: SkillRunRequest): String? {
    devinLaunchModelRefusal(request)?.let { return it }
    val wrapper = request.goalContinuation != null && request.promptOverride == null
    if (wrapper || !attachesRuntimeMcp(request)) return null
    return when {
      runtimeMcpBin == null ->
        "Devin phase workers require the sona runtime MCP executable; install or repair the runtime MCP " +
          "server, then retry. The phase was not started."
      databasePath == null ->
        "Devin phase workers require the selected runtime database for the sona runtime MCP server; " +
          "the phase was not started."
      else -> null
    }
  }

  override fun build(request: SkillRunRequest): AgentRunCommand {
    requireProcessLaunch(request, reviewIsolation)
    requireGovernedReviewLaunch(request, agent, governedReviewLaunchCapability)
    return goalContinuationCommand(request, agent, databasePath) ?: AgentRunCommand(
      command =
        buildList {
          add(SupportedAgent.DEVIN.wireValue)
          addAll(AcpProvider.Devin.launchArguments(request.modelOverride))
        },
      workingDirectory = request.repoRoot,
      timeout = request.timeout,
      stdinText = launchPrompt(request),
      environment =
        GoalContinuationEnvironment + goalContinuationEnvironment(request) +
          runtimeDatabaseEnvironment(true, databasePath),
      inheritEnvironment = true,
      conversationIsolation = governedReviewConversationIsolation(request),
      approvalProtocol = approvalProtocol,
      idlePolicy = unstreamedLivenessPolicy(request),
      sessionModel = request.modelOverride,
      acpMcpServers =
        if (runtimeMcpBin != null && databasePath != null && attachesRuntimeMcp(request)) {
          listOf(acpRuntimeMcpServer(runtimeMcpBin, databasePath, request.validationCheckInvocationId))
        } else {
          emptyList()
        },
    )
  }
}

internal fun devinLaunchModelRefusal(request: SkillRunRequest): String? =
  request.effortOverride?.takeIf(String::isNotBlank)?.let { effort ->
    "Devin does not accept a separate effort assignment ('$effort'); select the full Devin model UID " +
      "(for example swe-2-high) as the model and remove the effort. The phase was not started."
  }
