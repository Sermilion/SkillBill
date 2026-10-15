package dev.skillbill.tray.composition

import dev.skillbill.tray.core.common.di.AppScope
import dev.skillbill.tray.core.domain.model.WorkIdentity
import dev.skillbill.tray.core.domain.repository.SettingsRepository
import dev.skillbill.tray.core.domain.repository.WorkBoardRepository
import dev.skillbill.tray.core.domain.repository.WorkControlRepository
import dev.skillbill.tray.core.domain.repository.WorkDetailRepository
import dev.skillbill.tray.core.domain.repository.WorkPolicyRepository
import dev.skillbill.tray.core.domain.repository.WorkStoreStatusRepository
import dev.skillbill.tray.feature.detail.DetailViewModel
import dev.skillbill.tray.feature.history.HistoryViewModel
import dev.skillbill.tray.feature.settings.SettingsViewModel
import dev.skillbill.tray.feature.work.TrayViewModel
import dev.skillbill.tray.feature.work.WorkViewModel
import me.tatarka.inject.annotations.Inject
import software.amazon.lastmile.kotlin.inject.anvil.SingleIn
import kotlin.time.Clock

/**
 * The one place the screens' ViewModels are built from the application graph. A ViewModel's lifetime belongs to the store
 * owner that holds it, so every call returns a new instance and the caller decides how long it lives.
 */
@Inject
@SingleIn(AppScope::class)
class ViewModelFactory(
  private val boards: WorkBoardRepository,
  private val details: WorkDetailRepository,
  private val controls: WorkControlRepository,
  private val policy: WorkPolicyRepository,
  private val settings: SettingsRepository,
  private val storeStatus: WorkStoreStatusRepository,
  private val clock: Clock,
) {
  fun work() = WorkViewModel(boards, settings)

  fun tray() = TrayViewModel(boards, policy, settings)

  fun history() = HistoryViewModel(boards, settings)

  fun detail(identity: WorkIdentity) = DetailViewModel(identity, details, controls, boards, clock)

  fun settings() = SettingsViewModel(settings, storeStatus)
}
