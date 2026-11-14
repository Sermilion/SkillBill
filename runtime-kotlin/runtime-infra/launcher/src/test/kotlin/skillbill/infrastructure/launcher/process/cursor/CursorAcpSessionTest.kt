package skillbill.infrastructure.launcher.process.cursor

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
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
  private val exactProvider =
    AcpProvider(
      "fixture",
      "Fixture",
      listOf("acp"),
      null,
      false,
      exactModels = true,
      loginCommand = "fixture auth login",
    )

  private val runtimeMcp =
    AcpStdioMcpServer(
      "skill-bill",
      "/opt/skill-bill/runtime-mcp",
      emptyList(),
      listOf(AcpEnvVariable("SKILL_BILL_REVIEW_DB", "/var/tmp/sona/runtime.db")),
    )

  @Test
  fun `session new and load repeat the complete MCP list and additional roots in ACP shape`() {
    listOf(null, "saved-1").forEach { saved ->
      val harness =
        Harness(saved = saved, provider = exactProvider, mcpServers = listOf(runtimeMcp), roots = listOf("/state"))
      harness.session.start()
      harness.respond(
        """{"protocolVersion":1,"agentCapabilities":{"loadSession":true,""" +
          """"sessionCapabilities":{"additionalDirectories":{}}}}""",
      )

      val params = harness.frames.last()[CursorAcpKeys.PARAMS]
      assertEquals(
        Json.parseToJsonElement(
          """{"cwd":"/checkout","mcpServers":[{"name":"skill-bill","command":"/opt/skill-bill/runtime-mcp",""" +
            """"args":[],"env":[{"name":"SKILL_BILL_REVIEW_DB","value":"/var/tmp/sona/runtime.db"}]}],""" +
            """"additionalDirectories":["/state"]""" + (saved?.let { ""","sessionId":"$it"""" } ?: "") + "}",
        ),
        params,
      )
      harness.session.close()
    }
  }

  @Test
  fun `required roots without the advertised capability stop setup before any session or prompt`() {
    val harness = Harness(provider = exactProvider, mcpServers = listOf(runtimeMcp), roots = listOf("/state"))
    harness.session.start()
    harness.respond("""{"protocolVersion":1,"agentCapabilities":{}}""")

    assertContains(requireNotNull(harness.session.outcome()?.failure), "additionalDirectories")
    assertEquals(listOf("initialize"), harness.frames.map { it.text(CursorAcpKeys.METHOD) })
  }

  @Test
  fun `exact provider selects only the exact advertised model and never aliases a variant`() {
    val exact = Harness(model = "swe-2-high", provider = exactProvider)
    exact.session.start()
    exact.respond("""{"protocolVersion":1,"agentCapabilities":{}}""")
    exact.respond("""{"sessionId":"s","models":{"currentModelId":"swe-2","availableModels":[{"modelId":"swe-2"}]}}""")
    assertContains(requireNotNull(exact.session.outcome()?.failure), "swe-2-high")
    assertTrue(exact.frames.none { it.text(CursorAcpKeys.METHOD) == "session/prompt" })

    val unconfirmed = Harness(model = "swe-2-high", provider = exactProvider)
    unconfirmed.session.start()
    unconfirmed.respond("""{"protocolVersion":1,"agentCapabilities":{}}""")
    unconfirmed.respond("""{"sessionId":"s"}""")
    assertContains(requireNotNull(unconfirmed.session.outcome()?.failure), "swe-2-high")
    assertTrue(unconfirmed.frames.none { it.text(CursorAcpKeys.METHOD) == "session/prompt" })

    val absent = Harness(model = "swe-2-high", provider = exactProvider, modelOnCommandLine = true)
    absent.session.start()
    absent.respond("""{"protocolVersion":1,"agentCapabilities":{}}""")
    absent.respond("""{"sessionId":"s"}""")
    assertTrue(absent.session.ready)
    absent.respond("""{"stopReason":"end_turn"}""")
    assertNull(absent.session.outcome()?.model)
    assertTrue(absent.reportedModels.isEmpty())
  }

  @Test
  fun `exact provider confirms a full model id from the session family thinking and speed options`() {
    val harness = Harness(model = "swe-2-high-lightning", provider = exactProvider, modelOnCommandLine = true)
    harness.session.start()
    harness.respond("""{"protocolVersion":1,"agentCapabilities":{}}""")
    harness.respond(devinSession(level = "high", speed = "fast"))

    assertTrue(harness.session.ready)
    assertTrue(harness.frames.none { it.text(CursorAcpKeys.METHOD) == "session/set_config_option" })
    harness.respond("""{"stopReason":"end_turn"}""")
    assertEquals("swe-2-high-lightning", harness.session.outcome()?.model)
    assertEquals(listOf("swe-2-high-lightning"), harness.reportedModels)
  }

  @Test
  fun `exact provider refuses a full model id the session configuration does not match`() {
    val harness = Harness(model = "swe-2-high-lightning", provider = exactProvider, modelOnCommandLine = true)
    harness.session.start()
    harness.respond("""{"protocolVersion":1,"agentCapabilities":{}}""")
    harness.respond(devinSession(level = "high", speed = "standard"))

    assertContains(requireNotNull(harness.session.outcome()?.failure), "swe-2-high-lightning")
    assertTrue(harness.frames.none { it.text(CursorAcpKeys.METHOD) == "session/prompt" })
    assertTrue(harness.reportedModels.isEmpty())
  }

  private fun devinSession(
    level: String,
    speed: String,
  ): String =
    """{"sessionId":"s","configOptions":[""" +
      """{"id":"model","category":"model","type":"select","currentValue":"swe-2-high","options":[""" +
      """{"value":"swe-2-high","name":"SWE-2"},{"value":"swe-2-flash-medium","name":"SWE-2 Flash"}]},""" +
      """{"id":"thought_level","category":"thought_level","type":"select","currentValue":"$level","options":[""" +
      """{"value":"medium"},{"value":"high"},{"value":"max"}]},""" +
      """{"id":"speed","category":"model_config","type":"select","currentValue":"$speed","options":[""" +
      """{"value":"standard"},{"value":"fast"}]}]}"""

  @Test
  fun `exact provider legacy set model acknowledgement without an echoed model reports no identity`() {
    val harness = legacyExactProvider()
    val selection = harness.frames.last()
    assertEquals("session/set_model", selection.text(CursorAcpKeys.METHOD))
    assertEquals("swe-2-high", selection.obj(CursorAcpKeys.PARAMS)?.text(CursorAcpKeys.MODEL_ID))
    harness.respond("{}")
    assertTrue(harness.session.ready)
    harness.respond("""{"stopReason":"end_turn"}""")
    assertNull(harness.session.outcome()?.model)
    assertTrue(harness.reportedModels.isEmpty())
    assertTrue(harness.records.any { it.contains("outcome=model_selected_unconfirmed reported_identity=unavailable") })
  }

  @Test
  fun `exact provider legacy set model reports the model the provider echoes back`() {
    val harness = legacyExactProvider()
    harness.respond("""{"modelId":"swe-2-high"}""")
    assertTrue(harness.session.ready)
    harness.respond("""{"stopReason":"end_turn"}""")
    assertEquals("swe-2-high", harness.session.outcome()?.model)
    assertEquals(listOf("swe-2-high"), harness.reportedModels)
  }

  @Test
  fun `exact provider reports the model a later config option update names`() {
    val harness = legacyExactProvider()
    harness.respond("""{"modelId":"swe-2-high"}""")
    harness.session.observe(
      """{"jsonrpc":"2.0","method":"session/update","params":{"sessionId":"s",""" +
        """"update":{"sessionUpdate":"config_option_update","configOptions":[""" +
        """{"id":"model","category":"model","currentValue":"swe-2"}]}}}""",
    )
    harness.respond("""{"stopReason":"end_turn"}""")
    assertEquals("swe-2", harness.session.outcome()?.model)
    assertTrue(harness.records.any { it.contains("outcome=model_changed_by_provider") })
  }

  @Test
  fun `cursor legacy set model still reports the selected model`() {
    val harness = Harness(model = "gpt-5")
    harness.session.start()
    harness.respond("""{"protocolVersion":1,"agentCapabilities":{},"authMethods":[{"id":"cursor_login"}]}""")
    harness.respond("{}")
    harness.respond(
      """{"sessionId":"s","modes":{"availableModes":[{"id":"agent"}]},""" +
        """"models":{"currentModelId":"auto","availableModels":[{"modelId":"auto"},{"modelId":"gpt-5"}]}}""",
    )
    assertEquals("session/set_model", harness.frames.last().text(CursorAcpKeys.METHOD))
    harness.respond("{}")
    harness.respond("{}")
    assertTrue(harness.session.ready)
    harness.respond("""{"stopReason":"end_turn"}""")
    assertEquals("gpt-5", harness.session.outcome()?.model)
  }

  private fun legacyExactProvider(): Harness {
    val harness = Harness(model = "swe-2-high", provider = exactProvider)
    harness.session.start()
    harness.respond("""{"protocolVersion":1,"agentCapabilities":{}}""")
    harness.respond(
      """{"sessionId":"s","models":{"currentModelId":"swe-2",""" +
        """"availableModels":[{"modelId":"swe-2"},{"modelId":"swe-2-high"}]}}""",
    )
    return harness
  }

  @Test
  fun `an unparseable session path is refused before any session or prompt`() {
    val harness = Harness(provider = exactProvider, roots = listOf("/state\u0000bad"))
    harness.session.start()
    harness.respond(
      """{"protocolVersion":1,"agentCapabilities":{"sessionCapabilities":{"additionalDirectories":{}}}}""",
    )

    assertContains(requireNotNull(harness.session.outcome()?.failure), "absolute paths")
    assertEquals(listOf("initialize"), harness.frames.map { it.text(CursorAcpKeys.METHOD) })
  }

  @Test
  fun `exact provider auth required names the CLI login recovery and sends no prompt`() {
    val harness = Harness(provider = exactProvider)
    harness.session.start()
    harness.respond("""{"protocolVersion":1,"agentCapabilities":{}}""")
    harness.reject(-32000)

    assertContains(requireNotNull(harness.session.outcome()?.failure), "fixture auth login")
    assertFalse(harness.session.ready)
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
  fun `Cursor publishing requests show full cards and wait for the operator choice`() {
    val harness = Harness(provider = AcpProvider.Cursor)
    harness.open()
    val command = "gh issue create --repo example/tools --body 'Include the exact payload'"
    val before = harness.frames.toList()

    harness.permission("17", "publish", command)

    val card = harness.cards.single()
    assertEquals(AcpProvider.Cursor.id, card.provider)
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
    for ((payload, outcome) in cases) {
      val harness = Harness(provider = AcpProvider.Cursor)
      harness.open()
      harness.session.observe(payload)
      assertNull(harness.session.outcome())
      assertTrue(harness.events.none { it is CursorAcpEvent.Unsupported })
      assertContains(harness.frames.last { it[CursorAcpKeys.RESULT] != null }.toString(), outcome)
      harness.session.close()
    }
  }

  @Test
  fun `a permission without an operator channel selects allow once and keeps running`() {
    val harness = Harness(bindOwner = false, provider = AcpProvider.Cursor)
    harness.open()
    harness.permission("9", "shell", "git status")
    assertNull(harness.session.outcome())
    assertTrue(harness.owner.requests.isEmpty())
    val result = harness.frames.last { it[CursorAcpKeys.RESULT] != null }.toString()
    assertContains(result, "selected")
    assertContains(result, "once-shell")
    harness.session.close()
  }

  @Test
  fun `execute and move permissions carry match facts`() {
    val checkout = Files.createTempDirectory("cursor-facts").toRealPath()
    try {
      Files.createDirectories(checkout.resolve("src"))
      Files.writeString(checkout.resolve("src/a.kt"), "x")
      val harness = Harness(checkout = checkout.toString())
      harness.open()
      harness.permission("90", "run", "git status")
      assertEquals(ApprovalMatchFacts.Command("git status"), harness.owner.requests.last().matchFacts)
      harness.session.observe(
        """{"jsonrpc":"2.0","id":91,"method":"session/request_permission",""" +
          """"params":{"sessionId":"s","toolCall":{"toolCallId":"mv","title":"Move","kind":"move",""" +
          """"rawInput":{"path":"src/b.kt"},"locations":[{"path":"src/a.kt"}]},""" +
          """"options":[{"optionId":"once-mv","name":"Allow once","kind":"allow_once"},""" +
          """{"optionId":"deny-mv","name":"Reject once","kind":"reject_once"}]}}""",
      )
      assertEquals(
        ApprovalMatchFacts.Paths(listOf("src/a.kt", "src/b.kt")),
        harness.owner.requests.last().matchFacts,
      )
      harness.session.observe(
        """{"jsonrpc":"2.0","id":92,"method":"session/request_permission",""" +
          """"params":{"sessionId":"s","toolCall":{"toolCallId":"mv-dest","title":"Move","kind":"move",""" +
          """"rawInput":{"path":"src/b.kt"}},""" +
          """"options":[{"optionId":"once-dest","name":"Allow once","kind":"allow_once"},""" +
          """{"optionId":"deny-dest","name":"Reject once","kind":"reject_once"}]}}""",
      )
      assertEquals(null, harness.owner.requests.last().matchFacts)
      harness.session.close()
    } finally {
      Files.walk(checkout).use { paths ->
        paths.sorted(Comparator.reverseOrder()).forEach(Files::deleteIfExists)
      }
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
  fun `OpenCode residual permission requests never park owned chats or unowned workers`() {
    listOf(false, true).forEach { owned ->
      val harness = Harness(provider = AcpProvider.OpenCode, bindOwner = owned)
      harness.session.start()
      harness.respond("""{"protocolVersion":1,"agentCapabilities":{}}""")
      harness.respond("""{"sessionId":"s"}""")
      harness.permission("90", "run", "git status")

      assertTrue(harness.cards.isEmpty())
      assertTrue(harness.owner.requests.isEmpty())
      assertEquals(
        Json.parseToJsonElement("""{"outcome":{"outcome":"selected","optionId":"once-run"}}"""),
        harness.frames.last()[CursorAcpKeys.RESULT],
      )
      assertTrue(harness.records.any { "permission_auto_allowed" in it })
      harness.session.observe(
        """{"jsonrpc":"2.0","id":91,"method":"session/request_permission","params":{"sessionId":"stale",""" +
          """"options":[{"optionId":"yes","name":"Allow","kind":"allow_once"}]}}""",
      )
      assertEquals(
        Json.parseToJsonElement("""{"outcome":{"outcome":"cancelled"}}"""),
        harness.frames.last()[CursorAcpKeys.RESULT],
      )
      harness.session.close()
    }
  }

  @Test
  fun `Devin new and resumed sessions select bypass before sending a prompt`() {
    listOf(null, "s").forEach { saved ->
      val harness = Harness(saved = saved, provider = AcpProvider.Devin)
      harness.session.start()
      harness.respond("""{"protocolVersion":1,"agentCapabilities":{"loadSession":true}}""")
      harness.respond(
        """{"sessionId":"s","configOptions":[{"id":"mode","category":"mode","currentValue":"accept-edits",""" +
          """"options":[{"value":"accept-edits"},{"value":"bypass"}]}]}""",
      )
      val selection = harness.frames.last()
      assertEquals("session/set_config_option", selection.text(CursorAcpKeys.METHOD))
      assertEquals("bypass", selection.obj(CursorAcpKeys.PARAMS)?.text(CursorAcpKeys.VALUE))
      assertFalse(harness.session.ready)
      assertTrue(harness.frames.none { it.text(CursorAcpKeys.METHOD) == "session/prompt" })

      harness.respond("""{"configOptions":[{"id":"mode","currentValue":"bypass"}]}""")
      assertTrue(harness.session.ready)
      assertEquals("session/prompt", harness.frames.last().text(CursorAcpKeys.METHOD))
      assertTrue(harness.cards.isEmpty())
      harness.session.close()
    }
  }

  @Test
  fun `Devin never sends a prompt when bypass is unavailable or refused`() {
    listOf(false, true).forEach { offered ->
      val harness = Harness(provider = AcpProvider.Devin)
      harness.session.start()
      harness.respond("""{"protocolVersion":1,"agentCapabilities":{}}""")
      val mode = if (offered) "bypass" else "accept-edits"
      harness.respond("""{"sessionId":"s","modes":{"availableModes":[{"id":"$mode"}]}}""")
      if (offered) {
        assertEquals("session/set_mode", harness.frames.last().text(CursorAcpKeys.METHOD))
        assertEquals("bypass", harness.frames.last().obj(CursorAcpKeys.PARAMS)?.text(CursorAcpKeys.MODE_ID))
        harness.reject(-32602)
      }
      assertNotNull(harness.session.outcome()?.failure)
      assertTrue(harness.frames.none { it.text(CursorAcpKeys.METHOD) == "session/prompt" })
    }
  }

  @Test
  fun `Devin steering sends a second prompt on the running turn and resolves its delivery`() {
    val harness = devinTurn()

    assertEquals(CursorAcpSteerOutcome.Pending, harness.session.steer("d1", "also adjust the plan", emptyList()))
    val steer = harness.frames.last()
    assertEquals("session/prompt", steer.text(CursorAcpKeys.METHOD))
    assertContains(steer.toString(), "also adjust the plan")
    val steerId = steer[CursorAcpKeys.ID]

    harness.session.observe("""{"jsonrpc":"2.0","id":$steerId,"result":{"stopReason":"end_turn"}}""")
    assertEquals(listOf(SteeringResponse("d1", true, null)), harness.steeringResponses)

    harness.respondPrompt("""{"stopReason":"end_turn"}""")
    assertNull(harness.session.outcome()?.failure)
    assertEquals(1, harness.steeringResponses.size)
  }

  @Test
  fun `Devin steering deliveries resolve with the turn even without their own response`() {
    val harness = devinTurn()
    assertEquals(CursorAcpSteerOutcome.Pending, harness.session.steer("d1", "x", emptyList()))

    harness.respondPrompt("""{"stopReason":"end_turn"}""")

    assertNull(harness.session.outcome()?.failure)
    assertEquals(listOf(SteeringResponse("d1", true, null)), harness.steeringResponses)
  }

  @Test
  fun `a Devin follow-up answer arriving after the turn response settles only once`() {
    val harness = devinTurn()
    assertEquals(CursorAcpSteerOutcome.Pending, harness.session.steer("d1", "x", emptyList()))
    val steerId = harness.frames.last()[CursorAcpKeys.ID]

    harness.respondPrompt("""{"stopReason":"end_turn"}""")
    harness.session.observe("""{"jsonrpc":"2.0","id":$steerId,"result":{"stopReason":"end_turn"}}""")

    assertNull(harness.session.outcome()?.failure)
    assertEquals(listOf(SteeringResponse("d1", true, null)), harness.steeringResponses)
  }

  @Test
  fun `Devin steering refuses outside a live prompt and for providers without steering`() {
    val idle = Harness(provider = AcpProvider.Devin)
    assertEquals(CursorAcpSteerOutcome.Inactive, idle.session.steer("d", "x", emptyList()))

    val cursor = Harness(provider = AcpProvider.Cursor)
    cursor.open()
    assertEquals(CursorAcpSteerOutcome.Inactive, cursor.session.steer("d", "x", emptyList()))
    val prompts = cursor.frames.filter { it.text(CursorAcpKeys.METHOD) == "session/prompt" }
    assertTrue(prompts.none { it.toString().contains("\"x\"") })
    cursor.session.close()

    val ended = devinTurn()
    ended.respondPrompt("""{"stopReason":"end_turn"}""")
    assertEquals(CursorAcpSteerOutcome.Inactive, ended.session.steer("d", "x", emptyList()))
  }

  @Test
  fun `Devin steering reports provider rejection and turn cancellation`() {
    val rejected = devinTurn()
    assertEquals(CursorAcpSteerOutcome.Pending, rejected.session.steer("d1", "x", emptyList()))
    val steerId = rejected.frames.last()[CursorAcpKeys.ID]
    rejected.session.observe(
      """{"jsonrpc":"2.0","id":$steerId,"error":{"code":-32602,"message":"bad prompt"}}""",
    )
    assertEquals(listOf(SteeringResponse("d1", false, "bad prompt")), rejected.steeringResponses)
    assertNull(rejected.session.outcome())

    val cancelled = devinTurn()
    assertEquals(CursorAcpSteerOutcome.Pending, cancelled.session.steer("d2", "x", emptyList()))
    cancelled.session.stop("operator stopped")
    assertEquals(listOf(SteeringResponse("d2", false, "operator stopped")), cancelled.steeringResponses)
  }

  @Test
  fun `a failed Devin steering write drops the reservation without failing the turn`() {
    val harness = devinTurn()
    harness.writesOpen = false

    assertEquals(CursorAcpSteerOutcome.WriteFailed, harness.session.steer("d1", "x", emptyList()))

    harness.writesOpen = true
    harness.respondPrompt("""{"stopReason":"end_turn"}""")
    assertNull(harness.session.outcome()?.failure)
    assertTrue(harness.steeringResponses.isEmpty())
  }

  private fun devinTurn(): Harness {
    val harness = Harness(provider = AcpProvider.Devin)
    harness.session.start()
    harness.respond("""{"protocolVersion":1,"agentCapabilities":{"loadSession":true}}""")
    harness.respond(
      """{"sessionId":"s","configOptions":[{"id":"mode","category":"mode","currentValue":"accept-edits",""" +
        """"options":[{"value":"accept-edits"},{"value":"bypass"}]}]}""",
    )
    harness.respond("""{"configOptions":[{"id":"mode","currentValue":"bypass"}]}""")
    assertTrue(harness.session.ready)
    assertEquals("session/prompt", harness.frames.last().text(CursorAcpKeys.METHOD))
    harness.promptId = harness.frames.last()[CursorAcpKeys.ID]
    return harness
  }

  @Test
  fun `Devin refuses an unimplemented blocking method with its request id`() {
    val harness = Harness(provider = AcpProvider.Devin)
    harness.session.start()
    harness.respond("""{"protocolVersion":1,"agentCapabilities":{}}""")
    harness.respond("""{"sessionId":"s","modes":{"availableModes":[{"id":"bypass"}]}}""")
    harness.respond("{}")

    harness.session.observe(
      """{"jsonrpc":"2.0","id":73,"method":"fs/read_text_file","params":{"path":"secret.txt"}}""",
    )

    val response = harness.frames.last()
    assertEquals(JsonPrimitive(73), response[CursorAcpKeys.ID])
    assertEquals(
      -32601,
      response.obj(CursorAcpKeys.ERROR)?.get(CursorAcpKeys.CODE)?.let {
        (it as? JsonPrimitive)?.content?.toIntOrNull()
      },
    )
    assertTrue(harness.events.any { it is CursorAcpEvent.Unsupported })
    assertTrue(harness.records.any { "outcome=unsupported_request" in it })
  }

  @Test
  fun `OpenCode authentication rejection names its login command before prompt delivery`() {
    val harness = Harness(provider = AcpProvider.OpenCode)
    harness.session.start()
    harness.respond("""{"protocolVersion":1,"agentCapabilities":{}}""")
    harness.reject(CursorAcpKeys.AUTH_REQUIRED_CODE)

    assertContains(requireNotNull(harness.session.outcome()?.failure), "opencode auth login")
    assertTrue(harness.frames.none { it.text(CursorAcpKeys.METHOD) == "session/prompt" })
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

  private data class SteeringResponse(val deliveryId: String, val accepted: Boolean, val detail: String?)

  private class Harness(
    saved: String? = null,
    model: String? = null,
    dependency: CursorAcpMcpDependency = CursorAcpMcpDependency.ProjectUserOnly,
    provider: AcpProvider = AcpProvider.Cursor,
    checkout: String = "/checkout",
    bindOwner: Boolean = true,
    mcpServers: List<AcpStdioMcpServer> = emptyList(),
    roots: List<String> = emptyList(),
    modelOnCommandLine: Boolean = false,
  ) {
    val frames = mutableListOf<JsonObject>()
    val events = mutableListOf<CursorAcpEvent>()
    val cards = mutableListOf<ApprovalRequestRecord>()
    val reportedModels = mutableListOf<String>()
    val records = mutableListOf<String>()
    val steeringResponses = mutableListOf<SteeringResponse>()
    var writesOpen = true
    var promptId: JsonElement? = null
    val owner = Owner()
    val session =
      CursorAcpSession(
        CursorAcpLaunch(
          checkout,
          "scoped prompt",
          model,
          "agent".takeIf { provider == AcpProvider.Cursor },
          saved,
          mcpDependency = dependency,
          provider = provider,
          mcpServers = mcpServers,
          additionalDirectories = roots,
          modelOnCommandLine = modelOnCommandLine,
        ),
        Clock.fixed(Instant.EPOCH, ZoneOffset.UTC),
        {
          if (writesOpen) frames += Json.parseToJsonElement(it).jsonObject
          writesOpen
        },
        { true },
        events::add,
        cards::add,
        records::add,
        onModel = { reportedModels += it },
        onSteeringResponse = { id, accepted, detail -> steeringResponses += SteeringResponse(id, accepted, detail) },
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

    fun reject(code: Int) {
      val id = frames.last()[CursorAcpKeys.ID]
      session.observe("""{"jsonrpc":"2.0","id":$id,"error":{"code":$code,"message":"provider detail"}}""")
    }

    fun respond(result: String) {
      val id = frames.last()[CursorAcpKeys.ID]
      session.observe("""{"jsonrpc":"2.0","id":$id,"result":$result}""")
    }

    fun respondPrompt(result: String) {
      session.observe("""{"jsonrpc":"2.0","id":$promptId,"result":$result}""")
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
