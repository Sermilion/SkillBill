package dev.skillbill.desktop.core.designsystem.usage

import androidx.compose.runtime.Immutable

@Immutable
data class UsageUiMeter(
  val id: String,
  val label: String,
  val detail: String,
  val valueText: String,
  val progress: Float? = null,
  val progressLabel: String? = null,
  val progressStateDescription: String? = null,
  val stateText: String? = null,
  val warning: Boolean = false,
  val exhausted: Boolean = false,
  val stale: Boolean = false,
)

@Immutable
data class UsageUiState(
  val provider: String,
  val account: String,
  val accountExplanation: String? = null,
  val contextLabel: String? = null,
  val source: String? = null,
  val observationAge: String? = null,
  val status: String,
  val reason: String? = null,
  val requestPermission: String? = null,
  val cardMeters: List<UsageUiMeter> = emptyList(),
  val detailMeters: List<UsageUiMeter> = emptyList(),
  val checking: Boolean = false,
  val stale: Boolean = false,
  val expired: Boolean = false,
  val sessionOnly: Boolean = false,
)
