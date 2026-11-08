package dev.skillbill.desktop.core.designsystem.detail

import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import dev.skillbill.desktop.core.designsystem.SkillBillTheme
import dev.skillbill.desktop.core.designsystem.status.StatusKind

// Tables mirror the phase peek prototype's PEEK_TONE, PEEK_WORD, PEEK_STATUS and PEEK_SEV.
enum class PeekState { Done, Now, Fail, Wait, Skipped, Pending }

@Composable
fun peekTone(state: PeekState): Color {
  val colors = SkillBillTheme.colors
  return when (state) {
    PeekState.Done -> colors.stDone
    PeekState.Now -> colors.stActive
    PeekState.Fail -> colors.stFailed
    PeekState.Wait -> colors.stBlocked
    PeekState.Skipped -> colors.fgFaint
    PeekState.Pending -> colors.stPending
  }
}

fun peekWord(state: PeekState): String = when (state) {
  PeekState.Done -> "Done"
  PeekState.Now -> "Running"
  PeekState.Fail -> "Failed"
  PeekState.Wait -> "Blocked"
  PeekState.Skipped -> "Skipped"
  PeekState.Pending -> "Pending"
}

fun peekStatus(state: PeekState): StatusKind = when (state) {
  PeekState.Done -> StatusKind.Completed
  PeekState.Now -> StatusKind.Active
  PeekState.Fail -> StatusKind.Failed
  PeekState.Wait -> StatusKind.Blocked
  PeekState.Skipped -> StatusKind.Skipped
  PeekState.Pending -> StatusKind.Pending
}

@Composable
fun peekSeverityColor(severity: String): Color {
  val colors = SkillBillTheme.colors
  return when (severity.lowercase()) {
    "blocker" -> colors.stFailed
    "major", "high" -> colors.stBlocked
    "medium" -> colors.data1
    "minor", "low" -> colors.fgMuted
    else -> colors.fgFaint
  }
}
