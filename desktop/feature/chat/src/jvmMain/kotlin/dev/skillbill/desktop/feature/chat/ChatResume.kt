package dev.skillbill.desktop.feature.chat

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.text.BasicText
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.pointer.PointerIcon
import androidx.compose.ui.input.pointer.pointerHoverIcon
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.OffsetMapping
import androidx.compose.ui.text.input.TransformedText
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.dp
import dev.skillbill.desktop.core.designsystem.SkillBillColors
import dev.skillbill.desktop.core.designsystem.SkillBillTheme
import dev.skillbill.desktop.core.domain.model.ChatAgent
import java.io.File
import kotlin.time.Duration
import kotlin.time.Instant

internal const val RESUME_COMMAND = "/resume"
internal const val CLEAR_COMMAND = "/clear"

/** The chat's commands with the hint the composer shows while the draft spells one or the start of one. */
internal val CHAT_COMMANDS = linkedMapOf(
  RESUME_COMMAND to "Resume an agent session in this worktree",
  CLEAR_COMMAND to "Start a new conversation with the current execution state",
)

/** The length of the draft's leading token while it spells a chat command or the start of one, else null. */
internal fun commandTokenLength(draft: String): Int? {
  if (!draft.startsWith("/")) return null
  val token = draft.takeWhile { !it.isWhitespace() }
  return token.length.takeIf { matchingCommands(token).isNotEmpty() }
}

/** The chat commands that start with [token], in [CHAT_COMMANDS] order. */
internal fun matchingCommands(token: String): List<String> = CHAT_COMMANDS.keys.filter { it.startsWith(token) }

/** Shows the leading command token in the accent color, so the operator sees the draft is a command and not a message. */
internal data class CommandHighlight(private val colors: SkillBillColors) : VisualTransformation {
  override fun filter(text: AnnotatedString): TransformedText {
    val length = commandTokenLength(text.text) ?: return TransformedText(text, OffsetMapping.Identity)
    val styled = buildAnnotatedString {
      withStyle(SpanStyle(color = colors.accentText, background = colors.accentTint, fontWeight = FontWeight.Medium)) {
        append(text.subSequence(0, length))
      }
      append(text.subSequence(length, text.length))
    }
    return TransformedText(styled, OffsetMapping.Identity)
  }
}

/** How long ago [savedAt] was, in the largest whole unit. */
internal fun savedAgo(savedAt: Instant, now: Instant): String {
  val age = (now - savedAt).coerceAtLeast(Duration.ZERO)
  return when {
    age.inWholeMinutes < 1 -> "just now"
    age.inWholeHours < 1 -> "${age.inWholeMinutes}m ago"
    age.inWholeDays < 1 -> "${age.inWholeHours}h ago"
    else -> "${age.inWholeDays}d ago"
  }
}

/** The profile directory's name without its dot, such as `codex-api`, or the agent id when it runs as the app is configured. */
internal fun agentName(agent: ChatAgent): String = agent.profile?.let { File(it).name.removePrefix(".") } ?: agent.agentId

/** Shown while the draft is a prefix of [command]; clicking it runs the command. */
@Composable
internal fun CommandHint(command: String, onClick: () -> Unit) {
  val colors = SkillBillTheme.colors
  val type = SkillBillTheme.type
  Row(
    Modifier.pointerHoverIcon(PointerIcon.Hand).clickable(onClickLabel = CHAT_COMMANDS.getValue(command), onClick = onClick),
    horizontalArrangement = Arrangement.spacedBy(8.dp),
    verticalAlignment = Alignment.CenterVertically,
  ) {
    ChatPill(command, mono = true)
    BasicText(CHAT_COMMANDS.getValue(command), style = type.tSm.copy(color = colors.fgMuted))
  }
}
