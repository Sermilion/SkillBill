package skillbill.infrastructure.launcher.process.cursor

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonObject
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
import java.time.Clock
import java.time.Instant
import java.time.ZoneOffset
import kotlin.test.Test
import kotlin.test.assertContains
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class CursorAcpSessionTest {
  @Test
  fun `team dashboard dependency refuses before any ACP request`() {
    val harness = Harness(dependency = CursorAcpMcpDependency.TeamDashboardRequired)

    harness.session.start()

    assertNotNull(harness.session.outcome()?.failure)
    assertTrue(harness.frames.isEmpty())
    harness.session.close()
  }

  @Test
  fun `an undeclared Cursor launch starts as project and user configuration only`() {
    val harness = Harness(dependency = CursorAcpLaunch("/repo", "p", null, null).mcpDependency)

    harness.session.start()

    assertNull(harness.session.outcome())
    assertEquals(JsonPrimitive("initialize"), harness.frames.single()[CursorAcpKeys.METHOD])
    harness.session.close()
  }

  @Test
  fun `Junie enables Brave mode before prompting and retains cards for actual provider requests`() {
    val harness = Harness(dependency = CursorAcpMcpDependency.TeamDashboardRequired, provider = AcpProvider.Junie)
    harness.session.start()
    harness.respond("""{"protocolVersion":1,"agentCapabilities":{},"authMethods":[{"id":"cursor_login"}]}""")
    assertEquals(JsonPrimitive("session/new"), harness.frames.last()[CursorAcpKeys.METHOD])
    harness.respond("""{"sessionId":"s"}""")
    assertEquals(JsonPrimitive("session/set_config_option"), harness.frames.last()[CursorAcpKeys.METHOD])
    assertFalse(harness.session.ready)
    harness.respond("""{"configOptions":[{"id":"brave_mode","currentValue":true}]}""")
    assertEquals(JsonPrimitive("session/prompt"), harness.frames.last()[CursorAcpKeys.METHOD])
    assertTrue(harness.session.ready)

    harness.permission("1", "a", "ls")
    val card = harness.owner.requests.single().request
    assertEquals("junie", card.provider)
    assertTrue(card.approvalId.startsWith("junie:"))
    assertEquals(
      ApprovalDeliveryState.WRITTEN,
      harness.session.deliver(card.copy(state = ApprovalState.ALLOWED, selectedOptionId = "once-a")),
    )
    assertContains(harness.frames.last().toString(), "once-a")
    harness.session.close()
  }

  @Test
  fun `Junie refuses an unacknowledged Brave mode before sending a prompt`() {
    val harness = Harness(provider = AcpProvider.Junie)
    harness.session.start()
    harness.respond("""{"protocolVersion":1,"agentCapabilities":{}}""")
    harness.respond("""{"sessionId":"s"}""")
    harness.respond("""{"configOptions":[{"id":"brave_mode","currentValue":false}]}""")

    assertNotNull(harness.session.outcome()?.failure)
    assertFalse(harness.frames.any { it.text(CursorAcpKeys.METHOD) == "session/prompt" })
    harness.session.close()
  }

  @Test
  fun `Cursor and Junie publishing requests show full cards and wait for the operator choice`() {
    for (provider in listOf(AcpProvider.Cursor, AcpProvider.Junie)) {
      val harness = Harness(provider = provider)
      if (provider == AcpProvider.Cursor) {
        harness.open()
      } else {
        harness.session.start()
        harness.respond("""{"protocolVersion":1,"agentCapabilities":{}}""")
        harness.respond("""{"sessionId":"s"}""")
        harness.respond("""{"configOptions":[{"id":"brave_mode","currentValue":"on"}]}""")
      }
      val command = "gh issue create --repo example/tools --body 'Include the exact payload'"
      val before = harness.frames.toList()

      harness.permission("17", "publish", command)

      val card = harness.cards.single()
      assertEquals(provider.id, card.provider)
      assertContains(card.inputText, command)
      assertEquals(ApprovalState.PENDING, card.state)
      assertEquals(before, harness.frames)
      assertNull(harness.session.outcome())

      assertEquals(
        ApprovalDeliveryState.WRITTEN,
        harness.session.deliver(card.copy(state = ApprovalState.ALLOWED, selectedOptionId = "once-publish")),
      )
      assertEquals(
        Json.parseToJsonElement("""{"outcome":{"outcome":"selected","optionId":"once-publish"}}"""),
        harness.frames.last()[CursorAcpKeys.RESULT],
      )
      harness.session.close()
    }
  }

  @Test
  fun `interleaved permissions preserve typed ids full content and original one time options`() {
    val harness = Harness()
    harness.open()
    val large = "complete input ".repeat(2000)
    harness.permission("7", "a", large)
    harness.permission("\"7\"", "b", "second")
    assertEquals(2, harness.owner.requests.size)
    assertContains(harness.owner.requests[0].request.inputText, large)
    val first = harness.owner.requests[0].request
    val second = harness.owner.requests[1].request
    assertEquals(ApprovalProviderRequestId.Integer("7"), first.providerRequestId)
    assertEquals(ApprovalProviderRequestId.Text("7"), second.providerRequestId)
    assertEquals(
      ApprovalDeliveryState.WRITTEN,
      harness.session.deliver(
        second.copy(
          state = ApprovalState.ALLOWED,
          selectedOptionId = "once-b",
        ),
      ),
    )
    assertEquals(
      ApprovalDeliveryState.WRITTEN,
      harness.session.deliver(
        first.copy(
          state = ApprovalState.DENIED,
          selectedOptionId = "deny-a",
        ),
      ),
    )
    val responses = harness.frames.filter { it[CursorAcpKeys.RESULT] != null }
    assertEquals(JsonPrimitive("7"), responses[0][CursorAcpKeys.ID])
    assertEquals(JsonPrimitive(7), responses[1][CursorAcpKeys.ID])
    assertContains(responses[0].toString(), "once-b")
    assertContains(responses[1].toString(), "deny-a")
    assertFalse(responses.any { it.toString().contains("remember-") })
    harness.session.observe(
      """{"jsonrpc":"2.0","method":"session/update","params":{"sessionId":"s",""" +
        """"update":{"sessionUpdate":"tool_call_update","toolCallId":"a",""" +
        """"status":"completed"}}}""",
    )
    val before = harness.frames.size
    assertEquals(ApprovalDeliveryState.NOT_REQUIRED, harness.session.deliver(first.copy(state = ApprovalState.ALLOWED)))
    assertEquals(before, harness.frames.size)
    harness.session.close()
  }

  @Test
  fun `cancel sends cancelled outcomes and session cancellation without remembered decisions`() {
    val harness = Harness()
    harness.open()
    harness.permission("9", "a", "command")
    harness.session.close()
    val responses = harness.frames.filter { it[CursorAcpKeys.RESULT] != null }
    assertContains(responses.single().toString(), "cancelled")
    assertTrue(harness.frames.any { it.text(CursorAcpKeys.METHOD) == "session/cancel" })
    assertNotNull(harness.session.outcome()?.failure)
    val count = harness.frames.size
    harness.session.deliver(harness.owner.requests.single().request.copy(state = ApprovalState.ALLOWED))
    assertEquals(count, harness.frames.size)
  }

  @Test
  fun `unsupported saved sessions and unavailable model effort never receive a prompt or replacement session`() {
    for (saved in listOf("saved", null)) {
      val harness = Harness(saved, model = "model[effort=high]")
      harness.session.start()
      harness.respond("""{"protocolVersion":1,"agentCapabilities":{},"authMethods":[{"id":"cursor_login"}]}""")
      if (saved == null) {
        harness.respond("{}")
        harness.respond(
          """{"sessionId":"s","models":{"currentModelId":"other",""" +
            """"availableModels":[{"modelId":"other"}]},""" +
            """"modes":{"availableModes":[{"id":"agent"}]}}""",
        )
      }
      assertNotNull(harness.session.outcome()?.failure)
      assertFalse(harness.frames.any { it.text(CursorAcpKeys.METHOD) == "session/prompt" })
      if (saved != null) assertFalse(harness.frames.any { it.text(CursorAcpKeys.METHOD) == "session/new" })
      harness.session.close()
    }
  }

  @Test
  fun `incompatible ACP version stops before authentication or session creation`() {
    val harness = Harness()
    harness.session.start()

    harness.respond("""{"protocolVersion":2,"agentCapabilities":{},"authMethods":[{"id":"cursor_login"}]}""")

    assertNotNull(harness.session.outcome()?.failure)
    assertFalse(harness.frames.any { it.text(CursorAcpKeys.METHOD) == "authenticate" })
    assertFalse(harness.frames.any { it.text(CursorAcpKeys.METHOD) == "session/new" })
    assertFalse(harness.frames.any { it.text(CursorAcpKeys.METHOD) == "session/prompt" })
  }

  @Test
  fun `extension requests are answered and leave the session running`() {
    val cases =
      listOf(
        """{"jsonrpc":"2.0","id":"todos","method":"cursor/update_todos",""" +
          """"params":{"toolCallId":"call-1","todos":[{"id":"inspect-ac002",""" +
          """"content":"Inspect AC-002","status":"in_progress"}],"merge":false}}""" to "accepted",
        """{"jsonrpc":"2.0","id":"ask","method":"cursor/ask_question",""" +
          """"params":{"sessionId":"s","title":"full question"}}""" to "skipped",
        """{"jsonrpc":"2.0","id":"plan","method":"cursor/create_plan",""" +
          """"params":{"sessionId":"s","title":"full question"}}""" to "accepted",
        """{"jsonrpc":"2.0","id":"future","method":"future/blocker",""" +
          """"params":{"sessionId":"s","title":"full question"}}""" to "accepted",
      )
    for (provider in listOf(AcpProvider.Cursor, AcpProvider.Junie)) {
      for ((payload, outcome) in cases) {
        val harness = Harness(provider = provider)
        if (provider == AcpProvider.Cursor) {
          harness.open()
        } else {
          harness.session.start()
          harness.respond("""{"protocolVersion":1,"agentCapabilities":{}}""")
          harness.respond("""{"sessionId":"s"}""")
          harness.respond("""{"configOptions":[{"id":"brave_mode","currentValue":true}]}""")
        }
        harness.session.observe(payload)
        assertNull(harness.session.outcome())
        assertTrue(harness.events.none { it is CursorAcpEvent.Unsupported })
        assertContains(harness.frames.last { it[CursorAcpKeys.RESULT] != null }.toString(), outcome)
        harness.session.close()
      }
    }
  }

  @Test
  fun `a permission without an operator channel selects allow once and keeps running`() {
    for (provider in listOf(AcpProvider.Cursor, AcpProvider.Junie)) {
      val harness = Harness(bindOwner = false, provider = provider)
      if (provider == AcpProvider.Cursor) {
        harness.open()
      } else {
        harness.session.start()
        harness.respond("""{"protocolVersion":1,"agentCapabilities":{}}""")
        harness.respond("""{"sessionId":"s"}""")
        harness.respond("""{"configOptions":[{"id":"brave_mode","currentValue":true}]}""")
      }
      harness.permission("9", "shell", "git status")
      assertNull(harness.session.outcome())
      assertTrue(harness.owner.requests.isEmpty())
      val result = harness.frames.last { it[CursorAcpKeys.RESULT] != null }.toString()
      assertContains(result, "selected")
      assertContains(result, "once-shell")
      harness.session.close()
    }
  }

  @Test
  fun `an owned permission for a different session is cancelled without approval or termination`() {
    val harness = Harness()
    harness.open()
    harness.session.observe(
      """{"jsonrpc":"2.0","id":19,"method":"session/request_permission","params":{"sessionId":"stale",
"toolCall":{"toolCallId":"shell"},"options":[{"optionId":"yes","name":"Allow","kind":"allow_once"}]}}""",
    )
    assertNull(harness.session.outcome())
    assertTrue(harness.owner.requests.isEmpty())
    assertEquals(
      Json.parseToJsonElement("""{"outcome":{"outcome":"cancelled"}}"""),
      harness.frames.last()[CursorAcpKeys.RESULT],
    )
    harness.session.close()
  }

  @Test
  fun `missing one time option cancels while rule deliveries always select offered allow once`() {
    val harness = Harness()
    harness.open()
    harness.permission("5", "a", "command", allow = false)
    val request = harness.owner.requests.single().request
    assertEquals(
      ApprovalDeliveryState.UNCERTAIN,
      harness.session.deliver(
        request.copy(
          state = ApprovalState.ALLOWED,
          selectedOptionId = "remember-a",
        ),
      ),
    )
    assertEquals(ApprovalDeliveryState.WRITTEN, harness.session.deliver(request.copy(state = ApprovalState.CANCELLED)))
    assertContains(harness.frames.last().toString(), "cancelled")
    harness.session.close()
  }

  private class Harness(
    saved: String? = null,
    model: String? = null,
    dependency: CursorAcpMcpDependency = CursorAcpMcpDependency.ProjectUserOnly,
    provider: AcpProvider = AcpProvider.Cursor,
    bindOwner: Boolean = true,
  ) {
    val frames = mutableListOf<JsonObject>()
    val events = mutableListOf<CursorAcpEvent>()
    val cards = mutableListOf<ApprovalRequestRecord>()
    val owner = Owner()
    val session =
      CursorAcpSession(
        CursorAcpLaunch(
          "/checkout",
          "scoped prompt",
          model,
          "agent".takeIf { provider == AcpProvider.Cursor },
          saved,
          mcpDependency = dependency,
          provider = provider,
        ),
        Clock.fixed(Instant.EPOCH, ZoneOffset.UTC),
        {
          frames += Json.parseToJsonElement(it).jsonObject
          true
        },
        { true },
        events::add,
        cards::add,
        {},
      )

    init {
      if (bindOwner) {
        session.bind(owner)
        owner.deliver = session::deliver
      }
    }

    fun open() {
      session.start()
      respond(
        """{"protocolVersion":1,"agentCapabilities":{"loadSession":true},""" +
          """"authMethods":[{"id":"cursor_login"}]}""",
      )
      respond("{}")
      respond("""{"sessionId":"s","modes":{"availableModes":[{"id":"agent"}]}}""")
      respond("{}")
      assertNull(session.outcome())
      assertTrue(session.ready)
    }

    fun respond(result: String) {
      val id = frames.last()[CursorAcpKeys.ID]
      session.observe("""{"jsonrpc":"2.0","id":$id,"result":$result}""")
    }

    fun permission(
      id: String,
      tool: String,
      input: String,
      allow: Boolean = true,
    ) {
      val encoded = JsonPrimitive(input)
      val allowOption = if (allow) """{"optionId":"once-$tool","name":"Allow once","kind":"allow_once"},""" else ""
      session.observe(
        """{"jsonrpc":"2.0","id":$id,"method":"session/request_permission",""" +
          """"params":{"sessionId":"s","toolCall":{"toolCallId":"$tool",""" +
          """"title":"Full action","kind":"execute","rawInput":{"command":$encoded,""" +
          """"cwd":"/checkout"}},"options":[$allowOption{"optionId":"deny-$tool",""" +
          """"name":"Reject once","kind":"reject_once"},{"optionId":"remember-$tool",""" +
          """"name":"Allow always","kind":"allow_always"}]}}""",
      )
    }
  }

  private class Owner : ApprovalOwnerSession {
    override val key = ApprovalOwnerKey("repo", "run", "generation")
    val requests = mutableListOf<ApprovalRegistrationCommand>()
    lateinit var deliver: (ApprovalRequestRecord) -> ApprovalDeliveryState

    override fun register(command: ApprovalRegistrationCommand): ApprovalRegistrationResult {
      val request =
        command.request.copy(
          actionFingerprint = command.canonicalInput,
          authorityScope =
            command.canonicalInput?.let {
                value ->
              command.request.authorityScope?.copy(actionFingerprint = value)
            },
        )
      requests += command.copy(request = request)
      return ApprovalRegistrationResult.Registered(request)
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

    override fun release() {
      requests.forEach { deliver(it.request.copy(state = ApprovalState.PROCESS_ENDED)) }
    }
  }
}
