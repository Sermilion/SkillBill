package dev.skillbill.desktop.core.data.chat

import dev.skillbill.desktop.core.common.HostEnvironment
import dev.skillbill.desktop.core.domain.model.ChatAgent
import dev.skillbill.desktop.core.domain.model.ChatHistoryLine
import dev.skillbill.desktop.core.domain.model.ChatHistoryRole
import dev.skillbill.desktop.core.testing.FakeAppSettingsRepository
import dev.skillbill.desktop.core.testing.RecordingDiagnostics
import kotlinx.coroutines.flow.asFlow
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.test.runTest
import java.io.File
import kotlin.io.path.createTempDirectory
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.time.Clock

class AgentSessionCatalogTest {
  private val home = createTempDirectory("chat-home").toFile()
  private val root = "/work/skill-bill"
  private val host = HostEnvironment(emptyMap(), home.path, "")
  private val store = ChatStore(host, RecordingDiagnostics(), Clock.System)
  private val catalog = AgentSessionCatalog(host, FakeAppSettingsRepository(), store, RecordingDiagnostics())

  private fun write(path: String, vararg lines: String, modified: Long) = File(home, path).apply {
    parentFile.mkdirs()
    writeText(lines.joinToString("\n"))
    setLastModified(modified)
  }

  // A session read from the wrong profile, or resumed without it, is a session the agent cannot find.
  @Test
  fun `sessions from every agent profile in the worktree list newest first and a codex one resumes in its own profile`() = runTest {
    write(
      ".claude-work/projects/${claudeProjectName(root)}/c-1.jsonl",
      """{"type":"user","message":{"role":"user","content":"<local-command-caveat>ignored</local-command-caveat>"}}""",
      """{"type":"user","message":{"role":"user","content":"fix the lint job"}}""",
      """{"type":"assistant","message":{"role":"assistant","content":[{"type":"text","text":"Fixed."}]}}""",
      """{"type":"ai-title","aiTitle":"Fix lint"}""",
      modified = 1_000,
    )
    write(
      ".codex-api/sessions/2026/10/31/rollout-a.jsonl",
      """{"type":"session_meta","payload":{"id":"t-1","cwd":"$root"}}""",
      """{"type":"response_item","payload":{"type":"message","role":"user","content":[{"type":"input_text","text":"# AGENTS.md instructions"}]}}""",
      """{"type":"response_item","payload":{"type":"message","role":"user","content":[{"type":"input_text","text":"why is CI red?"}]}}""",
      """{"type":"response_item","payload":{"type":"message","role":"assistant","content":[{"type":"output_text","text":"Formatting."}]}}""",
      modified = 3_000,
    )
    write(".codex/sessions/2026/10/31/rollout-b.jsonl", """{"type":"session_meta","payload":{"id":"t-2","cwd":"/elsewhere"}}""", modified = 4_000)
    write(".cursor/chats/h/k-1/meta.json", """{"cwd":"$root","hasConversation":true,"updatedAtMs":2000}""", modified = 2_000)

    val listed = catalog.list("wf-1", root, "current", ::ChatAgentFor)

    assertEquals(listOf("t-1", "k-1", "c-1"), listed.map { it.sessionId })
    assertEquals(listOf("why is CI red?", "cursor session", "Fix lint"), listed.map { it.title })
    assertEquals(listOf(".codex-api", ".cursor", ".claude-work"), listed.map { File(it.agent.profile!!).name })
    val codex = listed.first()
    assertEquals(
      listOf(ChatHistoryLine(ChatHistoryRole.Operator, "why is CI red?"), ChatHistoryLine(ChatHistoryRole.Agent, "Formatting.")),
      catalog.open(codex),
    )

    val commands = mutableListOf<ChatCommand>()
    val launcher = ChatProcessLauncher { command -> commands += command; listOf(ChatProcessOutput.Exited(0, "")).asFlow() }
    CodexChatTransport(host, launcher, store, RecordingDiagnostics())
      .run(chatRequest(agent = codex.agent, firstTurn = false).copy(sessionId = codex.sessionId)).toList()

    assertEquals(listOf("resume", "t-1", "-"), commands.single().argv.takeLast(3))
    assertEquals(File(home, ".codex-api").path, commands.single().environment["CODEX_HOME"])
  }

  @Suppress("FunctionName")
  private fun ChatAgentFor(agentId: String) = ChatAgent(agentId, null, null)
}
