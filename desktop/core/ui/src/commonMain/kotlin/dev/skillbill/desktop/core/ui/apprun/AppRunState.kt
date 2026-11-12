package dev.skillbill.desktop.core.ui.apprun

import kotlin.time.Instant

sealed interface AppRunState {
  data object Idle : AppRunState
  data class Building(val device: String, val step: String) : AppRunState
  data class Running(val device: String, val openedAt: Instant) : AppRunState
  data class BuildFailed(val device: String, val task: String) : AppRunState
  data class Contended(val device: String, val holderLabel: String) : AppRunState
  data object Stopped : AppRunState
  data class Problem(val message: String) : AppRunState
}
