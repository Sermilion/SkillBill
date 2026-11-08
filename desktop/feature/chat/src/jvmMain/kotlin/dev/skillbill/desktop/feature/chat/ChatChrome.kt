package dev.skillbill.desktop.feature.chat

import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import dev.skillbill.desktop.core.designsystem.SkillBillColors
import dev.skillbill.desktop.core.designsystem.SkillBillTheme
import dev.skillbill.desktop.core.designsystem.component.LocalUiTicker
import dev.skillbill.desktop.core.designsystem.component.SbTag
import dev.skillbill.desktop.core.designsystem.icon.LucideIcons
import dev.skillbill.desktop.core.domain.model.ChatPermissionMode
import kotlin.time.Duration
import kotlin.time.Instant

/** Read-only or act, the mode a turn or action runs in. */
@Composable
internal fun ModeBadge(mode: ChatPermissionMode, pause: Boolean = false) {
  val colors = SkillBillTheme.colors
  val tone = modeTone(mode, colors)
  val text = when {
    pause -> "Pause + act"
    mode == ChatPermissionMode.Act -> "Act"
    else -> "Read-only"
  }
  val hint = when {
    pause -> "Pauses the running execution, acts, then resumes it"
    mode == ChatPermissionMode.Act -> "The agent may change files and the remote"
    else -> "The execution is running; changes can collide with it"
  }
  SbTag(text, tone = tone, tooltip = hint)
}

internal fun modeTone(mode: ChatPermissionMode, colors: SkillBillColors): Color =
  if (mode == ChatPermissionMode.Act) colors.stBlocked else colors.stPlanning

internal fun stateTone(state: ExecutionState, colors: SkillBillColors): Color = when (state) {
  ExecutionState.Blocked, ExecutionState.Stalled -> colors.stBlocked
  ExecutionState.Failed -> colors.stFailed
  ExecutionState.Running -> colors.stActive
  ExecutionState.Done -> colors.stDone
  ExecutionState.Stopped -> colors.stPaused
}

internal fun stateIcon(state: ExecutionState): ImageVector = when (state) {
  ExecutionState.Blocked, ExecutionState.Failed -> LucideIcons.alert
  ExecutionState.Stalled -> LucideIcons.clock
  ExecutionState.Running -> LucideIcons.play
  ExecutionState.Done -> LucideIcons.checkCircle
  ExecutionState.Stopped -> LucideIcons.pause
}

internal fun toolIcon(name: String): ImageVector = when (name.lowercase()) {
  "bash", "shell", "exec", "run" -> LucideIcons.terminal
  "read", "notebookread" -> LucideIcons.fileText
  "edit", "multiedit", "write", "notebookedit", "apply_patch" -> LucideIcons.pencil
  "grep", "glob", "search", "ls" -> LucideIcons.search
  "webfetch", "websearch" -> LucideIcons.external
  "task", "agent" -> LucideIcons.bot
  else -> LucideIcons.zap
}

/** "0.4s" and "9.2s" under ten seconds, then "21s", then "1m 48s". */
internal fun durationLabel(duration: Duration): String {
  val millis = duration.inWholeMilliseconds.coerceAtLeast(0)
  return when {
    millis < 10_000 -> "${millis / 1000}.${millis % 1000 / 100}s"
    millis < 60_000 -> "${millis / 1000}s"
    else -> "${millis / 60_000}m ${millis % 60_000 / 1000}s"
  }
}

/** The time since [since], ticking once a second while shown. */
@Composable
internal fun liveElapsed(since: Instant): Duration {
  val ticker = LocalUiTicker.current
  DisposableEffect(ticker) {
    ticker.retain()
    onDispose { ticker.release() }
  }
  val now by ticker.now
  return now - since
}
