package skillbill.infrastructure.launcher.agentrun

import skillbill.infrastructure.launcher.process.launch.AgentRunProcessLaunchFields
import skillbill.infrastructure.launcher.process.launch.AgentRunProcessResult
import skillbill.infrastructure.skills.install.mcp.McpRegistrationOperations
import skillbill.install.model.SupportedAgent
import skillbill.ports.agentrun.model.SkillRunRequest
import skillbill.ports.diagnostics.RuntimeDiagnostics
import skillbill.ports.review.model.ReviewLaunchIsolationStrategy
import java.nio.file.Path
import kotlin.time.DurationUnit

internal class JunieAgentRunCommandBuilder(
  override val governedReviewLaunchCapability: GovernedReviewLaunchCapability =
    GovernedReviewLaunchCapability(
      governedOnlyTooling = false,
      mcpIsolation = false,
      configFormat = McpRegistrationOperations.configFormatFor(SupportedAgent.JUNIE),
    ),
  private val databasePath: Path? = null,
) : AgentRunCommandBuilder {
  override val agent: SupportedAgent = SupportedAgent.JUNIE
  override val reviewIsolation: ReviewLaunchIsolationStrategy = ReviewLaunchIsolationStrategy.FRESH_PROCESS
  override val outputDecoder: AgentRunOutputDecoder = AgentRunOutputDecoder.JUNIE_TEXT
  override val approvalUnavailableReason =
    "Junie cannot run card-required execution: Junie ACP exists, but " +
      "permission-request coverage is " +
      "unverified for this integration. Select a supported provider explicitly; " +
      "no process was started."
  override val approvalProtocol: AgentRunApprovalProtocol? = null

  override fun continuation(
    command: AgentRunCommand,
    diagnostics: RuntimeDiagnostics,
  ): ((AgentRunProcessResult) -> AgentRunProcessLaunchFields?)? = null

  override fun build(request: SkillRunRequest): AgentRunCommand {
    requireProcessLaunch(request, reviewIsolation)
    requireGovernedReviewLaunch(request, agent, governedReviewLaunchCapability)
    return goalContinuationCommand(request, agent, databasePath) ?: AgentRunCommand(
      command =
        buildList {
          require(request.modelOverride == null && request.effortOverride == null) {
            "junie cannot honor a model/effort directive; " +
              "remove its execution_matrix entry or --phase-model assignment."
          }
          add(SupportedAgent.JUNIE.wireValue)
          add("--project")
          add(request.repoRoot.toString())
          add("--output-format")
          add("text")
          add("--skip-update-check")
          request.timeout?.let { timeout ->
            add("--timeout")
            add(timeout.toLong(DurationUnit.MILLISECONDS).toString())
          }
          add(launchPrompt(request))
        },
      workingDirectory = request.repoRoot,
      timeout = request.timeout,
      environment =
        goalContinuationEnvironment(request) +
          runtimeDatabaseEnvironment(request.reviewEvidenceBroker == null, databasePath),
      inheritEnvironment = request.reviewEvidenceBroker == null,
      conversationIsolation = governedReviewConversationIsolation(request),
      idlePolicy = unstreamedLivenessPolicy(request),
      environmentPassthroughKeys =
        if (request.reviewEvidenceBroker != null) JUNIE_PROVIDER_PASSTHROUGH_KEYS else emptySet(),
    )
  }
}
