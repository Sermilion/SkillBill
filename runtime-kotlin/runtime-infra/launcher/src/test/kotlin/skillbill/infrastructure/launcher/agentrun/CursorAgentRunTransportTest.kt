package skillbill.infrastructure.launcher.agentrun

import skillbill.ports.agentrun.model.SkillRunRequest
import java.nio.file.Path
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.seconds

class CursorAgentRunTransportTest {
  @Test
  fun `unstreamed launch buffers instead of carrying the whole session`() {
    val command = CursorAgentRunCommandBuilder().build(request())

    assertFalse(
      command.command.contains("stream-json"),
      "a launch nobody streams must not request the whole-session transport; its " +
        "terminal event " +
        "arrives last, so a capped drain spends its budget on the preamble",
    )
    assertEquals(listOf("agent", "acp"), command.command)
  }

  @Test
  fun `a streamed launch still requests the per-event transport`() {
    val command = CursorAgentRunCommandBuilder().build(request().copy(streamOutputForLiveness = true))

    assertEquals(AgentRunApprovalProtocol.CURSOR_ACP, command.approvalProtocol)
    assertFalse(command.command.contains("--force"))
  }

  @Test
  fun `the decoder harvests the buffered single-object form and ignores usage`() {
    val decoded =
      AgentRunOutputDecoder.CURSOR_STREAM_JSON.decode(
        """{"type":"result","subtype":"success","is_error":false,"result":"PONG",""" +
          """"usage":{"inputTokens":14154,"outputTokens":14,"cacheReadTokens":896}}""",
      )

    assertEquals("PONG", decoded.text)
  }

  @Test
  fun `buffered result with progress glued onto NO_FINDINGS harvests the register`() {
    val glued =
      "I'll fetch the bound evidence then score the parse return type is the " +
        "core change.reachaNO_FINDINGS"
    val decoded =
      AgentRunOutputDecoder.CURSOR_STREAM_JSON.decode(
        """{"type":"result","subtype":"success","is_error":false,"result":"$glued",""" +
          """"usage":{"inputTokens":134323,"outputTokens":21191}}""",
      )

    assertEquals("NO_FINDINGS", decoded.text)
  }

  @Test
  fun `last turn review payload with lanes NO_FINDINGS and verdict wins over concatenated terminal result`() {
    val progress = "I'll fetch the bound evidence."
    val payload = "lanes: architecture\\nNO_FINDINGS\\nverdict: approved"
    val jsonl =
      """
      {"type":"assistant","message":{"content":[{"type":"text","text":"$progress"}]}}
      {"type":"assistant","message":{"content":[{"type":"text","text":"$payload"}]}}
      {"type":"result","result":"${progress}NO_FINDINGS","usage":{"inputTokens":10,"outputTokens":4}}
      """.trimIndent()

    val decoded = AgentRunOutputDecoder.CURSOR_STREAM_JSON.decode(jsonl)
    assertTrue(decoded.text.contains("lanes: architecture"))
    assertTrue(decoded.text.contains("NO_FINDINGS"))
    assertTrue(decoded.text.contains("verdict: approved"))
    assertFalse(decoded.text.contains(progress))
  }

  @Test
  fun `buffered result with progress glued onto NO_FINDINGS and verdict harvests register and verdict`() {
    val glued =
      "I'll fetch the bound evidence then score the parse return type is the " +
        "core change.reachaNO_FINDINGS" +
        "verdict: approved"
    val decoded =
      AgentRunOutputDecoder.CURSOR_STREAM_JSON.decode(
        """{"type":"result","subtype":"success","is_error":false,"result":"$glued",""" +
          """"usage":{"inputTokens":134323,"outputTokens":21191}}""",
      )

    assertTrue(decoded.text.contains("NO_FINDINGS"))
    assertTrue(decoded.text.contains("verdict: approved"))
    assertFalse(decoded.text.contains("reacha"))
  }

  @Test
  fun `finding description ending in verdict approved is not harvested as a register verdict`() {
    val glued = "progress[F-001] Major | High | path.kt:1 | dropped verdict: approved"
    val decoded =
      AgentRunOutputDecoder.CURSOR_STREAM_JSON.decode(
        """{"type":"result","result":"$glued"}""",
      )

    assertEquals("[F-001] Major | High | path.kt:1 | dropped verdict: approved", decoded.text)
  }

  @Test
  fun `last assistant register wins over concatenated terminal result`() {
    val jsonl =
      """
      {"type":"assistant","message":{"content":[{"type":"text","text":"I'll fetch the bound evidence."}]}}
      {"type":"assistant","message":{"content":[{"type":"text","text":"NO_FINDINGS"}]}}
      {"type":"result","result":"I'll fetch the bound evidence.NO_FINDINGS","usage":{"inputTokens":10,"outputTokens":4}}
      """.trimIndent()

    assertEquals("NO_FINDINGS", AgentRunOutputDecoder.CURSOR_STREAM_JSON.decode(jsonl).text)
  }

  @Test
  fun `last assistant Finding none wins over concatenated progress`() {
    val progress =
      "I'll assess the head-commit code and tests against the subtask spec, " +
        "without editing files or running builds."
    val jsonl =
      """
      {"type":"assistant","message":{"content":[{"type":"text","text":"$progress"}]}}
      {"type":"assistant","message":{"content":[{"type":"text","text":"Finding: none"}]}}
      {"type":"result","result":"${progress}Finding: none","usage":{"inputTokens":10,"outputTokens":4}}
      """.trimIndent()

    assertEquals("Finding: none", AgentRunOutputDecoder.CURSOR_STREAM_JSON.decode(jsonl).text)
  }

  @Test
  fun `buffered result with progress glued onto Finding none harvests the assessment line`() {
    val glued =
      "I'll assess the head-commit code and tests against the subtask spec, " +
        "without editing files or running builds." +
        "Finding: none"
    val decoded =
      AgentRunOutputDecoder.CURSOR_STREAM_JSON.decode(
        """{"type":"result","subtype":"success","is_error":false,"result":"$glued"}""",
      )

    assertEquals("Finding: none", decoded.text)
  }

  @Test
  fun `glued finding lines are harvested and trailing NO_FINDINGS is dropped`() {
    val glued = "progress[F-001] Major | High | path.kt:1 | bugNO_FINDINGS"
    val decoded =
      AgentRunOutputDecoder.CURSOR_STREAM_JSON.decode(
        """{"type":"result","result":"$glued"}""",
      )

    assertEquals("[F-001] Major | High | path.kt:1 | bug", decoded.text)
  }

  @Test
  fun `progress sentences glued onto a finding harvest the finding line`() {
    val glued =
      "I'll follow the bill-code-review skill and fetch the assigned evidence." +
        "MCP read_evidence is still blocked. I'll read the assigned files from " +
        "there." +
        "[F-001] Major | High | specialist=bill-kotlin-code-review-architecture | " +
        "path=\\\"runtime-kotlin/Foo.kt\\\" | line=12 | endpoint close leaks on " +
        "spawn failure"
    val decoded =
      AgentRunOutputDecoder.CURSOR_STREAM_JSON.decode(
        """{"type":"result","subtype":"success","is_error":false,"result":"$glued"}""",
      )

    assertEquals(
      "[F-001] Major | High | specialist=bill-kotlin-code-review-architecture | " +
        "path=\"runtime-kotlin/Foo.kt\" | line=12 | endpoint close leaks on spawn " +
        "failure",
      decoded.text,
    )
  }

  private fun request(): SkillRunRequest =
    SkillRunRequest(
      issueKey = "SKILL-113",
      repoRoot = Path.of("/tmp/skillbill-agent-run"),
      subtaskId = 1,
      timeout = 3.seconds,
      promptOverride = "Phase: implement",
    )
}
