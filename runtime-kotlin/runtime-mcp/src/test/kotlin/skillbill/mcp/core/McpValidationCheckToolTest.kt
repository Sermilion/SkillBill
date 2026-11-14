package skillbill.mcp.core

import skillbill.contracts.SharedPayloadKeys
import skillbill.contracts.persistence.RuntimeMcpEnvironmentKeys
import skillbill.contracts.system.UpdateCheckPayloadKeys
import skillbill.mcp.featuretask.VALIDATION_CHECK_PROTOCOL
import skillbill.mcp.featuretask.ValidationCheckPayloadKeys
import skillbill.mcp.shared.McpRuntimeContext
import skillbill.mcp.shared.callToolError
import skillbill.mcp.shared.decodeResponse
import skillbill.mcp.shared.disabledTelemetryEnvironment
import skillbill.mcp.shared.fieldMap
import skillbill.mcp.shared.properties
import skillbill.mcp.shared.schemaFor
import skillbill.mcp.shared.toolNamedOrNull
import skillbill.ports.db.DatabaseSessionFactory
import skillbill.ports.idestatus.model.LiveChecksAvailability
import skillbill.ports.idestatus.model.StandalonePhaseStatusRegistration
import skillbill.workflow.taskruntime.model.skeleton.SkeletonDefinition
import java.nio.file.Files
import java.time.Instant
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class McpValidationCheckToolTest {
  @Test
  fun `a server without a validation run hides the tool and rejects a call`() {
    val context = context(emptyMap())

    assertNull(toolsList(context).toolNamedOrNull(TOOL))
    val payload =
      context.callToolError(
        TOOL,
        mapOf(
          ValidationCheckPayloadKeys.EVENT to "planned",
          ValidationCheckPayloadKeys.CHECK_ID to "unit-tests",
          ValidationCheckPayloadKeys.NAME to "Unit tests",
          ValidationCheckPayloadKeys.COMMAND to "./gradlew test",
          ValidationCheckPayloadKeys.WORKING_DIR to ".",
        ),
      )
    assertEquals("rejected", payload[SharedPayloadKeys.STATUS])
    assertEquals("no_run_identity", payload[UpdateCheckPayloadKeys.REASON])
  }

  @Test
  fun `a server launched for a validation run lists the tool with its schema`() {
    val context = context(mapOf(RuntimeMcpEnvironmentKeys.VALIDATION_INVOCATION_ID to "validation-run"))

    val schema = toolsList(context).schemaFor(TOOL)
    assertEquals(false, schema["additionalProperties"])
    assertEquals(
      setOf("event", "check_id", "name", "command", "working_dir"),
      (schema["required"] as List<*>).toSet(),
    )
    assertEquals(
      "^[a-z0-9][a-z0-9._-]{0,63}$",
      schema.properties().fieldMap(ValidationCheckPayloadKeys.CHECK_ID)["pattern"],
    )
  }

  @Test
  fun `initialize marks a live validation run available and leaves a finished run unchanged`() {
    val plain = context(emptyMap())
    assertNull(initialize(plain)["instructions"])

    val live = context(mapOf(RuntimeMcpEnvironmentKeys.VALIDATION_INVOCATION_ID to RUN))
    val database = live.runtimeComponent().databaseSession
    register(database, RUN, lifecycleState = "active")
    val validationInitialize = initialize(live)
    assertEquals(VALIDATION_CHECK_PROTOCOL, validationInitialize["instructions"])
    assertEquals("sona", validationInitialize.fieldMap("serverInfo")["name"])
    assertEquals(LiveChecksAvailability.AVAILABLE, liveChecks(database, RUN))

    val settled = context(mapOf(RuntimeMcpEnvironmentKeys.VALIDATION_INVOCATION_ID to SETTLED))
    val settledDatabase = settled.runtimeComponent().databaseSession
    register(settledDatabase, SETTLED, lifecycleState = "terminal")
    assertEquals(VALIDATION_CHECK_PROTOCOL, initialize(settled)["instructions"])
    assertNull(liveChecks(settledDatabase, SETTLED))
  }

  private fun initialize(context: McpRuntimeContext): Map<String, Any?> =
    decodeResponse(
      McpStdioServer.handleLine(
        """{"jsonrpc":"2.0","id":"init","method":"initialize","params":{}}""",
        context.mcpComponent(),
      ),
    ).fieldMap("result")

  private fun liveChecks(
    database: DatabaseSessionFactory,
    invocationId: String,
  ): LiveChecksAvailability? = database.read { it.standalonePhaseStatuses.readByInvocation(invocationId)?.liveChecks }

  private fun register(
    database: DatabaseSessionFactory,
    invocationId: String,
    lifecycleState: String,
  ) {
    database.transaction {
      it.standalonePhaseStatuses.register(
        StandalonePhaseStatusRegistration(
          repositoryIdentity = "repo", branchCorrelation = "main", issueKey = null, workflowId = null,
          invocationId = invocationId, phaseId = SkeletonDefinition.VALIDATION.id,
          executionId = "execution-$invocationId", lifecycleState = lifecycleState,
          currentStep = SkeletonDefinition.VALIDATION.id, startedAt = Instant.EPOCH, leaseOwner = "owner",
          leaseGeneration = 1, leaseExpiresAt = Instant.EPOCH.plusSeconds(3600),
        ),
      )
    }
  }

  private fun context(extra: Map<String, String>): McpRuntimeContext {
    val tempDir = Files.createTempDirectory("skillbill-mcp-validation-check")
    return McpRuntimeContext(environment = disabledTelemetryEnvironment(tempDir) + extra, userHome = tempDir)
  }

  private fun toolsList(context: McpRuntimeContext): List<*> =
    decodeResponse(
      McpStdioServer.handleLine(
        """{"jsonrpc":"2.0","id":"tools","method":"tools/list","params":{}}""",
        context.mcpComponent(),
      ),
    ).fieldMap("result")["tools"] as List<*>

  private companion object {
    const val TOOL = "validation_check"
    const val RUN = "validation-run"
    const val SETTLED = "settled-run"
  }
}
