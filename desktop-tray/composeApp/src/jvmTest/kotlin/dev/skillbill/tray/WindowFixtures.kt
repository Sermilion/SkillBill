package dev.skillbill.tray

import androidx.lifecycle.ViewModel
import dev.skillbill.tray.composition.EntryComponent
import dev.skillbill.tray.composition.EntryComponents
import dev.skillbill.tray.composition.EntryViewModels
import dev.skillbill.tray.composition.WindowComponent
import dev.skillbill.tray.core.domain.model.AppSettings
import dev.skillbill.tray.core.domain.model.SettingsState
import dev.skillbill.tray.core.testing.FakeAgentHomeRepository
import dev.skillbill.tray.core.testing.FakeSettingsRepository
import dev.skillbill.tray.core.testing.FakeSpecBrowserRepository
import dev.skillbill.tray.core.testing.FakeWorkBoardRepository
import dev.skillbill.tray.core.testing.FakeWorkControlRepository
import dev.skillbill.tray.core.testing.FakeWorkDetailRepository
import dev.skillbill.tray.core.testing.FakeWorkStoreStatusRepository
import dev.skillbill.tray.core.testing.TestClock
import dev.skillbill.tray.core.testing.fixtureInstant
import dev.skillbill.tray.core.ui.LaunchRequests
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob

/** The fakes one window's entries read from, and how many entry graphs the window has been asked for. */
class WindowFakes {
  val boards = FakeWorkBoardRepository()
  val details = FakeWorkDetailRepository()
  val settings = FakeSettingsRepository(SettingsState.Ready(AppSettings()))
  var entryComponentsCreated = 0
    private set

  private val launches = LaunchRequests(CoroutineScope(SupervisorJob() + Dispatchers.Main), FakeWorkControlRepository(), boards, TestClock(fixtureInstant))

  private fun viewModels() = EntryViewModels(
    boards = boards,
    details = details,
    controls = FakeWorkControlRepository(),
    settings = settings,
    storeStatus = FakeWorkStoreStatusRepository(),
    specs = FakeSpecBrowserRepository(),
    agentHomes = FakeAgentHomeRepository(),
    clock = TestClock(fixtureInstant),
    launches = launches,
  )

  /** A window graph with no generated code: each entry gets a fresh component over the same fakes. */
  fun windowComponent(): WindowComponent = object : WindowComponent {
    override val entries = EntryComponents(
      object : EntryComponent.Factory {
        override fun createEntryComponent(): EntryComponent {
          entryComponentsCreated++
          val viewModels = viewModels()
          return object : EntryComponent {
            override val viewModels: EntryViewModels = viewModels
          }
        }
      },
    )
  }
}

/** A ViewModel that records that its store let go of it. */
class ClearableViewModel : ViewModel() {
  var cleared = false
    private set

  override fun onCleared() {
    cleared = true
  }
}
