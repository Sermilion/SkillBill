package skillbill.infrastructure.launcher.process.codex

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import skillbill.ports.agentrun.model.AgentRunOutputSink
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
  fun `stale turn failures in the resumed thread do not poison the active turn`() {
    val channel = channel(savedThread = "thread-1")
    try {
      channel.attach(SessionProcess())
      channel.observe("""{"id":1,"result":{"userAgent":"codex_cli_rs/0.166.0 (test)"}}""" + "\n")
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
      assertFalse("approvalPolicy" in turn)
      val sandbox = turn.getValue("sandboxPolicy").jsonObject
      assertEquals("workspaceWrite", sandbox.getValue("type").jsonPrimitive.content)
      assertEquals(
        listOf("/checkout", "/qa"),
        sandbox.getValue("writableRoots").jsonArray.map { it.jsonPrimitive.content },
      )
    } finally {
      channel.finish()
    }
  }

  @Test
  fun `read only turns require the provider to confirm a read only policy object`() {
    for (policy in listOf("readOnly", "workspaceWrite")) {
      val process = SessionProcess()
      val channel = channel(readOnly = true)
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
        assertEquals("read-only", thread.getValue("sandbox").jsonPrimitive.content)
        assertFalse("runtimeWorkspaceRoots" in thread)
        val turn = frames.singleOrNull { it.getValue("method").jsonPrimitive.content == "turn/start" }
        assertEquals(policy == "readOnly", turn != null)
        if (turn != null) {
          val sandbox = turn.getValue("params").jsonObject.getValue("sandboxPolicy").jsonObject
          assertEquals("readOnly", sandbox.getValue("type").jsonPrimitive.content)
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
  fun `incompatible server and changed resume identity never receive a mutating turn`() {
    for (badVersion in listOf(true, false)) {
      val process = SessionProcess()
      val channel = channel(savedThread = "saved-thread")
      try {
        channel.attach(process)
        val version = if (badVersion) "0.166.01" else "0.166.0"
        channel.observe("""{"id":1,"result":{"userAgent":"codex_cli_rs/$version (test)"}}""" + "\n")
        if (!badVersion) channel.observe(threadResponse())
        assertFalse(assertNotNull(channel.outcome()).completed)
        assertFalse(process.stdin.toString().contains("turn/start"))
        assertFalse(process.stdin.toString().contains("thread/start"))
      } finally {
        channel.finish()
      }
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

  private fun channel(
    savedThread: String? = null,
    readOnly: Boolean = false,
  ) = CodexSessionChannel(
    CodexSessionLaunch(
      "scoped briefing",
      "/checkout",
      "worker-model",
      "high",
      savedThread,
      AgentRunOutputSink.NONE,
      listOf("/qa"),
      readOnly = readOnly,
    ),
    Clock.fixed(Instant.EPOCH, ZoneOffset.UTC),
    NoopRuntimeDiagnostics,
  )

  private fun threadResponse() =
    """{"id":2,"result":{"thread":{"id":"thread-1"},"model":"worker-model","cwd":"/checkout"}}""" + "\n"

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
