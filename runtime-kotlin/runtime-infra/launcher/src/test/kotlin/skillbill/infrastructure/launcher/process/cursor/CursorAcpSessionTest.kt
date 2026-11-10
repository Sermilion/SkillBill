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
  fun `OpenCode carries runtime MCP and acknowledges refreshed model effort and mode before prompt`() {
    val server = CursorAcpMcpServer("skill-bill", "/runtime-mcp", emptyList(), mapOf("DB" to "/db.sqlite"))
    val harness =
      Harness(
        model = "anthropic/claude-sonnet-4-5",
        provider = AcpProvider.OpenCode,
        effort = "high",
        mcpServers = listOf(server),
      )
    harness.session.start()
    harness.respond("""{"protocolVersion":1,"agentCapabilities":{}}""")
    harness.respond(
      """{"sessionId":"s","configOptions":[{"id":"model","category":"model",""" +
        """"currentValue":"anthropic/old","options":[{"value":"anthropic/claude-sonnet-4-5"}]}]}""",
    )
    assertEquals(
      JsonPrimitive("model"),
      harness.frames.last()[CursorAcpKeys.PARAMS]?.jsonObject?.get(CursorAcpKeys.CONFIG_ID),
    )
    assertEquals(
      JsonPrimitive("anthropic/claude-sonnet-4-5"),
      harness.frames.last()[CursorAcpKeys.PARAMS]?.jsonObject?.get(CursorAcpKeys.VALUE),
    )
    assertContains(harness.frames[1].toString(), "\"name\":\"skill-bill\"")
    assertContains(harness.frames[1].toString(), "\"command\":\"/runtime-mcp\"")
    assertContains(harness.frames[1].toString(), "\"args\":[]")
    assertContains(harness.frames[1].toString(), "\"env\":[{\"name\":\"DB\",\"value\":\"/db.sqlite\"}]")

    harness.respond(
      """{"configOptions":[{"id":"model","category":"model",""" +
        """"currentValue":"anthropic/claude-sonnet-4-5",""" +
        """"options":[{"value":"anthropic/claude-sonnet-4-5"}]},{"id":"effort",""" +
        """"category":"thought_level","currentValue":"low","options":[{"value":"low"},""" +
        """{"value":"high"}]},{"id":"mode","category":"mode","currentValue":"primary",""" +
        """"options":[{"value":"primary"}]}]}""",
    )
    assertEquals(
      JsonPrimitive("effort"),
      harness.frames.last()[CursorAcpKeys.PARAMS]?.jsonObject?.get(CursorAcpKeys.CONFIG_ID),
    )
    harness.respond(
      """{"configOptions":[{"id":"effort","category":"thought_level","currentValue":"high",""" +
        """"options":[{"value":"low"},{"value":"high"}]},{"id":"mode","category":"mode",""" +
        """"currentValue":"primary","options":[{"value":"primary"}]}]}""",
    )
    assertEquals(
      JsonPrimitive("mode"),
      harness.frames.last()[CursorAcpKeys.PARAMS]?.jsonObject?.get(CursorAcpKeys.CONFIG_ID),
    )
    harness.respond(
      """{"configOptions":[{"id":"mode","category":"mode","currentValue":"primary",""" +
        """"options":[{"value":"primary"}]}]}""",
    )
    assertEquals(JsonPrimitive("session/prompt"), harness.frames.last()[CursorAcpKeys.METHOD])
    val promptId = harness.frames.last()[CursorAcpKeys.ID]
    harness.session.observe(
      """{"jsonrpc":"2.0","method":"session/update","params":{"sessionId":"s",""" +
        """"update":{"sessionUpdate":"agent_message_chunk","content":{"type":"text",""" +
        """"text":"OpenCode answer"}}}}""",
    )
    harness.session.observe("""{"jsonrpc":"2.0","id":$promptId,"result":{"stopReason":"end_turn"}}""")
    assertEquals("OpenCode answer", harness.session.outcome()?.text)
    harness.session.close()
  }

  @Test
  fun `OpenCode permission cards allow once reject and refuse remembered grants`() {
    val harness = Harness(provider = AcpProvider.OpenCode)
    harness.session.start()
    harness.respond("""{"protocolVersion":1,"agentCapabilities":{}}""")
    harness.respond(
      """{"sessionId":"s","configOptions":[{"id":"mode","category":"mode",""" +
        """"currentValue":"primary","options":[{"value":"primary"}]}]}""",
    )
    harness.respond(
      """{"configOptions":[{"id":"mode","category":"mode","currentValue":"primary"}]}""",
    )
    assertTrue(harness.session.ready)
    harness.permission("17", "write", "git status")
    assertEquals("opencode", harness.owner.requests.single().request.provider)
    assertEquals(
      ApprovalDeliveryState.WRITTEN,
      harness.session.deliver(
        harness.owner.requests.single().request.copy(
          state = ApprovalState.ALLOWED,
          selectedOptionId = "once-write",
        ),
      ),
    )
    assertContains(harness.frames.last().toString(), "once-write")
    harness.permission("18", "read", "cat file")
    val remember = harness.owner.requests.last().request
    assertEquals(
      ApprovalDeliveryState.UNCERTAIN,
      harness.session.deliver(remember.copy(state = ApprovalState.ALLOWED, selectedOptionId = "remember-read")),
    )
    assertEquals(ApprovalDeliveryState.WRITTEN, harness.session.deliver(remember.copy(state = ApprovalState.CANCELLED)))
    assertContains(harness.frames.last().toString(), "cancelled")
    harness.permission("19", "delete", "rm file", allow = false)
    val rejection = harness.owner.requests.last().request
    assertEquals(
      ApprovalDeliveryState.WRITTEN,
      harness.session.deliver(rejection.copy(state = ApprovalState.DENIED, selectedOptionId = "deny-delete")),
    )
    assertContains(harness.frames.last().toString(), "deny-delete")
    harness.session.close()
  }

  @Test
  fun `OpenCode refuses unavailable effort and unsupported load without starting a replacement session`() {
    val effortHarness = Harness(provider = AcpProvider.OpenCode, effort = "extreme")
    effortHarness.session.start()
    effortHarness.respond("""{"protocolVersion":1,"agentCapabilities":{}}""")
    effortHarness.respond(
      """{"sessionId":"s","configOptions":[{"id":"effort","category":"thought_level",""" +
        """"currentValue":"low","options":[{"value":"low"}]}]}""",
    )
    assertNotNull(effortHarness.session.outcome()?.failure)
    assertFalse(effortHarness.frames.any { it.text(CursorAcpKeys.METHOD) == CursorAcpKeys.SESSION_PROMPT })

    val loadHarness = Harness(saved = "saved-session", provider = AcpProvider.OpenCode)
    loadHarness.session.start()
    loadHarness.respond("""{"protocolVersion":1,"agentCapabilities":{}}""")
    assertNotNull(loadHarness.session.outcome()?.failure)
    assertFalse(loadHarness.frames.any { it.text(CursorAcpKeys.METHOD) == CursorAcpKeys.SESSION_NEW })
    assertFalse(loadHarness.frames.any { it.text(CursorAcpKeys.METHOD) == CursorAcpKeys.SESSION_LOAD })
  }

  @Test
  fun `OpenCode retains typed provider errors and never prompts after acquisition failure`() {
    val harness = Harness(provider = AcpProvider.OpenCode)
    harness.session.start()
    harness.respond("""{"protocolVersion":1,"agentCapabilities":{}}""")
    val acquisitionId = harness.frames.last()[CursorAcpKeys.ID]
    harness.session.observe(
      """{"jsonrpc":"2.0","id":$acquisitionId,"error":{"code":-32001,""" +
        """"message":"Authentication required","data":{"reason":"missing_api_key"}}}""",
    )

    assertContains(harness.session.outcome()?.failure.orEmpty(), "-32001")
    assertContains(harness.session.outcome()?.failure.orEmpty(), "missing_api_key")
    assertFalse(harness.frames.any { it.text(CursorAcpKeys.METHOD) == CursorAcpKeys.SESSION_PROMPT })
  }

  @Test
  fun `OpenCode load keeps the supplied handle ignores replay and cancels permissions without an owner`() {
    val harness = Harness(saved = "saved-session", provider = AcpProvider.OpenCode, bindOwner = false)
    harness.session.start()
    harness.respond("""{"protocolVersion":1,"agentCapabilities":{"loadSession":true}}""")
    assertEquals(CursorAcpKeys.SESSION_LOAD, harness.frames.last().text(CursorAcpKeys.METHOD))
    assertEquals(
      JsonPrimitive("saved-session"),
      harness.frames.last()[CursorAcpKeys.PARAMS]?.jsonObject?.get(CursorAcpKeys.SESSION_ID),
    )
    harness.respond(
      """{"sessionId":"saved-session","configOptions":[{"id":"mode","category":"mode",""" +
        """"currentValue":"primary","options":[{"value":"primary"}]}]}""",
    )
    harness.session.observe(
      """{"jsonrpc":"2.0","method":"session/update","params":{"sessionId":"saved-session",""" +
        """"update":{"sessionUpdate":"agent_message_chunk","content":{"type":"text",""" +
        """"text":"replayed"}}}}""",
    )
    harness.respond(
      """{"configOptions":[{"id":"mode","category":"mode","currentValue":"primary",""" +
        """"options":[{"value":"primary"}]}]}""",
    )
    val promptId = harness.frames.last()[CursorAcpKeys.ID]
    harness.permission("17", "write", "git status")
    assertEquals(
      Json.parseToJsonElement("""{"outcome":{"outcome":"cancelled"}}"""),
      harness.frames.last()[CursorAcpKeys.RESULT],
    )
    harness.session.observe(
      """{"jsonrpc":"2.0","method":"session/update","params":{"sessionId":"saved-session",""" +
        """"update":{"sessionUpdate":"agent_message_chunk","content":{"type":"text",""" +
        """"text":"live answer"}}}}""",
    )
    harness.session.observe("""{"jsonrpc":"2.0","id":$promptId,"result":{"stopReason":"end_turn"}}""")

    assertEquals("live answer", harness.session.outcome()?.text)
    assertFalse(harness.frames.any { it.text(CursorAcpKeys.METHOD) == CursorAcpKeys.SESSION_NEW })
    harness.session.close()
  }

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
  fun `a CLI model slug selects the advertised variant of its base model before prompting`() {
    val harness = Harness(model = "composer-2.5")
    harness.session.start()
    harness.respond("""{"protocolVersion":1,"agentCapabilities":{},"authMethods":[{"id":"cursor_login"}]}""")
    harness.respond("{}")
    harness.respond(
      """{"sessionId":"s","configOptions":[{"id":"model","category":"model",""" +
        """"currentValue":"gpt-5.4-nano[reasoning=medium]","options":[""" +
        """{"value":"gpt-5.4-nano[reasoning=medium]"},{"value":"composer-2.5[fast=true]"}]}],""" +
        """"modes":{"availableModes":[{"id":"agent"}]}}""",
    )

    assertTrue(harness.reportedModels.isEmpty())
    val selection = harness.frames.last()
    assertEquals(JsonPrimitive("session/set_config_option"), selection[CursorAcpKeys.METHOD])
    assertContains(selection.toString(), "\"value\":\"composer-2.5[fast=true]\"")
    harness.respond(
      """{"configOptions":[{"id":"model","category":"model","currentValue":"composer-2.5[fast=true]"}]}""",
    )
    harness.respond("{}")
    assertEquals(JsonPrimitive("session/prompt"), harness.frames.last()[CursorAcpKeys.METHOD])
    harness.respond("""{"stopReason":"end_turn"}""")
    assertEquals(listOf("composer-2.5[fast=true]"), harness.reportedModels)
    assertEquals("composer-2.5[fast=true]", harness.session.outcome()?.model)
    harness.session.close()
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
    private val saved: String? = null,
    model: String? = null,
    dependency: CursorAcpMcpDependency = CursorAcpMcpDependency.ProjectUserOnly,
    provider: AcpProvider = AcpProvider.Cursor,
    bindOwner: Boolean = true,
    effort: String? = null,
    mcpServers: List<CursorAcpMcpServer> = emptyList(),
  ) {
    val frames = mutableListOf<JsonObject>()
    val events = mutableListOf<CursorAcpEvent>()
    val cards = mutableListOf<ApprovalRequestRecord>()
    val reportedModels = mutableListOf<String>()
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
          effort = effort,
          mcpServers = mcpServers,
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
        onModel = { reportedModels += it },
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
      val sessionId = JsonPrimitive(saved ?: "s")
      val allowOption = if (allow) """{"optionId":"once-$tool","name":"Allow once","kind":"allow_once"},""" else ""
      session.observe(
        """{"jsonrpc":"2.0","id":$id,"method":"session/request_permission",""" +
          """"params":{"sessionId":$sessionId,"toolCall":{"toolCallId":"$tool",""" +
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
