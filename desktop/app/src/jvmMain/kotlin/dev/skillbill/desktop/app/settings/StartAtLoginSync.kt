package dev.skillbill.desktop.app.settings

import dev.skillbill.desktop.core.common.ApplicationCoroutineScope
import dev.skillbill.desktop.core.common.Diagnostics
import dev.skillbill.desktop.core.common.di.AppScope
import dev.skillbill.desktop.core.domain.settings.AppSettingsRepository
import dev.skillbill.desktop.feature.settings.LoginItemRegistrar
import dev.skillbill.desktop.feature.settings.LoginItemResult
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.launch
import me.tatarka.inject.annotations.Inject
import software.amazon.lastmile.kotlin.inject.anvil.SingleIn

@Inject
@SingleIn(AppScope::class)
class StartAtLoginSync(
  private val settings: AppSettingsRepository,
  private val loginItems: LoginItemRegistrar,
  private val scope: ApplicationCoroutineScope,
  private val diagnostics: Diagnostics,
) {
  fun start() {
    scope.scope.launch {
      try {
        if (!settings.settings.first().startAtLogin) return@launch
        if (loginItems.setEnabled(true) is LoginItemResult.Failed) diagnostics.record("login_start_sync_failed")
      } catch (cancelled: CancellationException) {
        throw cancelled
      } catch (failure: Exception) {
        diagnostics.record("login_start_sync_failed")
      }
    }
  }
}
