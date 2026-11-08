package dev.skillbill.desktop.core.designsystem.usage

import androidx.compose.runtime.Immutable

/** One allowance window, such as "5 hour" or "Weekly". [progress] is the used fraction; the track colors by what is left. */
@Immutable
data class UsageUiMeter(
  val id: String,
  val label: String,
  val valueText: String,
  val progress: Float? = null,
  /** Time until the window resets, such as "5d 2h"; "now" once the reset time has passed. */
  val resetIn: String? = null,
  /** Local clock time of the reset, such as "14:30" today or "Fri 14:30" later. */
  val resetAt: String? = null,
)

/** The agent name and its meters; [message] replaces the meters when there are none, such as "Not signed in". */
@Immutable
data class UsageUiState(
  val provider: String,
  /** Agent id such as "codex"; colors the name like the agent's avatar. */
  val agentId: String? = null,
  val meters: List<UsageUiMeter> = emptyList(),
  val message: String? = null,
)
