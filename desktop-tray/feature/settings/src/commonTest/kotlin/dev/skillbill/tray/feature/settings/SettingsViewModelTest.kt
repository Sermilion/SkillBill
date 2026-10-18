package dev.skillbill.tray.feature.settings

import dev.skillbill.tray.core.domain.model.AppSettings
import dev.skillbill.tray.core.domain.model.Problem
import dev.skillbill.tray.core.domain.model.ProblemCode
import dev.skillbill.tray.core.domain.model.Retention
import dev.skillbill.tray.core.domain.model.SettingsState
import dev.skillbill.tray.core.domain.repository.Outcome
import dev.skillbill.tray.core.testing.FakeSettingsRepository
import dev.skillbill.tray.core.testing.FakeWorkStoreStatusRepository
import dev.skillbill.tray.core.ui.FeedbackTone
import dev.skillbill.tray.core.ui.TransientEffects
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.take
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlin.test.assertTrue

@OptIn(ExperimentalCoroutinesApi::class)
class SettingsViewModelTest {
  private val dispatcher = StandardTestDispatcher()
  private val repository = FakeSettingsRepository()

  @BeforeTest
  fun setUp() {
    Dispatchers.setMain(dispatcher)
  }

  @AfterTest
  fun tearDown() {
    Dispatchers.resetMain()
  }

  private fun TestScope.viewModel(initial: SettingsState = SettingsState.Ready(AppSettings(databasePath = "/data/store.db"))): SettingsViewModel {
    repository.publish(initial)
    return SettingsViewModel(repository, FakeWorkStoreStatusRepository()).also { runCurrent() }
  }

  @Test
  fun `controls wait for the first persisted read and are withheld when it fails`() = runTest(dispatcher) {
    val vm = viewModel(SettingsState.Loading)
    assertEquals(SettingsReadiness.Loading, vm.state.value.readiness)

    val problem = Problem(ProblemCode.SETTINGS_CORRUPT, "Settings are corrupt")
    repository.publish(SettingsState.Failed(problem))
    runCurrent()
    assertEquals(SettingsReadiness.Unreadable(problem), vm.state.value.readiness)

    repository.publish(SettingsState.Ready(AppSettings(databasePath = "/data/store.db")))
    runCurrent()
    assertEquals(SettingsReadiness.Ready, vm.state.value.readiness)
    assertEquals("/data/store.db", vm.state.value.draftPath)
  }

  @Test
  fun `unreadable settings ignore every write so nothing overwrites them`() = runTest(dispatcher) {
    val vm = viewModel(SettingsState.Failed(Problem(ProblemCode.SETTINGS_CORRUPT, "Settings are corrupt")))

    vm.onAction(SettingsAction.RetentionChosen(Retention.WEEK))
    vm.onAction(SettingsAction.StartAtLoginChanged(false))
    vm.onAction(SettingsAction.NotificationToggled("blocked", false))
    vm.onAction(SettingsAction.SavePath)
    runCurrent()

    assertTrue(repository.updates.isEmpty())
  }

  @Test
  fun `a simple preference is written and the screen follows the persisted value`() = runTest(dispatcher) {
    val vm = viewModel()

    vm.onAction(SettingsAction.RetentionChosen(Retention.WEEK))
    vm.onAction(SettingsAction.NotificationToggled("blocked", false))
    runCurrent()

    assertEquals(Retention.WEEK, vm.state.value.settings.retention)
    assertEquals(false, vm.state.value.settings.notifications["blocked"])
    assertEquals(true, vm.state.value.settings.notifications["failed"])
  }

  @Test
  fun `a whitespace-only path is refused without writing and editing it clears the error`() = runTest(dispatcher) {
    val vm = viewModel()

    vm.onAction(SettingsAction.PathChanged("   "))
    vm.onAction(SettingsAction.SavePath)
    runCurrent()

    assertEquals(PathError.PathBlank, vm.state.value.pathError)
    assertTrue(repository.updates.isEmpty())

    vm.onAction(SettingsAction.PathChanged("/other.db"))
    assertNull(vm.state.value.pathError)
  }

  @Test
  fun `an empty path saves the runtime default and confirms with an effect`() = runTest(dispatcher) {
    val vm = viewModel()
    vm.onAction(SettingsAction.PathChanged(""))

    vm.onAction(SettingsAction.SavePath)
    runCurrent()

    assertNull(repository.updates.single().databasePath)
    assertEquals(listOf<SettingsEffect>(SettingsEffect.Saved), vm.effects.take(1).toList())
  }

  @Test
  fun `a save in flight shows as saving and a second save is ignored`() = runTest(dispatcher) {
    val vm = viewModel()
    val gate = CompletableDeferred<Outcome<AppSettings>>()
    repository.onUpdate = { gate.await() }
    vm.onAction(SettingsAction.PathChanged("/other.db"))

    vm.onAction(SettingsAction.SavePath)
    vm.onAction(SettingsAction.SavePath)
    runCurrent()

    assertTrue(vm.state.value.savingPath)
    assertEquals(1, repository.updates.size)

    gate.complete(Outcome.Value(AppSettings(databasePath = "/other.db")))
    runCurrent()

    assertTrue(!vm.state.value.savingPath)
    assertEquals("/other.db", vm.state.value.settings.databasePath)
  }

  @Test
  fun `a failed save becomes feedback that is acknowledged by id and keeps the draft`() = runTest(dispatcher) {
    val vm = viewModel()
    repository.onUpdate = { Outcome.Failure(Problem(ProblemCode.SETTINGS_UPDATE_FAILED, "disk full")) }
    vm.onAction(SettingsAction.PathChanged("/other.db"))

    vm.onAction(SettingsAction.SavePath)
    runCurrent()

    val feedback = vm.state.value.pendingFeedback.single()
    assertEquals(FeedbackTone.PROBLEM, feedback.tone)
    assertEquals("disk full", feedback.message)
    assertEquals("/other.db", vm.state.value.draftPath)
    assertTrue(!vm.state.value.savingPath)

    vm.onAction(SettingsAction.AcknowledgeFeedback(feedback.id))
    assertTrue(vm.state.value.pendingFeedback.isEmpty())
  }

  @Test
  fun `an unsaved draft survives a persisted change that does not touch the path`() = runTest(dispatcher) {
    val vm = viewModel()
    vm.onAction(SettingsAction.PathChanged("/typing.db"))

    vm.onAction(SettingsAction.StartAtLoginChanged(false))
    runCurrent()

    assertEquals("/typing.db", vm.state.value.draftPath)
    assertEquals(false, vm.state.value.settings.startAtLogin)
  }

  @Test
  fun `confirmations are bounded so a stalled collector cannot pile them up`() = runTest(dispatcher) {
    val vm = viewModel()

    repeat(TransientEffects.TRANSIENT_EFFECT_CAPACITY + 2) {
      vm.onAction(SettingsAction.PathChanged("/store-$it.db"))
      vm.onAction(SettingsAction.SavePath)
      runCurrent()
    }

    val delivered = vm.effects.take(TransientEffects.TRANSIENT_EFFECT_CAPACITY).toList()
    assertEquals(TransientEffects.TRANSIENT_EFFECT_CAPACITY, delivered.size)
    assertIs<SettingsEffect.Saved>(delivered.first())
  }
}
