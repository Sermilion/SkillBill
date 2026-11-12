package skillbill.infrastructure.launcher.agentrun

import skillbill.contracts.persistence.RuntimeDatabaseEnvironmentKeys
import skillbill.contracts.persistence.RuntimeMcpEnvironmentKeys
import skillbill.error.core.SkillBillRuntimeException
import skillbill.error.shellcontent.GovernedReviewFailureCode
import skillbill.infrastructure.launcher.process.cursor.AcpEnvVariable
import skillbill.infrastructure.launcher.process.cursor.AcpStdioMcpServer
import skillbill.ports.agentrun.model.SkillRunGoalContinuationContext
import java.nio.file.Path
import kotlin.test.Test
import kotlin.test.assertContains
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNull
import kotlin.test.assertTrue

class DevinAgentRunCommandBuilderTest {
  private val database = Path.of("/var/tmp/sona-nondefault/runtime.db")
  private val runtimeMcp = Path.of("/opt/skill-bill/runtime-mcp")
  private val builder = DevinAgentRunCommandBuilder(databasePath = database, runtimeMcpBin = runtimeMcp)

  @Test
  fun `a devin phase worker passes the exact model UID and carries the selected runtime MCP context`() {
    val built = builder.build(request(model = "swe-2-high").copy(validationCheckInvocationId = "inv-7"))

    assertEquals(listOf("devin", "acp", "--model", "swe-2-high"), built.command)
    assertEquals("swe-2-high", built.sessionModel)
    assertEquals("Phase: implement", built.stdinText)
    assertEquals(AgentRunApprovalProtocol.DEVIN_ACP, built.approvalProtocol)
    assertEquals(
      listOf(
        AcpStdioMcpServer(
          name = "skill-bill",
          command = runtimeMcp.toString(),
          args = emptyList(),
          env =
            listOf(
              AcpEnvVariable(RuntimeDatabaseEnvironmentKeys.DATABASE_PATH, database.toString()),
              AcpEnvVariable(RuntimeMcpEnvironmentKeys.VALIDATION_INVOCATION_ID, "inv-7"),
            ),
        ),
      ),
      built.acpMcpServers,
    )
  }

  @Test
  fun `a devin launch with a separate effort or missing runtime context is refused before building`() {
    assertNull(builder.launchRefusal(request(model = "swe-2-high")))
    assertContains(
      requireNotNull(builder.launchRefusal(request(model = "swe-2", effort = "high"))),
      "full Devin model UID",
    )
    assertContains(
      requireNotNull(DevinAgentRunCommandBuilder(databasePath = database).launchRefusal(request())),
      "runtime MCP executable",
    )
    assertContains(
      requireNotNull(DevinAgentRunCommandBuilder(runtimeMcpBin = runtimeMcp).launchRefusal(request())),
      "runtime database",
    )
  }

  @Test
  fun `a devin governed specialist request keeps the typed capability refusal`() {
    val error = assertFailsWith<SkillBillRuntimeException> { builder.build(governedReviewRequest()) }

    assertEquals(GovernedReviewFailureCode.LAUNCH_CAPABILITY, error.code)
  }

  @Test
  fun `a devin goal continuation without a prompt override keeps the feature-task resume wrapper`() {
    val continuation =
      SkillRunGoalContinuationContext(
        parentIssueKey = "SKILL-113",
        subtaskId = 1,
        goalBranch = "feat/SKILL-113-goal",
        suppressPr = true,
        specPath = ".feature-specs/SKILL-113-goal/spec_subtask_1.md",
        assignedWorkflowId = "wfl-assigned",
      )
    val wrapperRequest = request().copy(promptOverride = null, goalContinuation = continuation)

    assertNull(DevinAgentRunCommandBuilder(databasePath = database).launchRefusal(wrapperRequest))
    val wrapper = builder.build(wrapperRequest)
    assertEquals("skill-bill", wrapper.command.first())
    assertTrue(wrapper.command.containsAll(listOf("feature-task", "resume", "wfl-assigned")))
    assertEquals(listOf("--agent", "devin"), wrapper.command.takeLast(2))
    assertNull(wrapper.stdinText)
    assertNull(wrapper.approvalProtocol)

    val direct = builder.build(request().copy(goalContinuation = continuation))
    assertEquals(listOf("devin", "acp"), direct.command)
    assertEquals("Phase: implement", direct.stdinText)
    assertEquals(AgentRunApprovalProtocol.DEVIN_ACP, direct.approvalProtocol)
  }
}
