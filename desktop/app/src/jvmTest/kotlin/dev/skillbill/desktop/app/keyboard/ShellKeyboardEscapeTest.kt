package dev.skillbill.desktop.app.keyboard

import dev.skillbill.desktop.app.command.CommandMenuState
import dev.skillbill.desktop.app.navigation.WindowNavigator
import dev.skillbill.desktop.core.domain.model.Lifecycle
import dev.skillbill.desktop.core.domain.model.WorkControlIdentity
import dev.skillbill.desktop.core.domain.model.WorkItem
import dev.skillbill.desktop.core.domain.session.DatabaseSessionController
import dev.skillbill.desktop.core.domain.session.DatabaseSessionState
import dev.skillbill.desktop.core.ui.input.ShellInput
import dev.skillbill.desktop.core.ui.navigation.DetailRoute
import dev.skillbill.desktop.core.ui.navigation.WorkRoute
import dev.skillbill.desktop.core.ui.pane.ChatCommands
import dev.skillbill.desktop.core.ui.pane.PaneCommands
import dev.skillbill.desktop.core.ui.selection.SelectionHolder
import dev.skillbill.desktop.core.ui.shell.ShellPanes
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import java.nio.file.Path
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlin.time.Instant

class ShellKeyboardEscapeTest {
  @Test
  fun `Esc closes an open menu before it leaves the detail under it`() {
    val selection = SelectionHolder()
    val navigator = WindowNavigator(OpenSession, selection)
    navigator.navigate(DetailRoute(workItem("goal-1")))
    val input = ShellInput()
    val panes = ShellPanes()
    val keyboard = ShellKeyboard(navigator, panes, input, CommandMenuState(), TerminalTriggers(NoTerminal), NoChat, selection)
    var dismissed = false
    val menu = ShellInput.Layer { dismissed = true }
    input.addLayer(menu)

    assertTrue(keyboard.onKey(KeyPress(ShellKey.Escape)))
    assertTrue(dismissed)
    assertTrue(navigator.top.route is DetailRoute)

    input.removeLayer(menu)
    assertTrue(keyboard.onKey(KeyPress(ShellKey.Escape)))
    assertEquals(WorkRoute(null), navigator.top.route)
  }

  private object NoTerminal : PaneCommands {
    override val terminalOpen: StateFlow<Boolean> = MutableStateFlow(false)
    override val terminalFocused: StateFlow<Boolean> = MutableStateFlow(false)

    override fun toggleTerminal() = Unit

    override fun newTerminalTab() = Unit

    override fun openTerminal(directory: Path?) = Unit

    override fun specProjectSelected(checkout: Path?) = Unit

    override fun closeTerminals() = Unit
  }

  private object NoChat : ChatCommands {
    override fun toggleChat(item: WorkItem?) = Unit

    override fun openChat(item: WorkItem) = Unit

    override fun closeChats() = Unit
  }

  private object OpenSession : DatabaseSessionController {
    override val state: StateFlow<DatabaseSessionState> = MutableStateFlow(DatabaseSessionState.Open("/db", sessionId = 1L))

    override fun reopen() = Unit
  }

  private fun workItem(id: String) = WorkItem(
    id = id, kind = null, issueKey = null, title = id, summary = id, lifecycle = Lifecycle.Active, planningPrepared = true,
    stalledSince = null, pauseRequested = false, fresh = true, stepLabel = "implement", phaseExecution = null,
    phaseExecutionLabel = null, progress = null, planningProgress = null, activeDurationMs = null, activeDurationAsOf = null,
    startedAt = null, finishedAt = null, model = null, effort = null, agentId = null, branch = null, repositoryName = "repo",
    repositoryRoot = "/repo", liveSection = null, displayGroup = null, displayPriority = 0,
    requiresAttention = false, controlEligible = false, pauseEligible = false, running = true, recentlyFinishedPhase = false,
    doneWithinDay = false, freshnessDeadline = null, membershipDeadline = null, exactPauseReason = null,
    awaitsOperatorDecision = false, currentSubtaskId = null, reason = null,
    control = WorkControlIdentity(
      sessionId = 1L, workflowId = id, invocationId = null, repositoryIdentity = "repo", executionId = null, statusStoreId = null,
      runSequence = null, statusRevision = null, phaseId = null, updatedAt = Instant.fromEpochMilliseconds(0),
      controlLeaseOwner = null, controlLeaseGeneration = null, storeId = null, storeRevision = null, databaseFile = null,
    ),
  )
}
