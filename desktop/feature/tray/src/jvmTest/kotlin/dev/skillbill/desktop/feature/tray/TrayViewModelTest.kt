package dev.skillbill.desktop.feature.tray

import dev.skillbill.desktop.core.domain.settings.AppSettings
import dev.skillbill.desktop.core.domain.settings.TrayTab
import dev.skillbill.desktop.core.testing.FakeAppSettingsRepository
import dev.skillbill.desktop.core.testing.FakeWorkBoardRepository
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.setMain
import kotlinx.coroutines.test.resetMain
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.cancel
import kotlin.test.Test
import kotlin.test.assertEquals

class TrayViewModelTest {
  @OptIn(ExperimentalCoroutinesApi::class)
  @Test
  fun initialTabComesFromStoredTrayPreference() = runTest {
    Dispatchers.setMain(StandardTestDispatcher(testScheduler))
    try {
      val model = TrayViewModel(FakeWorkBoardRepository(), FakeAppSettingsRepository(AppSettings.DEFAULT.copy(trayLastTab = TrayTab.Phases)))
      assertEquals(TrayTab.Phases, model.state.first { it.tab == TrayTab.Phases }.tab)
      model.viewModelScope.cancel()
      runCurrent()
    } finally {
      Dispatchers.resetMain()
    }
  }
}
