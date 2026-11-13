package dev.skillbill.desktop.app.usage

import androidx.compose.runtime.snapshots.Snapshot
import dev.skillbill.desktop.app.navigation.WindowNavigator
import dev.skillbill.desktop.core.designsystem.usage.UsageUiState
import dev.skillbill.desktop.core.domain.model.ChatAgent
import dev.skillbill.desktop.core.domain.model.DetailSections
import dev.skillbill.desktop.core.domain.model.DetailState
import dev.skillbill.desktop.core.domain.model.ReviewFindingsAction
import dev.skillbill.desktop.core.domain.model.WorkItem
import dev.skillbill.desktop.core.domain.session.DatabaseSessionController
import dev.skillbill.desktop.core.domain.session.DatabaseSessionState
import dev.skillbill.desktop.core.domain.usage.UsageRepository
import dev.skillbill.desktop.core.domain.usage.model.UsageDemand
import dev.skillbill.desktop.core.domain.usage.model.UsageDemandResult
import dev.skillbill.desktop.core.domain.usage.model.UsageProfileLookup
import dev.skillbill.desktop.core.domain.usage.model.UsageProvider
import dev.skillbill.desktop.core.domain.usage.model.UsageRefreshAvailability
import dev.skillbill.desktop.core.domain.usage.model.UsageRefreshResult
import dev.skillbill.desktop.core.domain.usage.model.UsageRequest
import dev.skillbill.desktop.core.domain.usage.model.UsageResult
import dev.skillbill.desktop.core.domain.usage.model.UsageSessionStatus
import dev.skillbill.desktop.core.domain.usage.model.UsageUnavailableCode
import dev.skillbill.desktop.core.domain.usage.model.UsageUnavailableReason
import dev.skillbill.desktop.core.testing.FakeAppSettingsRepository
import dev.skillbill.desktop.core.testing.FakeExecutionChatRepository
import dev.skillbill.desktop.core.testing.FakeLastUsageStore
import dev.skillbill.desktop.core.testing.FakeWorkDetailRepository
import dev.skillbill.desktop.core.testing.RecordingDiagnostics
import dev.skillbill.desktop.core.testing.workItem
import dev.skillbill.desktop.core.ui.navigation.DetailRoute
import dev.skillbill.desktop.core.ui.navigation.SettingsPageId
import dev.skillbill.desktop.core.ui.navigation.SettingsRoute
import dev.skillbill.desktop.core.ui.navigation.WindowCoroutineScope
import dev.skillbill.desktop.core.ui.navigation.WorkRoute
import dev.skillbill.desktop.core.ui.pane.ChatCommands
import dev.skillbill.desktop.core.ui.selection.SelectionHolder
import dev.skillbill.desktop.core.ui.usage.model.SelectedNewTaskUsageContext
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withContext
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertSame
import kotlin.test.assertTrue
import kotlin.time.Instant

@OptIn(ExperimentalCoroutinesApi::class)
class UsageSidebarModelTest {
  @Test
  fun `busy changes preserve the selected context result popup and lease`() = runTest {
    val fixture = Fixture(this)
    runCurrent()
    val before = fixture.model.view.value
    val lease = fixture.usage.leases.values.single()
    val selected = fixture.chat.selectedNewTaskUsageContext.value as SelectedNewTaskUsageContext.Ready

    fixture.chat.selectedNewTaskUsageContext.value = selected.copy(activeWork = true)
    runCurrent()

    assertTrue(fixture.usage.demands.values.single().active)
    assertSame(lease, fixture.usage.leases.values.single())
    assertEquals(before.result, fixture.model.view.value.result)
    assertEquals(before.popupDismissalToken, fixture.model.view.value.popupDismissalToken)
  }

  @Test
  fun `detail header refresh keeps its context while updating activity`() = runTest {
    val fixture = Fixture(this)
    val item = workItem("run").copy(agentId = "claude", running = false)
    val detail = MutableStateFlow(DetailState(item, DetailSections.Pending))
    fixture.details.details = mapOf(item.id to detail)
    fixture.navigator.navigate(DetailRoute(item))
    runCurrent()
    val before = fixture.model.view.value
    val lease = fixture.usage.leases.values.single()

    detail.value = DetailState(item.copy(title = "Updated title", running = true), DetailSections.Pending)
    runCurrent()

    assertTrue(fixture.usage.demands.values.single().active)
    assertSame(lease, fixture.usage.leases.values.single())
    assertEquals(before.result, fixture.model.view.value.result)
    assertEquals(before.popupDismissalToken, fixture.model.view.value.popupDismissalToken)
  }

  @Test
  fun `window cancellation releases only its own demand and completes cleanup`() = runTest {
    val fixture = Fixture(this)
    runCurrent()
    val demand = fixture.usage.demands.values.single()
    fixture.usage.acquireDemand(demand.copy(ownerId = "other-window"))

    fixture.windowJob.cancel()
    runCurrent()

    assertTrue(fixture.windowJob.isCancelled)
    assertTrue(fixture.windowJob.isCompleted)
    assertEquals(setOf("other-window"), fixture.usage.demands.keys)
    assertFalse(fixture.model.view.value.demandEligible)
  }

  @Test
  fun `observer failure releases the lease and reports unavailable state`() = runTest {
    val fixture = Fixture(this)
    val fail = CompletableDeferred<Unit>()
    fixture.usage.observations = {
      flow {
        emit(fixture.usage.result)
        fail.await()
        throw IllegalStateException("source failed")
      }
    }
    runCurrent()
    assertTrue(fixture.model.view.value.demandEligible)

    fail.complete(Unit)
    runCurrent()

    assertTrue(fixture.usage.demands.isEmpty())
    assertFalse(fixture.model.view.value.demandEligible)
    assertEquals("Usage observation failed", fixture.model.view.value.unavailableReason)
    assertTrue("usage_demand_observation_failed" in fixture.diagnostics.categories)
  }

  @Test
  fun `queued manual selection cannot publish on a new Settings entry`() = runTest {
    val fixture = Fixture(this)
    fixture.navigator.navigate(WorkRoute(null))
    runCurrent()
    fixture.model.chooseManualProvider("claude")
    fixture.navigator.navigate(SettingsRoute(SettingsPageId.App))
    Snapshot.sendApplyNotifications()
    runCurrent()

    assertEquals(UsageProvider.Codex, fixture.model.view.value.provider)
    assertTrue(fixture.usage.demands.isEmpty())
    assertFalse(fixture.usage.acquired.any { it.request.profile.provider == UsageProvider.Claude })
  }

  @Test
  fun `late profile lookup cannot publish after a database switch`() = runTest {
    val fixture = Fixture(this)
    val lookup = CompletableDeferred<UsageProfileLookup>()
    fixture.usage.profileObservations = {
      flow { emit(withContext(NonCancellable) { lookup.await() }) }
    }
    runCurrent()

    fixture.session.state.value = DatabaseSessionState.Open("/other-db", 2)
    runCurrent()
    lookup.complete(UsageProfileLookup(UsageProvider.Codex, null, 1))
    runCurrent()

    assertEquals(UsageProvider.Codex, fixture.model.view.value.provider)
    assertTrue(fixture.usage.demands.isEmpty())
    assertTrue(fixture.usage.acquired.isEmpty())
  }

  @Test
  fun `a database switch keeps the agent and its reading on the card`() = runTest {
    val fixture = Fixture(this)
    val reading = UsageResult.SignInRequired(fixture.usage.reason)
    fixture.usage.observations = { MutableStateFlow(reading) }
    runCurrent()
    val tokenBefore = fixture.model.view.value.popupDismissalToken

    fixture.session.state.value = DatabaseSessionState.Open("/other-db", 2)
    runCurrent()

    assertEquals(UsageProvider.Codex, fixture.model.view.value.provider)
    assertEquals(reading, fixture.model.view.value.result)
    assertTrue(fixture.model.view.value.popupDismissalToken > tokenBefore)
    assertTrue(fixture.usage.demands.isEmpty())
  }

  @Test
  fun `a draft without an agent shows the agent the card showed last`() = runTest {
    val fixture = Fixture(this, lastAgent = "claude", context = SelectedNewTaskUsageContext.Absent(1))
    runCurrent()

    assertEquals(UsageProvider.Claude, fixture.model.view.value.provider)
    assertEquals(UsageProvider.Claude, fixture.usage.demands.values.single().request.profile.provider)
  }

  @Test
  fun `a pending draft with nothing remembered falls back to the default agent, then Codex`() = runTest {
    val withDefault = Fixture(this, context = SelectedNewTaskUsageContext.Pending(1))
    withDefault.settings.setDefaultAgent("claude")
    runCurrent()
    assertEquals(UsageProvider.Claude, withDefault.model.view.value.provider)

    // A default agent without a usage source is skipped.
    val cursorDefault = Fixture(this, context = SelectedNewTaskUsageContext.Pending(1))
    cursorDefault.settings.setDefaultAgent("cursor")
    runCurrent()
    assertEquals(UsageProvider.Codex, cursorDefault.model.view.value.provider)
  }

  @Test
  fun `Settings at launch still names an agent but holds no demand`() = runTest {
    val fixture = Fixture(this, lastAgent = "claude")
    fixture.navigator.navigate(SettingsRoute(SettingsPageId.App))
    fixture.model.setSidebarFacts(settings = true, inlineVisible = true, drawerIsOpen = false)
    runCurrent()

    assertEquals(UsageProvider.Claude, fixture.model.view.value.provider)
    assertTrue(fixture.usage.demands.isEmpty())
  }

  @Test
  fun `the card remembers the agent it shows`() = runTest {
    val fixture = Fixture(this)
    runCurrent()
    assertEquals("codex", fixture.lastUsage.agent)

    fixture.model.chooseManualProvider("claude")
    runCurrent()
    assertEquals("claude", fixture.lastUsage.agent)
  }

  @Test
  fun `live profile changes replace demand without context reselection`() = runTest {
    val fixture = Fixture(this)
    val profile = MutableStateFlow(UsageProfileLookup(UsageProvider.Claude, "/home/a", 1))
    fixture.usage.profileObservations = { profile }
    fixture.chat.selectedNewTaskUsageContext.value = SelectedNewTaskUsageContext.Ready(
      1, ChatAgent("claude", null, null), "chat-session", false,
    )
    runCurrent()
    val originalLease = fixture.usage.leases.values.single()
    profile.value = UsageProfileLookup(UsageProvider.Claude, "/home/b", 2)
    runCurrent()

    assertEquals("/home/b", fixture.usage.demands.values.single().request.profile.configurationReference)
    assertTrue(originalLease !in fixture.usage.leases.values)
  }

  @Test
  fun `a pending context keeps the last provider and its lease`() = runTest {
    val fixture = Fixture(this)
    runCurrent()
    val lease = fixture.usage.leases.values.single()

    fixture.chat.selectedNewTaskUsageContext.value = SelectedNewTaskUsageContext.Pending(2)
    runCurrent()

    assertEquals(UsageProvider.Codex, fixture.model.view.value.provider)
    assertNull(fixture.model.view.value.nameOnlyAgent)
    assertSame(lease, fixture.usage.leases.values.single())
  }

  @Test
  fun `an agent without a usage source shows only its name until one with usage returns`() = runTest {
    val fixture = Fixture(this)
    runCurrent()

    fixture.chat.selectedNewTaskUsageContext.value = SelectedNewTaskUsageContext.Ready(2, ChatAgent("cursor", null, null), null, false)
    runCurrent()
    assertEquals("cursor", fixture.model.view.value.nameOnlyAgent)
    assertEquals(UsageUiState(provider = "Cursor", agentId = "cursor", nameOnly = true), fixture.model.view.value.toUsageUiState(Instant.fromEpochSeconds(0)))
    assertTrue(fixture.usage.demands.isEmpty())

    fixture.chat.selectedNewTaskUsageContext.value = SelectedNewTaskUsageContext.Ready(3, ChatAgent("junie", null, null), null, false)
    runCurrent()
    assertEquals("junie", fixture.model.view.value.nameOnlyAgent)
    assertEquals("Unavailable agent", fixture.model.view.value.toUsageUiState(Instant.fromEpochSeconds(0)).provider)

    fixture.chat.selectedNewTaskUsageContext.value = SelectedNewTaskUsageContext.Ready(4, ChatAgent("codex", "actual-model", null), "chat-session", false)
    runCurrent()
    assertNull(fixture.model.view.value.nameOnlyAgent)
    assertEquals(UsageProvider.Codex, fixture.model.view.value.provider)
    assertEquals(UsageProvider.Codex, fixture.usage.demands.values.single().request.profile.provider)
  }

  @Test
  fun `a provider picked during a Cursor chat holds until the chat changes agent`() = runTest {
    val fixture = Fixture(this, context = SelectedNewTaskUsageContext.Ready(1, ChatAgent("cursor", null, null), null, false))
    runCurrent()
    assertEquals("cursor", fixture.model.view.value.nameOnlyAgent)

    fixture.model.chooseManualProvider("claude")
    runCurrent()
    assertNull(fixture.model.view.value.nameOnlyAgent)
    assertEquals(UsageProvider.Claude, fixture.model.view.value.provider)

    fixture.chat.selectedNewTaskUsageContext.value = SelectedNewTaskUsageContext.Ready(2, ChatAgent("cursor", null, null), "next", false)
    runCurrent()
    assertEquals(UsageProvider.Claude, fixture.model.view.value.provider)
    assertNull(fixture.model.view.value.nameOnlyAgent)

    fixture.chat.selectedNewTaskUsageContext.value = SelectedNewTaskUsageContext.Ready(3, ChatAgent("junie", null, null), null, false)
    runCurrent()
    assertEquals("junie", fixture.model.view.value.nameOnlyAgent)
  }

  @Test
  fun `the board shows usage again after a Cursor chat`() = runTest {
    val fixture = Fixture(this, context = SelectedNewTaskUsageContext.Ready(1, ChatAgent("cursor", null, null), null, false))
    runCurrent()
    assertEquals("cursor", fixture.model.view.value.nameOnlyAgent)

    fixture.navigator.navigate(WorkRoute(null))
    Snapshot.sendApplyNotifications()
    runCurrent()

    assertNull(fixture.model.view.value.nameOnlyAgent)
    assertEquals(UsageProvider.Codex, fixture.model.view.value.provider)
    assertEquals(1, fixture.usage.demands.size)
  }

  @Test
  fun `a manual pick holds in a run context until the agent changes`() = runTest {
    val fixture = Fixture(this)
    runCurrent()
    val tokenBefore = fixture.model.view.value.popupDismissalToken

    fixture.model.chooseManualProvider("claude")
    runCurrent()
    assertEquals(UsageProvider.Claude, fixture.model.view.value.provider)
    assertEquals(tokenBefore, fixture.model.view.value.popupDismissalToken)

    val selected = fixture.chat.selectedNewTaskUsageContext.value as SelectedNewTaskUsageContext.Ready
    fixture.chat.selectedNewTaskUsageContext.value = selected.copy(selectionToken = 9, logicalSessionId = "next-session")
    runCurrent()
    assertEquals(UsageProvider.Claude, fixture.model.view.value.provider)

    fixture.chat.selectedNewTaskUsageContext.value = SelectedNewTaskUsageContext.Ready(10, ChatAgent("cursor", null, null), null, false)
    runCurrent()
    assertEquals("cursor", fixture.model.view.value.nameOnlyAgent)
  }

  @Test
  fun `the default agent toggles on and off from the usage popup`() = runTest {
    val fixture = Fixture(this)
    runCurrent()

    fixture.model.toggleDefaultAgent("claude")
    runCurrent()
    assertEquals("claude", fixture.settings.settings.value.defaultAgentId)
    assertEquals("claude", fixture.model.defaultAgent.value)

    fixture.model.toggleDefaultAgent("claude")
    runCurrent()
    assertNull(fixture.settings.settings.value.defaultAgentId)
    assertNull(fixture.model.defaultAgent.value)
  }

  @Test
  fun `a failed read does not replace the reading already shown`() = runTest {
    val fixture = Fixture(this)
    val results = MutableStateFlow<UsageResult>(UsageResult.SignInRequired(fixture.usage.reason))
    fixture.usage.observations = { results }
    runCurrent()

    results.value = UsageResult.TransientFailure(fixture.usage.reason)
    runCurrent()

    assertTrue(fixture.model.view.value.result is UsageResult.SignInRequired)
  }

  private class Fixture(
    test: TestScope,
    lastAgent: String? = null,
    context: SelectedNewTaskUsageContext = SelectedNewTaskUsageContext.Ready(1, ChatAgent("codex", "actual-model", null), "chat-session", false),
  ) {
    val windowJob = Job(test.backgroundScope.coroutineContext[Job])
    val session = TestSession()
    val navigator = WindowNavigator(session, SelectionHolder())
    val chat = TestChat(context)
    val lastUsage = FakeLastUsageStore(lastAgent)
    val usage = TestUsageRepository()
    val diagnostics = RecordingDiagnostics()
    val details = FakeWorkDetailRepository()
    val settings = FakeAppSettingsRepository()
    val model = UsageSidebarModel(
      chat, FakeExecutionChatRepository(), details, navigator, session, usage, diagnostics, settings, lastUsage,
      WindowCoroutineScope(CoroutineScope(test.backgroundScope.coroutineContext + windowJob)),
    ).apply {
      setWindowFacts(visible = true, isMinimized = false, focused = true)
      setSidebarFacts(settings = false, inlineVisible = true, drawerIsOpen = false)
      setCardHostAttached(true)
    }
  }
}

private class TestSession : DatabaseSessionController {
  override val state = MutableStateFlow<DatabaseSessionState>(DatabaseSessionState.Open("/db", 1))
  override fun reopen() = Unit
}

private class TestChat(context: SelectedNewTaskUsageContext) : ChatCommands {
  override val shownChatId = MutableStateFlow<String?>(null)
  override val newTaskDraftOpen = MutableStateFlow(false)
  override val newTaskOpenChats = MutableStateFlow(1)
  override val selectedNewTaskUsageContext = MutableStateFlow(context)
  override fun toggleChat(item: WorkItem?) = Unit
  override fun toggleChatPane() = Unit
  override fun runReviewAction(item: WorkItem, action: ReviewFindingsAction) = Unit
  override fun runVerifyFixes(item: WorkItem, brief: String) = Unit
  override fun prefillGoalIntake(repoRoot: String, branch: String, text: String) = false
  override fun openChat(item: WorkItem) = Unit
  override fun closeChats() = Unit
}

private class TestUsageRepository : UsageRepository {
  val demands = mutableMapOf<String, UsageDemand>()
  val acquired = mutableListOf<UsageDemand>()
  val leases = mutableMapOf<String, Any>()
  val reason = UsageUnavailableReason(UsageUnavailableCode.ProviderUnavailable)
  val result = UsageResult.Unavailable(reason)
  var lookup: suspend (UsageProvider) -> UsageProfileLookup = { UsageProfileLookup(it, null, 1) }
  var profileObservations: (UsageProvider) -> Flow<UsageProfileLookup> = { provider ->
    flow { emit(lookup(provider)) }
  }
  var observations: () -> Flow<UsageResult> = { MutableStateFlow(result) }

  override suspend fun profileLookup(provider: UsageProvider, selectedProfile: String?, logicalSessionId: String?) = lookup(provider)
  override fun observeProfileLookup(provider: UsageProvider, selectedProfile: String?, logicalSessionId: String?) =
    profileObservations(provider)
  override fun observe(request: UsageRequest) = observations()
  override fun observeDemand(ownerId: String) = observations()
  override fun acquireDemand(demand: UsageDemand): UsageDemandResult {
    acquired += demand
    demands[demand.ownerId] = demand
    leases[demand.ownerId] = Any()
    return UsageDemandResult.Acquired(demand)
  }
  override fun updateDemand(demand: UsageDemand): UsageDemandResult {
    demands[demand.ownerId] = demand
    return UsageDemandResult.Acquired(demand)
  }
  override fun releaseDemand(ownerId: String): UsageDemandResult {
    demands.remove(ownerId)
    leases.remove(ownerId)
    return UsageDemandResult.Released
  }
  override fun refresh(request: UsageRequest) = UsageRefreshResult.Disabled(reason)
  override fun checkDue(ownerId: String) = UsageRefreshResult.Disabled(reason)
  override fun refreshAvailability(request: UsageRequest) = flowOf(UsageRefreshAvailability.Disabled(reason))
  override fun recheckSignInAvailability(request: UsageRequest) = flowOf(UsageRefreshAvailability.Disabled(reason))
  override fun recheckSignIn(request: UsageRequest) = UsageRefreshResult.Disabled(reason)
  override fun sessionStatus(request: UsageRequest) = UsageSessionStatus.Unavailable
}
