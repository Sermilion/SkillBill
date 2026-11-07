package dev.skillbill.desktop.core.data.chat

import dev.skillbill.desktop.core.common.HostEnvironment
import dev.skillbill.desktop.core.domain.model.ChatAgent
import dev.skillbill.desktop.core.domain.model.ChatHistoryLine
import dev.skillbill.desktop.core.domain.model.ChatHistoryRole
import dev.skillbill.desktop.core.domain.model.SessionMoveResult
import dev.skillbill.desktop.core.testing.FakeAppSettingsRepository
import dev.skillbill.desktop.core.testing.RecordingDiagnostics
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

    assertEquals("t-1", store.agentSession(codex.sessionId))
    assertEquals("t-1", ChatStore(host, RecordingDiagnostics(), Clock.System).agentSession(codex.sessionId))
  }

  // Overwriting a session the repository root already holds, or moving a project's memory, loses the operator's history.
  @Test
  fun `moving claude sessions takes the session file and its folder, keeps an existing destination session and leaves memory`() = runTest {
    val worktree = "/work/skill-bill-goal"
    val source = "projects/${claudeProjectName(worktree)}"
    val target = "projects/${claudeProjectName(root)}"
    write(".claude/$source/A.jsonl", """{"type":"user"}""", modified = 1_000)
    write(".claude/$source/A/subagents/s.jsonl", "sub", modified = 1_000)
    write(".claude/$source/B.jsonl", "from worktree", modified = 1_000)
    write(".claude/$source/memory/MEMORY.md", "notes", modified = 1_000)
    write(".claude/$target/B.jsonl", "at root", modified = 1_000)

    val result = catalog.move(worktree, root)

    assertEquals(SessionMoveResult(claudeMoved = 1, codexMoved = 0, failed = 0), result)
    assertEquals("""{"type":"user"}""", File(home, ".claude/$target/A.jsonl").readText())
    assertEquals("sub", File(home, ".claude/$target/A/subagents/s.jsonl").readText())
    assertEquals("at root", File(home, ".claude/$target/B.jsonl").readText())
    assertEquals("from worktree", File(home, ".claude/$source/B.jsonl").readText())
    assertEquals("notes", File(home, ".claude/$source/memory/MEMORY.md").readText())
    assertEquals(0, catalog.move(worktree, root).claudeMoved)
  }

  // Corrupting a session's history, or rewriting another goal's session, breaks the chat the operator resumes.
  @Test
  fun `rewriting codex sessions changes only the first line of a session in the worktree and lists it at the root`() = runTest {
    val worktree = "/work/skill-bill-goal"
    val tail = "\n" + """{"type":"turn_context","payload":{"cwd":"$worktree"}}""" + "\n" + """{"type":"response_item","payload":{"type":"message","role":"user","content":[{"type":"input_text","text":"why?"}]}}"""
    val mine = write(".codex/sessions/2026/10/31/rollout-a.jsonl", """{"type":"session_meta","payload":{"id":"t-1","cwd":"$worktree","cli":"x"}}$tail""", modified = 1_000)
    val other = write(".codex/sessions/2026/10/31/rollout-b.jsonl", """{"type":"session_meta","payload":{"id":"t-2","cwd":"/elsewhere"}}$tail""", modified = 2_000)
    val otherBefore = other.readBytes()

    val result = catalog.move(worktree, root)

    assertEquals(SessionMoveResult(claudeMoved = 0, codexMoved = 1, failed = 0), result)
    val lines = mine.readText().lines()
    assertEquals("""{"type":"session_meta","payload":{"id":"t-1","cwd":"$root","cli":"x"}}""", lines.first())
    assertEquals(tail.removePrefix("\n"), lines.drop(1).joinToString("\n"))
    assertEquals(1_000, mine.lastModified())
    assertEquals(otherBefore.toList(), other.readBytes().toList())
    assertEquals(2_000, other.lastModified())
    assertEquals(listOf("rollout-a.jsonl", "rollout-b.jsonl"), mine.parentFile.list()!!.sorted())
    assertEquals(listOf("t-1"), catalog.list("wf-1", root, "current", ::ChatAgentFor).map { it.sessionId })
  }

  // A resume picked from a listing made before the move must still show the session's earlier lines.
  @Test
  fun `a claude session listed in the worktree before the move still opens its lines after it`() = runTest {
    val worktree = "/work/skill-bill-goal"
    write(
      ".claude/projects/${claudeProjectName(worktree)}/A.jsonl",
      """{"type":"user","message":{"role":"user","content":"ship it"}}""",
      modified = 1_000,
    )
    val entry = catalog.list("wf-1", worktree, "current", ::ChatAgentFor).single()

    catalog.move(worktree, root)

    assertEquals(listOf(ChatHistoryLine(ChatHistoryRole.Operator, "ship it")), catalog.open(entry))
  }

  @Suppress("FunctionName")
  private fun ChatAgentFor(agentId: String) = ChatAgent(agentId, null, null)
}
