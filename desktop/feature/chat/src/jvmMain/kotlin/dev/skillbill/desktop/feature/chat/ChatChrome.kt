package dev.skillbill.desktop.feature.chat

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicText
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import dev.skillbill.desktop.core.designsystem.LocalContentColor
import dev.skillbill.desktop.core.designsystem.SkillBillColors
import dev.skillbill.desktop.core.designsystem.SkillBillRadius
import dev.skillbill.desktop.core.designsystem.SkillBillTheme
import dev.skillbill.desktop.core.designsystem.component.LocalUiTicker
import dev.skillbill.desktop.core.designsystem.component.SbTooltip
import dev.skillbill.desktop.core.designsystem.icon.LucideIcons
import dev.skillbill.desktop.core.designsystem.icon.SbIcon
import dev.skillbill.desktop.core.designsystem.icon.SbIconSize
import dev.skillbill.desktop.core.domain.model.ChatPermissionMode
import kotlin.time.Duration
import kotlin.time.Instant

@Composable
internal fun ChatPill(
  text: String,
  modifier: Modifier = Modifier,
  tone: Color? = null,
  dot: Boolean = false,
  mono: Boolean = false,
  icon: ImageVector? = null,
  maxWidth: Dp = Dp.Unspecified,
) {
  val colors = SkillBillTheme.colors
  val type = SkillBillTheme.type
  val shape = RoundedCornerShape(11.dp)
  val ink = tone ?: colors.fg2
  Row(
    modifier.height(22.dp).widthIn(max = maxWidth)
      .then(if (tone != null) Modifier.background(tone.copy(alpha = .12f), shape) else Modifier.border(1.dp, colors.border, shape))
      .padding(horizontal = 8.dp),
    horizontalArrangement = Arrangement.spacedBy(6.dp),
    verticalAlignment = Alignment.CenterVertically,
  ) {
    if (dot) Box(Modifier.size(6.dp).background(ink, CircleShape))
    if (icon != null) CompositionLocalProvider(LocalContentColor provides colors.fgMuted) { SbIcon(icon, SbIconSize.Xs) }
    BasicText(
      text,
      style = (if (mono) type.monoSm else type.tSmMedium).copy(color = if (tone != null) ink else colors.fgMuted),
      maxLines = 1,
      overflow = TextOverflow.Ellipsis,
    )
  }
}

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
    else -> "The agent can inspect but never writes"
  }
  SbTooltip(hint) {
    BasicText(
      text,
      Modifier.background(tone.copy(alpha = .12f), RoundedCornerShape(SkillBillRadius.xs)).padding(horizontal = 6.dp, vertical = 1.dp),
      style = SkillBillTheme.type.t2xs.copy(color = tone),
    )
  }
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
