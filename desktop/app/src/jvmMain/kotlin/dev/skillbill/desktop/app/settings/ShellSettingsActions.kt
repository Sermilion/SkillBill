package dev.skillbill.desktop.app.settings

import dev.skillbill.desktop.app.navigation.WindowNavigator
import dev.skillbill.desktop.core.ui.di.WindowScope
import dev.skillbill.desktop.core.ui.navigation.WorkRoute
import dev.skillbill.desktop.feature.settings.SettingsShellActions
import me.tatarka.inject.annotations.Inject
import software.amazon.lastmile.kotlin.inject.anvil.ContributesBinding
import software.amazon.lastmile.kotlin.inject.anvil.SingleIn

@Inject
@SingleIn(WindowScope::class)
@ContributesBinding(WindowScope::class)
class ShellSettingsActions(private val navigator: WindowNavigator) : SettingsShellActions {
  override fun backToApp() {
    if (!navigator.pop()) navigator.navigate(WorkRoute(null))
  }
}
