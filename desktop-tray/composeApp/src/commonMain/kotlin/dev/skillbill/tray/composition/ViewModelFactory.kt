package dev.skillbill.tray.composition

import dev.skillbill.tray.core.common.di.AppScope
import dev.skillbill.tray.core.domain.repository.SettingsRepository
import dev.skillbill.tray.core.domain.repository.WorkBoardRepository
import dev.skillbill.tray.core.domain.repository.WorkPolicyRepository
import dev.skillbill.tray.feature.work.TrayViewModel
import me.tatarka.inject.annotations.Inject
import software.amazon.lastmile.kotlin.inject.anvil.SingleIn

/**
 * Builds the one ViewModel the application owns for its whole run: the tray popover's. Every screen entry builds its ViewModels
 * from its own [EntryViewModels] instead, so their lifetime is that entry's.
 */
@Inject
@SingleIn(AppScope::class)
class ViewModelFactory(
  private val boards: WorkBoardRepository,
  private val policy: WorkPolicyRepository,
  private val settings: SettingsRepository,
) {
  fun tray() = TrayViewModel(boards, policy, settings)
}
