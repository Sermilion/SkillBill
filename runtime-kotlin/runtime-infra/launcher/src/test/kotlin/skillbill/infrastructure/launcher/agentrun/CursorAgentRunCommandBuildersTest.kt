package skillbill.infrastructure.launcher.agentrun

import skillbill.infrastructure.launcher.process.launch.AgentRunIdlePolicy
import skillbill.infrastructure.launcher.review.CursorReviewStreamError
import skillbill.infrastructure.launcher.review.CursorReviewStreamMalformedError
import kotlin.test.Test
import kotlin.test.assertContains
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.seconds

class CursorAgentRunCommandBuildersTest {
  @Test
  fun `cursor bypass launch carries prompt checkout model and native bypass flags`() {
    val command = CursorAgentRunCommandBuilder().build(request(model = "model", effort = "high"))
    assertEquals(
      listOf(
        "agent", "--print", "--sandbox", "disabled", "--force", "--trust", "--approve-mcps",
        "--workspace", "/tmp/skillbill-agent-run", "--output-format", "json", "--model", "model[effort=high]",
      ),
      command.command,
    )
    assertEquals("model[effort=high]", command.sessionModel)
    assertEquals("/tmp/skillbill-agent-run", command.workingDirectory.toString())
    assertEquals("Phase: implement", command.stdinText)
    assertEquals(3.seconds, command.timeout)
    assertNull(command.approvalProtocol)
  }

  @Test
  fun `cursor bypass launch streams provider events without a host session`() {
    val builder = CursorAgentRunCommandBuilder()
    val command =
      builder.build(
        request().copy(streamProviderOutput = true, streamOutputForLiveness = true),
      )

    assertContains(command.command, "--print")
    assertContains(command.command, "stream-json")
    assertContains(command.command, "--stream-partial-output")
    assertNull(command.approvalProtocol)
    assertEquals(AgentRunIdlePolicy.OUTPUT_EXTENDED, command.idlePolicy)
  }

  @Test
  fun `cursor liveness-only launch withholds --stream-partial-output and falls back to heartbeat`() {
    val builder = CursorAgentRunCommandBuilder()
    val command = builder.build(request().copy(streamOutputForLiveness = true))

    assertFalse(
      command.command.contains("--stream-partial-output"),
      "partial deltas must not be requested for liveness alone; they are " +
        "indistinguishable from " +
        "finished turns at harvest time",
    )
    assertNull(command.approvalProtocol)
    assertEquals(AgentRunIdlePolicy.HEARTBEAT_EXTENDED, command.idlePolicy)
  }

  @Test
  fun `cursor read-only phase uses HEARTBEAT_EXTENDED idle policy`() {
    val builder = CursorAgentRunCommandBuilder()
    val command = builder.build(request().copy(readOnlyPhase = true))

    assertEquals(AgentRunIdlePolicy.HEARTBEAT_EXTENDED, command.idlePolicy)
  }

  @Test
  fun `cursor model-only directive reaches ACP setup`() {
    val builder = CursorAgentRunCommandBuilder()
    val command = builder.build(request(model = "claude-opus-4-8"))

    assertEquals("claude-opus-4-8", command.sessionModel)
  }

  @Test
  fun `cursor model-plus-effort directive merges into bracket syntax`() {
    val builder = CursorAgentRunCommandBuilder()
    val command = builder.build(request(model = "claude-opus-4-8", effort = "high"))

    assertEquals("claude-opus-4-8[effort=high]", command.sessionModel)
  }

  @Test
  fun `cursor effort directive without model fails loudly`() {
    val builder = CursorAgentRunCommandBuilder()

    val exception =
      assertFailsWith<IllegalArgumentException> {
        builder.build(request(effort = "high"))
      }

    assertContains(exception.message ?: "", "effort directive requires a model directive")
  }

  @Test
  fun `cursor model already with bracket parameters accepts identical duplicate effort`() {
    val builder = CursorAgentRunCommandBuilder()
    val command = builder.build(request(model = "claude-opus-4-8[effort=high]", effort = "high"))

    assertEquals("claude-opus-4-8[effort=high]", command.sessionModel)
  }

  @Test
  fun `cursor model already with bracket parameters rejects conflicting effort loudly`() {
    val builder = CursorAgentRunCommandBuilder()

    val exception =
      assertFailsWith<IllegalStateException> {
        builder.build(request(model = "claude-opus-4-8[effort=high]", effort = "medium"))
      }

    assertContains(exception.message ?: "", "Conflicting effort directive")
    assertContains(exception.message ?: "", "effort='high'")
    assertContains(exception.message ?: "", "effort='medium'")
  }

  @Test
  fun `cursor decoder extracts result and ignores usage from JSONL`() {
    val jsonl =
      """
      {"type":"partial","delta":"increment"}
      {"type":"result","result":"PLAN-OK","usage":{"input_tokens":100,"cached_input_tokens":20,"output_tokens":50,"total_tokens":150}}
      """.trimIndent()

    val decoded = AgentRunOutputDecoder.CURSOR_STREAM_JSON.decode(jsonl)

    assertEquals("PLAN-OK", decoded.text)
  }

  @Test
  fun `cursor decoder terminal result wins over partial deltas`() {
    val jsonl = """{"type":"partial","delta":"partial"}
{"type":"partial","delta":" delta"}
{"type":"result","result":"final","usage":{"input_tokens":10,"output_tokens":5,"total_tokens":15}}"""

    val decoded = AgentRunOutputDecoder.CURSOR_STREAM_JSON.decode(jsonl)

    assertEquals("final", decoded.text)
  }

  @Test
  fun `cursor decoder harvests completed assistant text independently of partial deltas`() {
    val jsonl = """{"type":"partial","delta":"draft"}
{"type":"assistant","message":{"content":[{"type":"text","text":"completed report"}]}}"""

    val decoded = AgentRunOutputDecoder.CURSOR_STREAM_JSON.decode(jsonl)

    assertEquals("completed report", decoded.text)
  }

  @Test
  fun `cursor decoder ignores camelCase usage keys`() {
    val usage = """{"inputTokens":28164,"cachedInputTokens":11,"outputTokens":0,"totalTokens":28175}"""
    val jsonl = """{"type":"result","result":"PLAN-OK","usage":$usage}"""

    val decoded = AgentRunOutputDecoder.CURSOR_STREAM_JSON.decode(jsonl)

    assertEquals("PLAN-OK", decoded.text)
  }

  @Test
  fun `cursor decoder reports an empty successful turn with zero assistant events`() {
    val jsonl = """{"type":"system","subtype":"init"}
{"type":"user","message":{"content":[{"type":"text","text":"briefing"}]}}
{"type":"result","subtype":"success","is_error":false,"result":"","usage":{"inputTokens":33110,"outputTokens":0}}"""

    val decoded = AgentRunOutputDecoder.CURSOR_STREAM_JSON.decode(jsonl)

    assertEquals("", decoded.text)
    assertEquals(0, decoded.assistantEventCount)
    assertNotNull(decoded.rawOutputPreview, "an empty harvest must retain bounded transport evidence")
  }

  @Test
  fun `cursor decoder falls back to the longest assistant turn when the terminal result is blank`() {
    val jsonl = """{"type":"assistant","message":{"content":[{"type":"text","text":"short"}]}}
{"type":"assistant","message":{"content":[{"type":"text","text":"{\"status\":\"completed\"}"}]}}
{"type":"result","result":"","usage":{"inputTokens":10,"outputTokens":4}}"""

    val decoded = AgentRunOutputDecoder.CURSOR_STREAM_JSON.decode(jsonl)

    assertEquals("""{"status":"completed"}""", decoded.text)
    assertEquals(2, decoded.assistantEventCount)
    assertNull(decoded.rawOutputPreview, "a harvested turn is not empty-turn evidence")
  }

  @Test
  fun `cursor decoder declares an undecodable stream so the launcher degrades to an empty harvest`() {
    val decoder = AgentRunOutputDecoder.CURSOR_STREAM_JSON

    assertTrue(decoder.undecodable(CursorReviewStreamMalformedError("truncated", RuntimeException())))
    assertFalse(decoder.undecodable(CursorReviewStreamError("provider said no")))
    assertFalse(
      AgentRunOutputDecoder.PLAIN.undecodable(CursorReviewStreamMalformedError("truncated", RuntimeException())),
      "the default policy keeps propagating; only a decoder that owns the " +
        "transport may degrade",
    )
  }

  @Test
  fun `cursor decoder on malformed line throws typed error`() {
    val jsonl = """invalid line
{"type":"result","result":"success","usage":{"input_tokens":5,"output_tokens":3,"total_tokens":8}}
more invalid"""

    assertFailsWith<CursorReviewStreamMalformedError> {
      AgentRunOutputDecoder.CURSOR_STREAM_JSON.decode(jsonl)
    }
  }

  @Test
  fun `cursor decoder on fully malformed input throws typed error`() {
    val malformed = "not json at all"

    assertFailsWith<CursorReviewStreamMalformedError> {
      AgentRunOutputDecoder.CURSOR_STREAM_JSON.decode(malformed)
    }
  }

  @Test
  fun `cursor decoder on empty stream returns empty text`() {
    val decoded = AgentRunOutputDecoder.CURSOR_STREAM_JSON.decode("")

    assertEquals("", decoded.text)
  }

  @Test
  fun `cursor decoder on error event throws typed error`() {
    val jsonl = """{"type":"error","error":"Provider error occurred"}"""

    assertFailsWith<CursorReviewStreamError> {
      AgentRunOutputDecoder.CURSOR_STREAM_JSON.decode(jsonl)
    }
  }

  @Test
  fun `cursor decoder on decoded envelope with no terminal result returns empty text`() {
    val jsonl = """{"type":"partial","delta":"only"}
{"type":"system","message":"done"}"""

    val decoded = AgentRunOutputDecoder.CURSOR_STREAM_JSON.decode(jsonl)

    assertEquals("", decoded.text)
  }

  @Test
  fun `cursor decoder bounds oversized input and stops processing`() {
    val hugeLine = "x".repeat(15_000_000)
    val jsonl = """{"type":"result","result":"early"}
$hugeLine
{"type":"result","result":"late","usage":{"total_tokens":1}}"""

    val decoded = AgentRunOutputDecoder.CURSOR_STREAM_JSON.decode(jsonl)

    assertEquals("early", decoded.text)
  }

  @Test
  fun `cursor builder sets empty passthrough keys when review evidence broker is absent`() {
    val builder = CursorAgentRunCommandBuilder()
    val command = builder.build(request())

    assertTrue(
      command.environmentPassthroughKeys.isEmpty(),
      "Non-isolated cursor run must not passthrough provider keys",
    )
  }
}
