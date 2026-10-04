package skillbill.infrastructure.launcher.agentrun

import skillbill.config.model.PhaseCompactionDirective
import skillbill.contracts.review.GovernedReviewEvidenceContracts
import skillbill.error.core.SkillBillRuntimeException
import skillbill.error.shellcontent.GovernedReviewFailureCode
import skillbill.install.model.SupportedAgent
import skillbill.ports.agentrun.model.SkillRunRequest
import skillbill.ports.review.evidence.GovernedReviewEvidenceEndpointHandle
import skillbill.ports.review.evidence.ReviewEvidenceBroker
import skillbill.ports.review.model.GovernedReviewEvidenceEndpointDescriptor
import skillbill.ports.review.model.ReviewEvidenceBatchRequest
import skillbill.ports.review.model.ReviewExpansionAuthorizationRequest
import skillbill.ports.review.model.ReviewToolCall
import skillbill.review.context.model.hunk.ReviewExpansionRecord
import java.nio.file.Path
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.seconds

internal fun assertGovernedReviewLaunch(
  builder: AgentRunCommandBuilder,
  governed: SkillRunRequest,
) {
  if (!builder.governedReviewLaunchCapability.governedOnlyTooling ||
    !builder.governedReviewLaunchCapability.mcpIsolation
  ) {
    val error = assertFailsWith<SkillBillRuntimeException> { builder.build(governed) }
    assertEquals(GovernedReviewFailureCode.LAUNCH_CAPABILITY, error.code)
    val message = error.message.orEmpty()
    assertTrue(message.contains("Agent '${builder.agent.id}'"))
    assertTrue(
      message.contains("missing capability 'governed-only tooling'") ||
        message.contains("missing capability 'MCP isolation'"),
      "Junie must name the missing capability, got '$message'",
    )
    return
  }
  val built = builder.build(governed)
  val rawFilesystemTools = setOf("Read", "Grep", "Glob", "Bash")
  assertTrue(
    built.command.none { arg -> arg.split(',').any { it in rawFilesystemTools } },
    built.command.toString(),
  )
  when (builder.agent) {
    SupportedAgent.CLAUDE -> assertClaudeGovernedLaunch(built.command)
    SupportedAgent.CODEX -> assertCodexGovernedLaunch(built.command)
    SupportedAgent.CURSOR -> assertCursorGovernedLaunch(built, governed, rawFilesystemTools)
    else -> error("unexpected provider ${builder.agent.id}")
  }
}

internal fun assertClaudeGovernedLaunch(command: List<String>) {
  val mcpJson = StubReviewEvidenceEndpoint.descriptor.mcpConfigPath.toString()
  assertEquals(mcpJson, command[command.indexOf("--mcp-config") + 1])
  assertTrue(command.contains("--strict-mcp-config"))
  val tools = command[command.indexOf("--tools") + 1].split(",")
  assertEquals(
    GovernedReviewEvidenceContracts.OPERATIONS.map { "mcp__${GovernedReviewEvidenceContracts.SERVER_NAME}__$it" },
    tools,
  )
}

internal fun assertCodexGovernedLaunch(command: List<String>) {
  val mcpTomlServer = "mcp_servers.${GovernedReviewEvidenceContracts.SERVER_NAME}"
  val governedOperations = GovernedReviewEvidenceContracts.OPERATIONS
  assertTrue(command.contains("--ignore-user-config"))
  val configValues = command.filterIndexed { index, _ -> index > 0 && command[index - 1] == "--config" }
  governedOperations.forEach { operation ->
    assertTrue("$mcpTomlServer.tools.$operation.approval_mode=\"approve\"" in configValues)
  }
  assertEquals("read-only", command[command.indexOf("--sandbox") + 1])
  assertTrue("--dangerously-bypass-approvals-and-sandbox" !in command)
  assertTrue(configValues.any { it.startsWith(mcpTomlServer) && it.contains("enabled_tools=") })
  assertTrue(
    configValues.any { value ->
      governedOperations.all { operation -> value.contains(operation) } && value.contains("enabled_tools=")
    },
  )
}

internal fun assertCursorGovernedLaunch(
  built: AgentRunCommand,
  governed: SkillRunRequest,
  rawFilesystemTools: Set<String>,
) {
  val command = built.command
  val workspace = StubReviewEvidenceEndpoint.descriptor.mcpConfigPath.parent
  assertEquals(workspace.toString(), command[command.indexOf("--workspace") + 1])
  assertEquals(workspace, built.workingDirectory)

  assertEquals(requireNotNull(governed.promptOverride), built.stdinText)
  assertTrue(command.none { it == governed.promptOverride })
  assertTrue(command.none { it.startsWith("/bill-code-review-inline") })
  assertTrue(command.contains("--approve-mcps"))

  assertTrue(command.contains("--force"))

  assertTrue(rawFilesystemTools.none { tool -> command.any { it.contains(tool) } })
}

internal fun request(
  model: String? = null,
  effort: String? = null,
  compaction: PhaseCompactionDirective? = null,
): SkillRunRequest =
  SkillRunRequest(
    issueKey = "SKILL-113",
    repoRoot = Path.of("/tmp/skillbill-agent-run"),
    subtaskId = 1,
    timeout = 3.seconds,
    promptOverride = "Phase: implement",
    modelOverride = model,
    effortOverride = effort,
    compaction = compaction,
  )

internal fun governedReviewRequest(nativeReviewWorkerName: String? = null): SkillRunRequest =
  request().copy(
    reviewEvidenceBroker = NoOpReviewEvidenceBroker,
    reviewEvidenceEndpoint = StubReviewEvidenceEndpoint,
    nativeReviewWorkerName = nativeReviewWorkerName,
  )

internal object StubReviewEvidenceEndpoint : GovernedReviewEvidenceEndpointHandle {
  override val descriptor =
    GovernedReviewEvidenceEndpointDescriptor(
      lane = "architecture",
      socketPath = Path.of("/tmp/skill-bill-review/evidence.sock"),
      mcpConfigPath = Path.of("/tmp/skill-bill-review/mcp.json"),
      token = "launch-token",
    )

  override fun close() = Unit
}

internal object NoOpReviewEvidenceBroker : ReviewEvidenceBroker {
  override fun authorizeExpansion(request: ReviewExpansionAuthorizationRequest): ReviewExpansionRecord = error("unused")

  override fun readBatch(request: ReviewEvidenceBatchRequest) = error("unused")

  override fun recordToolCall(call: ReviewToolCall) = error("unused")

  override fun recordModelTurn() = error("unused")

  override fun validateLaneResult(result: String) = error("unused")

  override fun observeLaneResultChunk(chunk: String) = error("unused")

  override fun accounting() = error("unused")

  override fun terminalOutcome() = error("unused")
}
