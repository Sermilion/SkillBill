package dev.skillbill.desktop.feature.settings

interface LoginItemRegistrar {
  suspend fun setEnabled(enabled: Boolean): LoginItemResult
}

sealed interface LoginItemResult {
  data object Applied : LoginItemResult

  data class Failed(val reason: String) : LoginItemResult
}
