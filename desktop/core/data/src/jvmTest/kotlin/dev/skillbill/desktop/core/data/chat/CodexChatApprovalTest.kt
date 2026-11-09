package dev.skillbill.desktop.core.data.chat

import dev.skillbill.desktop.core.domain.model.ApprovalCard
import dev.skillbill.desktop.core.testing.RecordingDiagnostics
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonObject
import skillbill.approval.model.ApprovalMatchFacts
import skillbill.approval.model.ApprovalProviderDisposition
import skillbill.contracts.approval.ApprovalProviderRequestId
import skillbill.contracts.approval.ApprovalRequestRecord
import skillbill.contracts.approval.ApprovalState
import skillbill.ports.approval.model.ApprovalDecisionResult
import skillbill.ports.approval.model.ApprovalDeliveryState
import skillbill.ports.approval.model.ApprovalOwnerKey
import skillbill.ports.approval.model.ApprovalOwnerSession
import skillbill.ports.approval.model.ApprovalPendingWait
import skillbill.ports.approval.model.ApprovalProviderClearance
import skillbill.ports.approval.model.ApprovalRegistrationCommand
import skillbill.ports.approval.model.ApprovalRegistrationResult
import java.nio.file.Files
import kotlin.test.Test
import kotlin.test.assertContains
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

class CodexChatApprovalTest {
  @Test
  fun `command and file cards keep complete item facts typed ids and one time responses out of order`() {
    val harness = Harness()
    val diff = "+complete patch ".repeat(2000)
    val file = obj("""{"type":"fileChange","id":"file","changes":[{"path":"/checkout/a","kind":{"type":"update"},"diff":${JsonPrimitive(diff)}}]}""")
    harness.approval.handleServerRequest(command(), emptyMap())
    harness.approval.handleServerRequest(obj("""{"id":"7","method":"item/fileChange/requestApproval","params":{"threadId":"thread","turnId":"turn","itemId":"file","grantRoot":"/checkout"}}"""),
      mapOf(codexItemKey("thread", "turn", "file") to file))
    assertEquals(2, harness.cards.size)
    assertContains(harness.cards[1].inputText, diff)
    val command = harness.owner.requests[0]
    val patch = harness.owner.requests[1]
    assertNotNull(command.actionFingerprint)
    assertNotNull(patch.actionFingerprint)
    assertEquals(ApprovalDeliveryState.WRITTEN, harness.approval.deliver(patch.copy(state = ApprovalState.ALLOWED)))
    assertEquals(ApprovalDeliveryState.WRITTEN, harness.approval.deliver(command.copy(state = ApprovalState.DENIED)))
    assertEquals(JsonPrimitive("7"), harness.frames[0]["id"])
    assertEquals(JsonPrimitive(7), harness.frames[1]["id"])
    assertEquals("accept", harness.frames[0].obj("result")?.string("decision"))
    assertEquals("decline", harness.frames[1].obj("result")?.string("decision"))
    assertFalse(harness.stopped)
    harness.approval.providerResolved(obj("""{"threadId":"thread","requestId":7}"""))
    val before = harness.frames.size
    assertEquals(ApprovalDeliveryState.NOT_REQUIRED, harness.approval.deliver(command.copy(state = ApprovalState.ALLOWED)))
    assertEquals(before, harness.frames.size)
    harness.approval.release()
  }

  @Test
  fun `provider clearance makes pending exact card inert without writing a cancellation response`() {
    val harness = Harness()
    harness.approval.handleServerRequest(command(), emptyMap())
    harness.approval.providerResolved(obj("""{"threadId":"thread","requestId":7}"""))
    assertEquals(listOf(harness.owner.requests.single().approvalId), harness.owner.cleared)
    assertEquals(ApprovalDeliveryState.NOT_REQUIRED, harness.approval.deliver(harness.owner.requests.single().copy(state = ApprovalState.ALLOWED)))
    assertTrue(harness.frames.isEmpty())
    harness.approval.release()
  }

  @Test
  fun `match facts honor shell unwrap grant root and checkout cwd`() {
    val checkout = Files.createTempDirectory("codex-facts").toRealPath()
    try {
      val harness = Harness(checkout = checkout.toString())
      val checkoutJson = jsonPathLiteral(checkout.toString())
      val wrapped =
        obj(
          """{"id":10,"method":"item/commandExecution/requestApproval",""" +
            """"params":{"threadId":"thread","turnId":"turn","itemId":"wrap",""" +
            """"command":"bash -lc 'git status --short'","cwd":"$checkoutJson",""" +
            """"commandActions":[{"type":"unknown","command":"bash -lc 'git status --short'"}]}}""",
        )
      harness.approval.handleServerRequest(wrapped, emptyMap())
      assertEquals(ApprovalMatchFacts.Command("git status --short"), harness.owner.commands.last().matchFacts)
      val quoted =
        obj(
          """{"id":11,"method":"item/commandExecution/requestApproval",""" +
            """"params":{"threadId":"thread","turnId":"turn","itemId":"quote",""" +
            """"command":"bash -lc \"git status\"","cwd":"$checkoutJson",""" +
            """"commandActions":[{"type":"unknown","command":"bash -lc \"git status\""}]}}""",
        )
      harness.approval.handleServerRequest(quoted, emptyMap())
      assertEquals(null, harness.owner.commands.last().matchFacts)
      val tabulated =
        obj(
          """{"id":14,"method":"item/commandExecution/requestApproval",""" +
            """"params":{"threadId":"thread","turnId":"turn","itemId":"tab",""" +
            """"command":"bash\t-lc 'git status'","cwd":"$checkoutJson",""" +
            """"commandActions":[{"type":"unknown","command":"bash\t-lc 'git status'"}]}}""",
        )
      harness.approval.handleServerRequest(tabulated, emptyMap())
      assertEquals(null, harness.owner.commands.last().matchFacts)
      val diff = "+patch"
      val file =
        obj("""{"type":"fileChange","id":"grant","changes":[{"path":"$checkoutJson/a","kind":{"type":"update"},"diff":${JsonPrimitive(diff)}}]}""")
      harness.approval.handleServerRequest(
        obj(
          """{"id":12,"method":"item/fileChange/requestApproval","params":{"threadId":"thread","turnId":"turn","itemId":"grant","grantRoot":"$checkoutJson"}}""",
        ),
        mapOf(codexItemKey("thread", "turn", "grant") to file),
      )
      assertEquals(null, harness.owner.commands.last().matchFacts)
      val outside = Files.createTempDirectory("codex-outside").toRealPath()
      try {
        val outsideJson = jsonPathLiteral(outside.toString())
        harness.approval.handleServerRequest(
          obj(
            """{"id":13,"method":"item/commandExecution/requestApproval",""" +
              """"params":{"threadId":"thread","turnId":"turn","itemId":"away",""" +
              """"command":"git status","cwd":"$outsideJson",""" +
              """"commandActions":[{"type":"unknown","command":"git status"}]}}""",
          ),
          emptyMap(),
        )
        assertEquals(null, harness.owner.commands.last().matchFacts)
      } finally {
        Files.walk(outside).use { paths ->
          paths.sorted(Comparator.reverseOrder()).forEach(Files::deleteIfExists)
        }
      }
    } finally {
      Files.walk(checkout).use { paths ->
        paths.sorted(Comparator.reverseOrder()).forEach(Files::deleteIfExists)
      }
    }
  }

  @Test
  fun `network permissions and elicitation refuse with distinct documented responses and full cards`() {
    val harness = Harness()
    val requests = listOf(
      command(network = true),
      obj("""{"id":"permissions","method":"item/permissions/requestApproval","params":{"threadId":"thread","turnId":"turn","itemId":"p","permissions":{"network":{"enabled":true}}}}"""),
      obj("""{"id":"mcp","method":"mcpServer/elicitation/request","params":{"threadId":"thread","turnId":"turn","serverName":"test","message":"complete reason","mode":"form"}}"""),
    )
    requests.forEach { harness.approval.handleServerRequest(it, emptyMap()) }
    assertEquals(3, harness.cards.size)
    assertEquals("cancel", harness.frames[0].obj("result")?.string("decision"))
    assertEquals(obj("""{"permissions":{},"scope":"turn"}"""), harness.frames[1].obj("result"))
    assertEquals(obj("""{"action":"cancel"}"""), harness.frames[2].obj("result"))
    assertTrue(harness.cards.all { it.options.none { option -> option.kind == "allow" } })
    assertContains(harness.cards[2].inputText, "complete reason")
    assertFalse(harness.stopped)
    harness.approval.release()
  }

  private class Harness(checkout: String = "/checkout") {
    val frames = mutableListOf<JsonObject>()
    val cards = mutableListOf<ApprovalCard>()
    var stopped = false
    val owner = Owner()
    val approval = CodexChatApproval({ frames += obj(it); true }, { stopped = true },
      RecordingDiagnostics(), cards::add, checkout)
    init { approval.bind(owner); approval.bindTurn("thread", "turn"); owner.deliver = approval::deliver }
  }

  private class Owner : ApprovalOwnerSession {
    override val key = ApprovalOwnerKey("repo", "run", "generation")
    val requests = mutableListOf<ApprovalRequestRecord>()
    val commands = mutableListOf<ApprovalRegistrationCommand>()
    val cleared = mutableListOf<String>()
    lateinit var deliver: (ApprovalRequestRecord) -> ApprovalDeliveryState
    override fun register(command: ApprovalRegistrationCommand): ApprovalRegistrationResult {
      val request = command.request.copy(actionFingerprint = command.canonicalInput,
        authorityScope = command.canonicalInput?.let { value -> command.request.authorityScope?.copy(actionFingerprint = value) })
      requests += request
      commands += command
      return ApprovalRegistrationResult.Registered(request)
    }
    override fun resolveByProvider(approvalId: String, providerRequestId: ApprovalProviderRequestId,
      disposition: ApprovalProviderDisposition): ApprovalDecisionResult {
      val request = requests.first { it.approvalId == approvalId }.copy(state = ApprovalState.UNSUPPORTED)
      deliver(request)
      return ApprovalDecisionResult.Settled(request)
    }
    override fun clearByProvider(approvalId: String, providerRequestId: ApprovalProviderRequestId,
      clearance: ApprovalProviderClearance): ApprovalDecisionResult { cleared += approvalId; return ApprovalDecisionResult.Missing }
    override fun pendingWait(): ApprovalPendingWait? = null
    override fun release() = Unit
  }

  private companion object {
    fun jsonPathLiteral(path: String): String = path.replace('\\', '/')

    fun obj(raw: String): JsonObject = Json.parseToJsonElement(raw).jsonObject
    fun command(network: Boolean = false): JsonObject = obj("""{"id":7,"method":"item/commandExecution/requestApproval","params":{"threadId":"thread","turnId":"turn","itemId":"cmd","command":"git status","cwd":"/checkout","commandActions":[{"type":"unknown","command":"git status"}],"availableDecisions":null${if (network) ",\"networkApprovalContext\":{\"host\":\"example.test\"}" else ""}}}""")
  }
}
