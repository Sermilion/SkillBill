package dev.skillbill.desktop.core.data.qa

import dev.skillbill.desktop.core.common.Diagnostics
import dev.skillbill.desktop.core.common.HostEnvironment
import dev.skillbill.desktop.core.common.di.AppScope
import dev.skillbill.desktop.core.data.chat.ChatProcessLauncher
import dev.skillbill.desktop.core.data.session.DatabaseSessionManager
import dev.skillbill.desktop.core.domain.qa.QaDeviceDiscoveryRepository
import me.tatarka.inject.annotations.Provides
import software.amazon.lastmile.kotlin.inject.anvil.ContributesTo
import software.amazon.lastmile.kotlin.inject.anvil.SingleIn

@ContributesTo(AppScope::class)
interface AndroidQaDiscoveryProviders {
  @Provides
  @SingleIn(AppScope::class)
  fun qaDeviceDiscovery(
    host: HostEnvironment,
    sessions: DatabaseSessionManager,
    diagnostics: Diagnostics,
    launcher: ChatProcessLauncher,
  ): QaDeviceDiscoveryRepository = AndroidQaDeviceDiscovery(
    AndroidQaSdkLocator(host, diagnostics),
    sessions,
    diagnostics,
    AndroidQaCommandRunner(launcher, host, diagnostics),
  )
}
