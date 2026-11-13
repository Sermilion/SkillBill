package skillbill.infrastructure.launcher.mcp

import com.fasterxml.jackson.databind.ObjectMapper
import skillbill.contracts.review.GovernedReviewEvidenceContracts
import skillbill.infrastructure.launcher.agentrun.NoOpReviewEvidenceBroker
import skillbill.infrastructure.launcher.agentrun.StubReviewEvidenceEndpoint
import skillbill.infrastructure.launcher.agentrun.request
import skillbill.ports.review.evidence.GovernedReviewEvidenceEndpointHandle
import java.nio.file.Files
import java.nio.file.attribute.PosixFilePermissions
import kotlin.test.Test
import kotlin.test.assertEquals

class OpenCodeReviewConfigWriterTest {
  @Test
  fun `config admits only exact tools from the lane endpoint after deny all`() {
    val root = Files.createTempDirectory("opencode-review-config-test-")
    try {
      val descriptor = StubReviewEvidenceEndpoint.descriptor
      val endpointConfig = root.resolve("mcp.json")
      val env =
        mapOf(
          GovernedReviewEvidenceContracts.SOCKET_ENV to descriptor.socketPath.toString(),
          GovernedReviewEvidenceContracts.TOKEN_ENV to descriptor.token,
          GovernedReviewEvidenceContracts.LANE_ENV to descriptor.lane,
        )
      ObjectMapper().writeValue(
        endpointConfig.toFile(),
        mapOf(
          OpenCodeReviewConfigKeys.MCP_SERVERS to
            mapOf(
              GovernedReviewEvidenceContracts.SERVER_NAME to
                mapOf(
                  OpenCodeReviewConfigKeys.COMMAND to "/skill-bill-mcp",
                  OpenCodeReviewConfigKeys.ARGS to listOf("evidence"),
                  OpenCodeReviewConfigKeys.INPUT_ENVIRONMENT to env,
                ),
            ),
        ),
      )
      val request =
        request("anthropic/claude-sonnet").copy(
          reviewEvidenceBroker = NoOpReviewEvidenceBroker,
          reviewEvidenceEndpoint =
            object : GovernedReviewEvidenceEndpointHandle {
              override val descriptor = StubReviewEvidenceEndpoint.descriptor.copy(mcpConfigPath = endpointConfig)

              override fun close() = Unit
            },
        )
      val output = root.resolve("opencode.json")
      OpenCodeReviewConfigWriter.write(output, request)
      val config = ObjectMapper().readTree(Files.readAllBytes(output))
      val permission = config.get(OpenCodeReviewConfigKeys.PERMISSION)
      assertEquals(OpenCodeReviewConfigKeys.DENY, permission.get(OpenCodeReviewConfigKeys.DENY_ALL_TOOL_KEY).asText())
      assertEquals(
        listOf(
          OpenCodeReviewConfigKeys.DENY_ALL_TOOL_KEY,
          "skill-bill-review-evidence_read_evidence",
          "skill-bill-review-evidence_request_expansion",
        ),
        permission.fieldNames().asSequence().toList(),
      )
      val mcp = config.get(OpenCodeReviewConfigKeys.MCP).get(GovernedReviewEvidenceContracts.SERVER_NAME)
      assertEquals("local", mcp.get(OpenCodeReviewConfigKeys.TYPE).asText())
      assertEquals("/skill-bill-mcp", mcp.get(OpenCodeReviewConfigKeys.COMMAND)[0].asText())
      assertEquals(
        "architecture",
        mcp.get(OpenCodeReviewConfigKeys.ENVIRONMENT).get(GovernedReviewEvidenceContracts.LANE_ENV).asText(),
      )
      assertEquals(PosixFilePermissions.fromString("rw-------"), Files.getPosixFilePermissions(output))
    } finally {
      root.toFile().deleteRecursively()
    }
  }
}
