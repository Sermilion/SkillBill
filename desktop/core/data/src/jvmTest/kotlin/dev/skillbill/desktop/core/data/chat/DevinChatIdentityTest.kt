package dev.skillbill.desktop.core.data.chat

import dev.skillbill.desktop.core.common.ApplicationCoroutineScope
import dev.skillbill.desktop.core.common.DatabaseDispatcher
import dev.skillbill.desktop.core.common.HostEnvironment
import dev.skillbill.desktop.core.data.session.ComponentSessionGraphFactory
import dev.skillbill.desktop.core.data.session.DatabaseSessionComponent
import dev.skillbill.desktop.core.data.session.DatabaseSessionManager
import dev.skillbill.desktop.core.data.session.LaunchReservations
import dev.skillbill.desktop.core.domain.model.ChatAgent
import dev.skillbill.desktop.core.domain.model.ChatChangesBaseline
import dev.skillbill.desktop.core.domain.model.ChatEvent
import dev.skillbill.desktop.core.domain.model.ChatHistoryEntry
import dev.skillbill.desktop.core.domain.model.ChatHistoryLine
import dev.skillbill.desktop.core.domain.model.ChatHistoryRole
import dev.skillbill.desktop.core.domain.model.ChatNewTaskContext
import dev.skillbill.desktop.core.domain.model.ChatScope
import dev.skillbill.desktop.core.testing.FakeAppSettingsRepository
import dev.skillbill.desktop.core.testing.RecordingDiagnostics
import kotlinx.coroutines.channels.ReceiveChannel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.runTest
import java.io.File
import kotlin.io.path.createTempDirectory
import kotlin.test.Test
import kotlin.test.assertContains
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNotEquals
import kotlin.test.assertTrue
import kotlin.time.Clock
import kotlin.time.Instant

class DevinChatIdentityTest {
  private val root = createTempDirectory("devin-chat-identity").toRealPath().toString()
  private val host = HostEnvironment(emptyMap(), root, "")
  private val diagnostics = RecordingDiagnostics()
  private val clock = object : Clock {
    override fun now() = Instant.parse("2026-11-14T12:00:00.123456789Z")
  }
  private val store = ChatStore(host, diagnostics, clock)
  private val baseline = ChatChangesBaseline("head", emptyList(), null, clock.now())
  private val draft = ChatHistoryEntry(
    "fresh-devin", null, ChatAgent("devin", "swe-2-high-lightning", null), clock.now(), "hello",
    listOf(ChatHistoryLine(ChatHistoryRole.Operator, "hello")),
    ChatNewTaskContext(root, "main", sessionEstablished = false,
      changesBaseline = baseline.copy(projectId = "project", worktrees = mapOf(root to baseline)),
      changesCaptureAttempted = true),
  )

  @Test
  fun `fresh Devin draft with precise baseline timestamps passes identity validation before runtime admission`() = runTest {
    assertTrue(store.saveChat(draft))
    val saved = assertIs<DevinSavedState.Readable>(store.devinSavedState(draft.sessionId))
    assertNotEquals(draft.newTask?.changesBaseline?.takenAt, saved.newTask?.changesBaseline?.takenAt)
    val original = savedFile().readText()
    val sessions = DatabaseSessionManager(
      ComponentSessionGraphFactory(object : DatabaseSessionComponent.Factory {
        override fun createDatabaseSessionComponent(): DatabaseSessionComponent = error("No runtime session is opened")
      }),
      FakeAppSettingsRepository(), DatabaseDispatcher(StandardTestDispatcher(testScheduler)), diagnostics,
      LaunchReservations(), ApplicationCoroutineScope(backgroundScope),
    )
    val launcher = object : LiveChatProcessLauncher {
      override fun open(command: ChatCommand, input: ReceiveChannel<String>): Flow<ChatProcessOutput> =
        error("Runtime admission must refuse before launching a provider")
    }
    val transport = DevinChatTransport(host, store, diagnostics, launcher, ChatApprovalAuthority(sessions, diagnostics), sessions)
    val request = chatRequest(agent = draft.agent).copy(
      sessionId = draft.sessionId, scope = ChatScope.NewTask(root, root, "main", root), savedHistory = draft,
    )

    val failure = assertIs<ChatEvent.Failed>(transport.run(request).toList().single())

    assertContains(failure.detail.orEmpty(), "active runtime MCP context")
    assertContains(diagnostics.categories, "chat.devin_runtime_context_refused")
    assertFalse("chat.devin_saved_identity_refused" in diagnostics.categories)
    assertEquals(original, savedFile().readText())
  }

  @Test
  fun `Devin acquires and retains its handle when draft progress changes across saves`() {
    assertTrue(store.saveChat(draft))
    assertTrue(store.saveDevinSession(draft.sessionId, "native-handle", draft))
    val continued = draft.copy(newTask = checkNotNull(draft.newTask).copy(
      sessionEstablished = true, pendingTurnLineIndex = 1, changesAttributedPaths = setOf("changed.kt"),
    ))
    assertTrue(store.saveDevinSession(draft.sessionId, "native-handle", continued))
    val saved = assertIs<DevinSavedState.Readable>(store.devinSavedState(draft.sessionId))
    assertEquals("native-handle", saved.handle)
    assertEquals(setOf("changed.kt"), saved.newTask?.changesAttributedPaths)
    val catalog = AgentSessionCatalog(host, FakeAppSettingsRepository(), store, diagnostics)
    assertEquals(continued.lines, catalog.open(continued))
  }

  @Test
  fun `Devin handle writes refuse changed conversation identity and preserve the original record`() {
    assertTrue(store.saveDevinSession(draft.sessionId, "native-handle", draft))
    val original = savedFile().readText()
    val context = checkNotNull(draft.newTask)
    val changed = listOf(
      draft.copy(agent = draft.agent.copy(agentId = "codex")),
      draft.copy(agent = draft.agent.copy(profile = "other-profile")),
      draft.copy(agent = draft.agent.copy(effort = "high")),
      draft.copy(executionId = "other-execution", newTask = null, executionCheckoutRoot = root),
      draft.copy(newTask = context.copy(workingDirectory = createTempDirectory("other-checkout").toString())),
      draft.copy(newTask = context.copy(selectedRepositoryRoot = null)),
      draft.copy(newTask = context.copy(baseBranch = "other-branch")),
    )
    for (entry in changed) {
      assertFalse(store.saveDevinSession(draft.sessionId, "native-handle", entry))
      assertEquals(original, savedFile().readText())
    }
    assertFalse(store.saveDevinSession(draft.sessionId, "other-handle", draft))
    assertEquals(original, savedFile().readText())
  }

  private fun savedFile() = File(root, "$CHAT_STORE_PATH/${draft.sessionId}.json")
}
