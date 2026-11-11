package skillbill.infrastructure.launcher.process.codex

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import skillbill.approval.model.ApprovalProviderDisposition
import skillbill.contracts.approval.ApprovalProviderRequestId
import skillbill.contracts.approval.ApprovalState
import skillbill.infrastructure.launcher.process.interaction.AgentRunSessionOutcome
import skillbill.ports.agentrun.model.AgentRunModelObserver
import skillbill.ports.agentrun.model.AgentRunOutputSink
import skillbill.ports.approval.model.ApprovalDecisionResult
import skillbill.ports.approval.model.ApprovalDeliveryState
import skillbill.ports.approval.model.ApprovalOwnerKey
import skillbill.ports.approval.model.ApprovalOwnerSession
import skillbill.ports.approval.model.ApprovalPendingWait
import skillbill.ports.approval.model.ApprovalProviderClearance
import skillbill.ports.approval.model.ApprovalRegistrationCommand
import skillbill.ports.approval.model.ApprovalRegistrationResult
import skillbill.ports.diagnostics.NoopRuntimeDiagnostics
import java.io.ByteArrayOutputStream
import java.io.InputStream
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

class CodexSessionChannelTest {
  @Test
  fun `model is reported from the provider handshake before turn completion`() {
    val models = mutableListOf<String?>()
    val channel = channel(observer = AgentRunModelObserver(models::add))
    try {
      channel.attach(SessionProcess())
      assertEquals(emptyList(), models)
      channel.observe("""{"id":1,"result":{"userAgent":"codex_cli_rs/9.0.0 (test)"}}""" + "\n")
      assertEquals(emptyList(), models)
      channel.observe(threadResponse())
      assertEquals<List<String?>>(listOf("worker-model"), models)
      assertNull(channel.outcome())
    } finally {
      channel.finish()
    }
  }

  @Test
  fun `stale turn failures in the resumed thread do not poison the active turn`() {
    val channel = channel(savedThread = "thread-1")
    try {
      channel.attach(SessionProcess())
      channel.observe("""{"id":1,"result":{"userAgent":"codex_cli_rs/9.0.0 (test)"}}""" + "\n")
      channel.observe(threadResponse())
      channel.observe("""{"id":3,"result":{"turn":{"id":"turn-1","status":"inProgress"}}}""" + "\n")
      channel.observe(
        """{"method":"item/completed","params":{"threadId":"thread-1","turnId":"old-turn",""" +
          """"item":{"id":"old-item","type":"commandExecution","status":"declined"}}}""" + "\n",
      )
      channel.observe(item("Final answer"))
      channel.observe(terminal())

      assertTrue(assertNotNull(channel.outcome()).completed)
      assertNull(channel.outcome()?.failure)
    } finally {
      channel.finish()
    }
  }

  @Test
  fun `handshake forwards settings and early correlated completion produces only final assistant prose`() {
    val process = SessionProcess()
    val channel = channel()
    try {
      assertTrue(channel.attach(process))
      channel.observe("""{"id":1,"result":{"userAgent":"codex_cli_rs/0.166.0 (test)"}}""" + "\n")
      channel.observe(threadResponse())
      channel.observe(item("Final ", delta = true))
      channel.observe(item("Final answer"))
      channel.observe(terminal(thread = "other"))
      assertNull(channel.outcome())
      channel.observe("""{"id":3,"result":{"turn":{"id":"turn-1","status":"inProgress"}}}""" + "\n")
      assertNull(channel.outcome())
      channel.observe(terminal())

      val outcome = assertNotNull(channel.outcome())
      assertTrue(outcome.completed)
      assertEquals("Final answer", outcome.text)
      assertEquals("thread-1", outcome.threadId)
      assertEquals("worker-model", outcome.model)
      val frames =
        process.stdin.toString().lineSequence().filter(String::isNotBlank)
          .map { Json.parseToJsonElement(it).jsonObject }.toList()
      assertEquals(
        listOf("initialize", "initialized", "thread/start", "turn/start"),
        frames.map { it.getValue("method").jsonPrimitive.content },
      )
      val thread = frames[2].getValue("params").jsonObject
      val turn = frames[3].getValue("params").jsonObject
      assertEquals("/checkout", thread.getValue("cwd").jsonPrimitive.content)
      assertContains(thread.getValue("runtimeWorkspaceRoots").toString(), "/qa")
      assertEquals("worker-model", turn.getValue("model").jsonPrimitive.content)
      assertEquals("high", turn.getValue("effort").jsonPrimitive.content)
      assertContains(turn.getValue("input").toString(), "scoped briefing")
      assertEquals("never", turn.getValue("approvalPolicy").jsonPrimitive.content)
      val sandbox = turn.getValue("sandboxPolicy").jsonObject
      assertEquals("dangerFullAccess", sandbox.getValue("type").jsonPrimitive.content)
      assertFalse("writableRoots" in sandbox)
    } finally {
      channel.finish()
    }
  }

  @Test
  fun `turns require the provider to acknowledge bypass instead of retaining a restricted policy`() {
    for (policy in listOf("dangerFullAccess", "readOnly", "workspaceWrite")) {
      val process = SessionProcess()
      val channel = channel()
      try {
        assertTrue(channel.attach(process))
        channel.observe("""{"id":1,"result":{"userAgent":"codex_cli_rs/0.166.0 (test)"}}""" + "\n")
        channel.observe(
          """{"id":2,"result":{"thread":{"id":"thread-1"},"model":"worker-model",""" +
            """"cwd":"/checkout","sandbox":{"type":"$policy"}}}""" + "\n",
        )
        val frames =
          process.stdin.toString().lineSequence().filter(String::isNotBlank)
            .map { Json.parseToJsonElement(it).jsonObject }.toList()
        val thread = frames[2].getValue("params").jsonObject
        assertEquals("danger-full-access", thread.getValue("sandbox").jsonPrimitive.content)
        assertContains(thread.getValue("runtimeWorkspaceRoots").toString(), "/qa")
        val turn = frames.singleOrNull { it.getValue("method").jsonPrimitive.content == "turn/start" }
        assertEquals(policy == "dangerFullAccess", turn != null)
        if (turn != null) {
          val sandbox = turn.getValue("params").jsonObject.getValue("sandboxPolicy").jsonObject
          assertEquals("dangerFullAccess", sandbox.getValue("type").jsonPrimitive.content)
          assertFalse("writableRoots" in sandbox)
          assertNull(channel.outcome())
        } else {
          assertFalse(assertNotNull(channel.outcome()).completed)
        }
      } finally {
        channel.finish()
      }
    }
  }

  @Test
  fun `changed resume identity never receives a mutating turn`() {
    val process = SessionProcess()
    val channel = channel(savedThread = "saved-thread")
    try {
      channel.attach(process)
      channel.observe("""{"id":1,"result":{"userAgent":"codex_cli_rs/9.0.0 (test)"}}""" + "\n")
      channel.observe(threadResponse())
      assertFalse(assertNotNull(channel.outcome()).completed)
      assertFalse(process.stdin.toString().contains("turn/start"))
      assertFalse(process.stdin.toString().contains("thread/start"))
    } finally {
      channel.finish()
    }
  }

  @Test
  fun `only a matching terminal failure permits output limit continuation`() {
    val channel = channel()
    try {
      channel.attach(SessionProcess())
      channel.observe("""{"id":1,"result":{"userAgent":"codex_cli_rs/0.166.0 (test)"}}""" + "\n")
      channel.observe(threadResponse())
      channel.observe("""{"id":3,"result":{"turn":{"id":"turn-1","status":"inProgress"}}}""" + "\n")
      channel.observe(terminal(status = "failed", failure = CODEX_OUTPUT_EXHAUSTED))

      val outcome = assertNotNull(channel.outcome())
      assertFalse(outcome.completed)
      assertTrue(outcome.canContinue)
      assertEquals(CODEX_OUTPUT_EXHAUSTED, outcome.failure)
    } finally {
      channel.finish()
    }
  }

  @Test
  fun `an approved command that exits non-zero is a tool result and the turn still completes`() {
    val outcome =
      approvedCommandTurn(
        ApprovalState.ALLOWED,
        """"status":"failed","exitCode":1,""" +
          """"aggregatedOutput":"CONFLICT (content): Merge conflict in App.kt",""",
      )

    assertTrue(outcome.completed, outcome.failure)
    assertNull(outcome.failure)
    assertEquals("Final answer", outcome.text)
  }

  @Test
  fun `a declined command still fails the turn`() {
    val outcome = approvedCommandTurn(ApprovalState.DENIED, """"status":"declined",""")

    assertFalse(outcome.completed)
    assertContains(outcome.failure.orEmpty(), "declined")
  }

  private fun approvedCommandTurn(
    decision: ApprovalState,
    completion: String,
  ): AgentRunSessionOutcome {
    val process = SessionProcess()
    val channel = channel()
    val owner = Owner()
    channel.bind(owner)
    try {
      channel.attach(process)
      channel.observe("""{"id":1,"result":{"userAgent":"codex_cli_rs/0.166.0 (test)"}}""" + "\n")
      channel.observe(threadResponse())
      channel.observe("""{"id":3,"result":{"turn":{"id":"turn-1","status":"inProgress"}}}""" + "\n")
      channel.observe(
        """{"method":"item/started","params":{"threadId":"thread-1","turnId":"turn-1",""" +
          """"item":{"id":"cmd","type":"commandExecution","command":"git merge origin/main",""" +
          """"cwd":"/checkout","status":"inProgress"}}}""" + "\n",
      )
      channel.observe(
        """{"id":7,"method":"item/commandExecution/requestApproval","params":{"threadId":"thread-1",""" +
          """"turnId":"turn-1","itemId":"cmd","command":"git merge origin/main","cwd":"/checkout",""" +
          """"commandActions":[{"type":"unknown","command":"git merge origin/main"}],""" +
          """"availableDecisions":null}}""" + "\n",
      )
      val request = owner.requests.single().request
      assertEquals(ApprovalDeliveryState.WRITTEN, channel.deliver(request.copy(state = decision)))
      channel.observe(
        """{"method":"item/completed","params":{"threadId":"thread-1","turnId":"turn-1",""" +
          """"item":{"id":"cmd","type":"commandExecution","command":"git merge origin/main",""" +
          """"cwd":"/checkout",$completion"durationMs":5}}}""" + "\n",
      )
      channel.observe(item("Final answer"))
      channel.observe(terminal())
      return assertNotNull(channel.outcome())
    } finally {
      channel.finish()
    }
  }

  private fun channel(
    savedThread: String? = null,
    observer: AgentRunModelObserver? = null,
  ) = CodexSessionChannel(
    CodexSessionLaunch(
      "scoped briefing",
      "/checkout",
      "worker-model",
      "high",
      savedThread,
      AgentRunOutputSink.NONE,
      listOf("/qa"),
      modelObserver = observer,
    ),
    Clock.fixed(Instant.EPOCH, ZoneOffset.UTC),
    NoopRuntimeDiagnostics,
  )

  private fun threadResponse() =
    """{"id":2,"result":{"thread":{"id":"thread-1"},"model":"worker-model",""" +
      """"cwd":"/checkout","sandbox":{"type":"dangerFullAccess"}}}""" + "\n"

  private fun item(
    text: String,
    delta: Boolean = false,
  ): String =
    if (delta) {
      """{"method":"item/agentMessage/delta",""" +
        """"params":{"threadId":"thread-1","turnId":"turn-1","itemId":"i","delta":"$text"}}""" + "\n"
    } else {
      """{"method":"item/completed",""" +
        """"params":{"threadId":"thread-1","turnId":"turn-1",""" +
        """"item":{"id":"i","type":"agentMessage","text":"$text"}}}""" + "\n"
    }

  private fun terminal(
    thread: String = "thread-1",
    status: String = "completed",
    failure: String = "",
  ) = """{"method":"turn/completed",""" +
    """"params":{"threadId":"$thread",""" +
    """"turn":{"id":"turn-1","status":"$status", """ +
    """"error":{"message":"$failure"}}}}""" + "\n"
}

private class SessionProcess : Process() {
  val stdin = ByteArrayOutputStream()

  override fun getOutputStream() = stdin

  override fun getInputStream(): InputStream = InputStream.nullInputStream()

  override fun getErrorStream(): InputStream = InputStream.nullInputStream()

  override fun waitFor(): Int = 0

  override fun exitValue(): Int = 0

  override fun destroy() = Unit
}

private class Owner : ApprovalOwnerSession {
  override val key = ApprovalOwnerKey("repo", "run", "generation")
  val requests = mutableListOf<ApprovalRegistrationCommand>()

  override fun register(command: ApprovalRegistrationCommand): ApprovalRegistrationResult {
    val fingerprint = command.canonicalInput
    val request =
      command.request.copy(
        actionFingerprint = fingerprint,
        authorityScope = fingerprint?.let { command.request.authorityScope?.copy(actionFingerprint = it) },
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

  override fun release() = Unit
}
