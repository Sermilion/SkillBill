package dev.skillbill.desktop.feature.settings

import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import dev.skillbill.desktop.core.ui.di.WindowScope
import dev.skillbill.desktop.core.ui.feedback.ToastQueue
import dev.skillbill.desktop.core.ui.navigation.AppNavigator
import dev.skillbill.desktop.core.ui.navigation.AppRoute
import dev.skillbill.desktop.core.ui.navigation.FeatureEntry
import dev.skillbill.desktop.core.ui.navigation.SettingsPageId
import dev.skillbill.desktop.core.ui.navigation.SettingsPageContribution
import dev.skillbill.desktop.core.ui.navigation.SettingsRoute
import dev.skillbill.desktop.core.ui.navigation.rememberEntryViewModel
import me.tatarka.inject.annotations.Inject
import me.tatarka.inject.annotations.IntoSet
import me.tatarka.inject.annotations.Provides
import software.amazon.lastmile.kotlin.inject.anvil.ContributesTo
import software.amazon.lastmile.kotlin.inject.anvil.SingleIn

@Inject
@SingleIn(WindowScope::class)
class SettingsFeatureEntry(
  private val pages: SettingsPages,
  private val toasts: ToastQueue,
  private val shell: SettingsShellActions,
  private val navigator: AppNavigator,
  private val viewModel: () -> SettingsViewModel,
) : FeatureEntry {
  override fun renders(route: AppRoute): Boolean = route is SettingsRoute

  override val settingsPages = listOf(
    SettingsPageContribution(SettingsPageId.App, "General", "App"),
    SettingsPageContribution(SettingsPageId.Notifications, "Notifications", "App"),
  )

  @Composable
  override fun Content(route: AppRoute) {
    val settingsRoute = route as SettingsRoute
    val model = rememberEntryViewModel("settings") { viewModel() }
    val focusManager = androidx.compose.ui.platform.LocalFocusManager.current
    LaunchedEffect(model) {
      model.effects.collect { effect ->
        when (effect) {
          is SettingsEffect.ShowToast -> toasts.show(effect.message)
          SettingsEffect.ClearFocus -> focusManager.clearFocus()
        }
      }
    }
    val state by model.state.collectAsState()
    val sessionState by model.sessionState.collectAsState()
    SettingsScreen(settingsRoute, state, sessionState, pages, shell, model::onAction) { page ->
      navigator.navigate(SettingsRoute(page))
    }
  }
}

@ContributesTo(WindowScope::class)
interface SettingsEntryBinding {
  @Provides
  @IntoSet
  fun settingsEntry(entry: SettingsFeatureEntry): FeatureEntry = entry
}
