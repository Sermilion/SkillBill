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
import dev.skillbill.desktop.core.designsystem.component.SbMenu
import dev.skillbill.desktop.core.designsystem.component.SbMenuEntry
import dev.skillbill.desktop.core.designsystem.component.SbPopover
import dev.skillbill.desktop.core.domain.model.ChatHistoryEntry
import dev.skillbill.desktop.core.domain.model.ChatHistoryRole
import dev.skillbill.desktop.core.ui.input.ShellLayer
import kotlin.time.Duration
import kotlin.time.Instant

internal const val RESUME_COMMAND = "/resume"

private const val LABEL_LENGTH = 60

/** The length of the draft's leading token while it spells `/resume` or the start of it, else null. */
internal fun commandTokenLength(draft: String): Int? {
  if (!draft.startsWith("/")) return null
  val token = draft.takeWhile { !it.isWhitespace() }
  return token.length.takeIf { RESUME_COMMAND.startsWith(token) }
}

/** Shows the leading `/resume` token in the accent color, so the operator sees the draft is a command and not a message. */
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

/** The chat's first operator message on one line, cut to fit a menu row. */
internal fun resumeLabel(entry: ChatHistoryEntry): String {
  val first = entry.lines.first { it.role == ChatHistoryRole.Operator }.text.lineSequence().joinToString(" ").trim()
  return if (first.length > LABEL_LENGTH) first.take(LABEL_LENGTH - 1).trimEnd() + "…" else first
}

/** The `/resume` picker: one row per saved chat of this execution, newest first. */
@Composable
internal fun ResumePicker(choices: List<ChatHistoryEntry>, now: Instant, onResume: (ChatHistoryEntry) -> Unit, onDismiss: () -> Unit) {
  ShellLayer(onDismiss)
  SbPopover(expanded = true, onDismissRequest = onDismiss, width = 340.dp) {
    val entries = if (choices.isEmpty()) {
      listOf(SbMenuEntry.Item("No saved chats for this execution", {}, enabled = false))
    } else {
      choices.map { entry ->
        SbMenuEntry.Item(resumeLabel(entry), { onResume(entry) }, endText = "${entry.agent.agentId} · ${savedAgo(entry.updatedAt, now)}")
      }
    }
    SbMenu(listOf(SbMenuEntry.Head("Resume a chat")) + entries)
  }
}

/** Shown while the draft is a prefix of `/resume`; clicking it opens the picker. */
@Composable
internal fun ResumeHint(onClick: () -> Unit) {
  val colors = SkillBillTheme.colors
  val type = SkillBillTheme.type
  Row(
    Modifier.pointerHoverIcon(PointerIcon.Hand).clickable(onClickLabel = "Resume a saved chat", onClick = onClick),
    horizontalArrangement = Arrangement.spacedBy(8.dp),
    verticalAlignment = Alignment.CenterVertically,
  ) {
    ChatPill(RESUME_COMMAND, mono = true)
    BasicText("Resume a saved chat of this execution", style = type.tSm.copy(color = colors.fgMuted))
  }
}
