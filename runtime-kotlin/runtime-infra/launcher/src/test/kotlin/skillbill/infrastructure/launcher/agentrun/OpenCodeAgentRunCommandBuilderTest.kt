package skillbill.infrastructure.launcher.agentrun

import skillbill.contracts.system.OpenCodeLaunchIdentity
import skillbill.infrastructure.launcher.process.cursor.AcpEnvVariable
import skillbill.ports.review.model.ReviewLaunchIsolationStrategy
import java.nio.file.Path
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class OpenCodeAgentRunCommandBuilderTest {
  @Test
  fun `OpenCode ACP command carries selected database and validation environment as typed MCP values`() {
    val builder =
      OpenCodeAgentRunCommandBuilder(
        databasePath = Path.of("/tmp/runtime/../runtime/skill-bill.db"),
        runtimeMcpBin = Path.of("/opt/skill-bill/runtime-mcp"),
        bindingEnvironment = mapOf("OPENCODE_CONFIG_DIR" to "/operator/opencode"),
      )

    val command =
      builder.build(
        request(model = "anthropic/claude-sonnet-4-5", effort = "high").copy(
          validationCheckInvocationId = "validation-17",
        ),
      )

    assertEquals(listOf("opencode", "acp"), command.command)
    assertEquals(Path.of("/tmp/skillbill-agent-run"), command.workingDirectory)
    assertEquals("anthropic/claude-sonnet-4-5", command.sessionModel)
    assertEquals("high", command.sessionEffort)
    val server = command.acpMcpServers.single()
    assertEquals("skill-bill", server.name)
    assertEquals("/opt/skill-bill/runtime-mcp", server.command)
    assertTrue(server.args.isEmpty())
    assertEquals(
      listOf(
        AcpEnvVariable("SKILL_BILL_REVIEW_DB", "/tmp/runtime/skill-bill.db"),
        AcpEnvVariable("SKILL_BILL_VALIDATION_INVOCATION_ID", "validation-17"),
      ),
      server.env,
    )
    assertEquals("/operator/opencode", command.environment["OPENCODE_CONFIG_DIR"])
    assertNull(command.preflightFailure)
  }

  @Test
  fun `missing runtime MCP executable refuses before OpenCode can start`() {
    val command = OpenCodeAgentRunCommandBuilder(runtimeMcpBin = null).build(request())

    assertTrue(command.preflightFailure.orEmpty().contains("runtime MCP executable"))
    assertTrue(command.sessionMcpServers.isEmpty())
  }

  @Test
  fun `unverified governed review scope exposes no isolation capability`() {
    val builder = OpenCodeAgentRunCommandBuilder()
    val command =
      builder.build(
        governedReviewRequest(nativeReviewWorkerName = "bill-kotlin-code-review-architecture")
          .copy(
            modelOverride = "anthropic/claude-sonnet",
            promptOverride = "Immutable parent packet",
            openCodeIdentity = OpenCodeLaunchIdentity.capture(emptyMap(), Path.of("/operator"), Path.of("/repo")),
          ),
      )

    assertTrue(command.preflightFailure.orEmpty().contains("user home cannot be verified"))
    assertEquals(ReviewLaunchIsolationStrategy.UNSUPPORTED, builder.reviewIsolation)
    assertTrue(command.sessionMcpServers.isEmpty())
    assertFalse(builder.governedReviewLaunchCapability.governedOnlyTooling)
    assertFalse(builder.governedReviewLaunchCapability.mcpIsolation)
  }
}
