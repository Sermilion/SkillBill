package dev.skillbill.desktop.app.tray

import dev.skillbill.desktop.core.common.di.AppScope
import dev.skillbill.desktop.app.host.HostIntents
import dev.skillbill.desktop.app.notifications.TransitionNotifier
import dev.skillbill.desktop.feature.tray.TrayViewModel
import software.amazon.lastmile.kotlin.inject.anvil.ContributesTo

@ContributesTo(AppScope::class)
interface TrayBindings {
  val trayViewModel: () -> TrayViewModel
  val transitionNotifier: TransitionNotifier
  val trayAvailability: TrayAvailability
  val hostIntents: HostIntents
}
