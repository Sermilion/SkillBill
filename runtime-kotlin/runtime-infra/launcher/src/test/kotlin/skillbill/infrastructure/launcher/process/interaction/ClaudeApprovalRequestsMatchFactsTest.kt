package skillbill.infrastructure.launcher.process.interaction

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import skillbill.approval.model.ApprovalMatchFacts
import skillbill.approval.model.ApprovalProviderDisposition
import skillbill.contracts.approval.ApprovalProviderRequestId
import skillbill.ports.approval.model.ApprovalDecisionResult
import skillbill.ports.approval.model.ApprovalOwnerKey
import skillbill.ports.approval.model.ApprovalOwnerSession
import skillbill.ports.approval.model.ApprovalPendingWait
import skillbill.ports.approval.model.ApprovalProviderClearance
import skillbill.ports.approval.model.ApprovalRegistrationCommand
import skillbill.ports.approval.model.ApprovalRegistrationResult
import java.nio.file.Files
import java.nio.file.Path
import java.time.Clock
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class ClaudeApprovalRequestsMatchFactsTest {
  @Test
  fun `bash edit and read registrations carry expected match facts`() {
    val checkout = Files.createTempDirectory("claude-facts").toRealPath()
    try {
      Files.createDirectories(checkout.resolve("src"))
      val target = checkout.resolve("src/A.kt")
      val owner = Owner()
      val approval =
        ClaudeApprovalRequests(
          checkout.toString(),
          { true },
          {},
          {},
          {},
          {},
          Clock.systemUTC(),
        )
      approval.bind(owner)
      register(approval, "1", "Bash", """{"command":" git status "}""")
      assertEquals(ApprovalMatchFacts.Command("git status"), owner.commands.single().matchFacts)
      owner.commands.clear()
      register(approval, "2", "Edit", """{"file_path":"$target"}""")
      assertEquals(ApprovalMatchFacts.Paths(listOf("src/A.kt")), owner.commands.single().matchFacts)
      owner.commands.clear()
      register(approval, "3", "Read", """{"file_path":"$target"}""")
      assertNull(owner.commands.single().matchFacts)
    } finally {
      deleteRecursively(checkout)
    }
  }

  private fun register(
    approval: ClaudeApprovalRequests,
    id: String,
    tool: String,
    input: String,
  ) {
    val line =
      """{"type":"control_request","request_id":"$id","request":{"subtype":"can_use_tool",""" +
        """"tool_name":"$tool","input":$input}}"""
    approval.handle(Json.parseToJsonElement(line).jsonObject, line)
  }

  private fun deleteRecursively(path: Path) {
    if (!Files.exists(path)) return
    Files.walk(path).use { paths ->
      paths.sorted(Comparator.reverseOrder()).forEach(Files::deleteIfExists)
    }
  }

  private class Owner : ApprovalOwnerSession {
    override val key = ApprovalOwnerKey("repo", "run", "generation")
    val commands = mutableListOf<ApprovalRegistrationCommand>()

    override fun register(command: ApprovalRegistrationCommand): ApprovalRegistrationResult {
      commands += command
      return ApprovalRegistrationResult.Registered(command.request)
    }

    override fun resolveByProvider(
      approvalId: String,
      providerRequestId: ApprovalProviderRequestId,
      disposition: ApprovalProviderDisposition,
    ): ApprovalDecisionResult = ApprovalDecisionResult.Missing

    override fun clearByProvider(
      approvalId: String,
      providerRequestId: ApprovalProviderRequestId,
      clearance: ApprovalProviderClearance,
    ): ApprovalDecisionResult = ApprovalDecisionResult.Missing

    override fun pendingWait(): ApprovalPendingWait? = null

    override fun release() = Unit
  }
}
