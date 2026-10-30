package dev.skillbill.desktop.app.notifications

import dev.skillbill.desktop.app.host.HostIntents
import dev.skillbill.desktop.core.common.ApplicationCoroutineScope
import dev.skillbill.desktop.core.domain.model.Lifecycle
import dev.skillbill.desktop.core.domain.session.DatabaseSessionController
import dev.skillbill.desktop.core.domain.session.DatabaseSessionState
import dev.skillbill.desktop.core.domain.settings.NotificationKind
import dev.skillbill.desktop.core.testing.FakeAppSettingsRepository
import dev.skillbill.desktop.core.testing.FakeWorkBoardRepository
import dev.skillbill.desktop.core.testing.loadedBoard
import dev.skillbill.desktop.core.testing.workItem
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.runCurrent
import kotlin.test.Test
import kotlin.test.assertEquals

class TransitionNotifierTest {
  @OptIn(ExperimentalCoroutinesApi::class)
  @Test
  fun newSessionStartsSilentBaselineAfterOneLifecycleTransitionNotification() = runTest {
    val goal = workItem("SKILL-1").copy(summary = "Build the app", control = workItem("SKILL-1").control.copy(statusRevision = "1"))
    val boards = FakeWorkBoardRepository(loadedBoard(items = listOf(goal)))
    val session = FakeSession(DatabaseSessionState.Open("one.db", 1))
    val settings = FakeAppSettingsRepository()
    val sink = RecordingSink()
    TransitionNotifier(boards, session, settings, sink, HostIntents(), ApplicationCoroutineScope(backgroundScope)).start()
    runCurrent()
    boards.state.value = loadedBoard(items = listOf(goal.copy(lifecycle = Lifecycle.Blocked, control = goal.control.copy(statusRevision = "2"))))
    runCurrent()
    assertEquals(listOf("Build the app"), sink.titles)
    session.state.value = DatabaseSessionState.Open("two.db", 2)
    boards.state.value = loadedBoard(items = listOf("a", "b", "c").mapIndexed { index, id ->
      workItem(id).copy(lifecycle = Lifecycle.Blocked, summary = "Goal $index", control = workItem(id).control.copy(statusRevision = "1"))
    })
    runCurrent()
    assertEquals(listOf("Build the app"), sink.titles)
  }

  @OptIn(ExperimentalCoroutinesApi::class)
  @Test
  fun disabledPreferenceSuppressesBlockedTransition() = runTest {
    val goal = workItem("SKILL-2").copy(summary = "Blocked goal", control = workItem("SKILL-2").control.copy(statusRevision = "1"))
    val settings = FakeAppSettingsRepository()
    val boards = FakeWorkBoardRepository(loadedBoard(items = listOf(goal)))
    val sink = RecordingSink(deferDelivery = true)
    TransitionNotifier(boards, FakeSession(DatabaseSessionState.Open("one.db", 1)), settings, sink, HostIntents(), ApplicationCoroutineScope(backgroundScope)).start()
    runCurrent()
    boards.state.value = loadedBoard(items = listOf(goal.copy(lifecycle = Lifecycle.Blocked, control = goal.control.copy(statusRevision = "2"))))
    runCurrent()
    settings.setNotification(NotificationKind.Blocked, false)
    runCurrent()
    sink.deliverPending()
    assertEquals(emptyList(), sink.titles)
  }

  private class FakeSession(initial: DatabaseSessionState) : DatabaseSessionController {
    override val state = MutableStateFlow(initial)
    override fun reopen() = Unit
  }

  private class RecordingSink(private val deferDelivery: Boolean = false) : NotificationSink {
    val titles = mutableListOf<String>()
    private val pending = mutableListOf<Pending>()
    override fun send(title: String, body: String, onClick: () -> Unit) { titles += title }

    override fun sendAuthorized(title: String, body: String, authorizeAndSend: (() -> Unit) -> Unit, onClick: () -> Unit) {
      if (deferDelivery) pending += Pending(title, body, authorizeAndSend, onClick)
      else authorizeAndSend { send(title, body, onClick) }
    }

    fun deliverPending() {
      pending.forEach { item -> item.authorizeAndSend { send(item.title, item.body, item.onClick) } }
      pending.clear()
    }
  }

  private data class Pending(val title: String, val body: String, val authorizeAndSend: (() -> Unit) -> Unit, val onClick: () -> Unit)
}
