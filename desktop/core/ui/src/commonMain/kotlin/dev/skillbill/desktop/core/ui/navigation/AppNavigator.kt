package dev.skillbill.desktop.core.ui.navigation

/** Opens a route in the window, so a feature can open the detail or another view without depending on the shell. */
interface AppNavigator {
  fun navigate(route: AppRoute)
}
