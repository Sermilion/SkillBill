package dev.skillbill.desktop.feature.changes

import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.snap
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.hoverable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsFocusedAsState
import androidx.compose.foundation.interaction.collectIsHoveredAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.IntrinsicSize
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicText
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.isCtrlPressed
import androidx.compose.ui.input.key.isMetaPressed
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.error
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.dp
import dev.skillbill.desktop.core.designsystem.SkillBillColors
import dev.skillbill.desktop.core.designsystem.SkillBillTheme
import dev.skillbill.desktop.core.designsystem.detail.SbBox
import dev.skillbill.desktop.core.designsystem.LocalReducedMotion
import dev.skillbill.desktop.core.designsystem.component.LocalUiTicker
import dev.skillbill.desktop.core.designsystem.component.SbTag
import dev.skillbill.desktop.core.designsystem.component.SbInput
import dev.skillbill.desktop.core.ui.changes.changesMono
import dev.skillbill.desktop.core.ui.changes.changesSans
import dev.skillbill.desktop.core.domain.model.Comment
import dev.skillbill.desktop.core.domain.model.CommentAuthor
import dev.skillbill.desktop.core.domain.model.CommentOutcome
import dev.skillbill.desktop.core.domain.model.CommentState
import dev.skillbill.desktop.core.domain.model.CommentThread
import kotlin.time.Instant

@Composable
@OptIn(ExperimentalLayoutApi::class)
internal fun DiffCommentThread(
  thread: CommentThread,
  ui: ThreadUi,
  onAction: (ChangesWindowAction) -> Unit,
  onReplyFocusChanged: (String, Boolean) -> Unit,
  editing: CommentEditUi? = null,
  onEditFocusChanged: (Boolean) -> Unit = {},
  modifier: Modifier = Modifier,
) {
  val colors = SkillBillTheme.colors
  val railTarget = when (thread.state) {
    CommentState.Pending -> colors.accent
    CommentState.Open -> colors.stPlanning
    CommentState.Resolved -> colors.stDone
    CommentState.Declined -> colors.stPaused
  }
  val rail by animateColorAsState(railTarget, animationSpec = if (LocalReducedMotion.current) snap() else tween(160), label = "thread-state-rail")
  val expanded = ui.expanded ?: (thread.state == CommentState.Open || thread.state == CommentState.Pending)
  val collapsible = thread.state == CommentState.Resolved || thread.state == CommentState.Declined
  var confirmDelete by remember(thread.id) { mutableStateOf<String?>(null) }
  val focusRequester = remember(thread.id) { FocusRequester() }
  val focusManager = LocalFocusManager.current
  LaunchedEffect(thread.id, ui.draft != null) {
    if (ui.draft != null) focusRequester.requestFocus()
  }
  DisposableEffect(thread.id) { onDispose { onReplyFocusChanged(thread.id, false) } }
  SbBox(modifier.height(IntrinsicSize.Min), fill = colors.bgPanel, borderColor = if (thread.state == CommentState.Pending) colors.accent.copy(alpha = .35f) else colors.borderSubtle) {
    Row(Modifier.height(IntrinsicSize.Min)) {
      Box(Modifier.width(3.dp).fillMaxHeight().drawBehind {
        drawLine(rail, Offset(size.width / 2, 0f), Offset(size.width / 2, size.height), strokeWidth = size.width)
      })
      if (!expanded) {
        Row(
          Modifier.weight(1f).fillMaxWidth().height(36.dp)
            .clickable(role = Role.Button) { onAction(ChangesWindowAction.ToggleThread(thread.id)) }
            .semantics { stateDescription = "Collapsed" }
            .padding(horizontal = 10.dp),
          verticalAlignment = Alignment.CenterVertically,
          horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
          SbTag(thread.state.name, tone = stateColor(thread.state, colors))
          BasicText(thread.comments.firstOrNull()?.body?.lineSequence()?.firstOrNull().orEmpty(), Modifier.weight(1f), style = changesSans(11f, FontWeight.Normal, colors.fg2), maxLines = 1)
          BasicText("${(thread.comments.size - 1).coerceAtLeast(0)} replies", style = changesSans(10f, FontWeight.Normal, colors.fgMuted))
        }
      } else {
        val ticker = LocalUiTicker.current
        DisposableEffect(ticker) { ticker.retain(); onDispose { ticker.release() } }
        val now = ticker.now.value
        Column(Modifier.weight(1f).padding(10.dp), verticalArrangement = Arrangement.spacedBy(9.dp)) {
          FlowRow(
            modifier = if (collapsible) {
              Modifier.clickable(role = Role.Button) { onAction(ChangesWindowAction.ToggleThread(thread.id)) }
                .semantics { stateDescription = "Expanded" }
            } else {
              Modifier
            },
            horizontalArrangement = Arrangement.spacedBy(6.dp),
            verticalArrangement = Arrangement.spacedBy(5.dp),
            itemVerticalAlignment = Alignment.CenterVertically,
          ) {
            SbTag(thread.state.name, tone = stateColor(thread.state, colors))
            if (thread.outdated) SbTag("Outdated", tone = colors.stFailed)
            thread.severity?.let { SbTag(it, tone = severityColor(it, colors)) }
            thread.findingRef?.let { BasicText(it, style = changesMono(10f, FontWeight.Medium, colors.fgMuted)) }
          }
          if (thread.outdated && (thread.line != null || thread.anchorLineText != null)) {
            BasicText("${thread.line?.let { "L$it" }.orEmpty()}${if (thread.line != null && thread.anchorLineText != null) " · " else ""}${thread.anchorLineText.orEmpty()}", style = changesMono(10f, FontWeight.Normal, colors.fgMuted))
          }
          thread.comments.forEachIndexed { index, comment ->
            if (editing?.commentId == comment.id) {
              CommentComposer(editing.text, editing.problem, editing.saving, "", "", "Save", { onAction(ChangesWindowAction.EditText(it)) }, { onAction(ChangesWindowAction.SaveEdit) }, { onAction(ChangesWindowAction.CancelEdit) }, onEditFocusChanged)
            } else CommentRow(comment, thread.sourceLabel, now, ui.busy, onDelete = {
              if (thread.state == CommentState.Pending && comment.author == CommentAuthor.User) onAction(ChangesWindowAction.DeleteComment(thread.id, comment.id)) else confirmDelete = comment.id
            })
            if (confirmDelete == comment.id) {
              Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                BasicText(if (index == 0) "Deletes the whole thread" else "Delete this reply?", style = changesSans(10.5f, FontWeight.Normal, colors.stFailed))
                ActionText("Delete", colors.stFailed, ui.busy) { onAction(ChangesWindowAction.DeleteComment(thread.id, comment.id)); confirmDelete = null }
                ActionText("Cancel", colors.fgMuted) { confirmDelete = null }
              }
            }
            if (thread.state == CommentState.Pending && comment.author == CommentAuthor.User && editing?.commentId != comment.id) {
              Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                ActionText("Edit", colors.accentText, ui.busy) { onAction(ChangesWindowAction.StartEdit(thread.id, comment.id, comment.body)) }
                ActionText("Delete", colors.stFailed, ui.busy) { onAction(ChangesWindowAction.DeleteComment(thread.id, comment.id)) }
              }
            }
          }
          ui.problem?.let {
            BasicText(
              it,
              Modifier.semantics {
                error(it)
                liveRegion = LiveRegionMode.Polite
              },
              style = changesSans(11f, FontWeight.Normal, colors.stFailed),
            )
          }
          if (ui.draft != null) {
            SbInput(
              ui.draft,
              { onAction(ChangesWindowAction.EditReply(thread.id, it)) },
              Modifier.fillMaxWidth().heightIn(min = 54.dp)
                .focusRequester(focusRequester).onFocusChanged { onReplyFocusChanged(thread.id, it.isFocused) }
                .onPreviewKeyEvent { event ->
                  if (event.type != KeyEventType.KeyDown) false
                  else if (event.key == Key.Escape) { onAction(ChangesWindowAction.EditReply(thread.id, null)); focusManager.clearFocus(); true }
                  else if (event.key == Key.Enter && (event.isCtrlPressed || event.isMetaPressed)) { if (!ui.busy) onAction(ChangesWindowAction.SendReply(thread.id)); true }
                  else false
                },
              multiline = true,
              onSubmit = null,
            )
            FlowRow(horizontalArrangement = Arrangement.spacedBy(10.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
              ActionText("Send", colors.accentText, ui.busy) { onAction(ChangesWindowAction.SendReply(thread.id)) }
              ActionText("Cancel", colors.fgMuted) { onAction(ChangesWindowAction.EditReply(thread.id, null)) }
            }
          } else if (thread.state == CommentState.Pending) {
            BasicText("Only you can see this until you add the review.", style = changesSans(10.5f, FontWeight.Normal, colors.fgFaint))
          } else {
            FlowRow(horizontalArrangement = Arrangement.spacedBy(10.dp), verticalArrangement = Arrangement.spacedBy(4.dp), itemVerticalAlignment = Alignment.CenterVertically) {
              ActionText("Reply…", colors.fgMuted, ui.busy) { onAction(ChangesWindowAction.EditReply(thread.id, "")) }
              if (thread.state == CommentState.Open) {
                ActionText("Resolve", colors.stDone, ui.busy) { onAction(ChangesWindowAction.SetThreadState(thread.id, CommentState.Resolved)) }
                ActionText("Decline", colors.fgMuted, ui.busy) { onAction(ChangesWindowAction.SetThreadState(thread.id, CommentState.Declined)) }
              } else if (thread.state == CommentState.Resolved || thread.state == CommentState.Declined) {
                ActionText("Reopen", colors.accentText, ui.busy) { onAction(ChangesWindowAction.SetThreadState(thread.id, CommentState.Open)) }
              }
            }
          }
        }
      }
    }
  }
}

@Composable
@OptIn(ExperimentalLayoutApi::class)
private fun CommentRow(comment: Comment, sourceLabel: String?, now: Instant, busy: Boolean, onDelete: () -> Unit) {
  val colors = SkillBillTheme.colors
  val hover = remember { MutableInteractionSource() }
  val hovered by hover.collectIsHoveredAsState()
  val deleteInteraction = remember { MutableInteractionSource() }
  val deleteFocused by deleteInteraction.collectIsFocusedAsState()
  val revealDelete = hovered || deleteFocused
  Row(Modifier.fillMaxWidth().hoverable(hover), horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.Top) {
    val agent = comment.author == CommentAuthor.Agent
    Box(
      Modifier.size(22.dp).clip(if (agent) RoundedCornerShape(5.dp) else CircleShape)
        .background(if (agent) colors.accentTint else colors.bgActive),
      contentAlignment = Alignment.Center,
    ) { BasicText(if (agent) "✦" else "U", style = changesSans(10f, FontWeight.SemiBold, if (agent) colors.accentText else colors.fg2)) }
    Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
      FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalArrangement = Arrangement.spacedBy(4.dp), itemVerticalAlignment = Alignment.CenterVertically) {
        if (comment.author == CommentAuthor.Agent) comment.outcome?.let { SbTag(it.name, tone = outcomeColor(it, colors)) }
        BasicText(comment.agentId ?: if (agent) "Agent" else "You", style = changesSans(10.5f, FontWeight.SemiBold, colors.fg))
        sourceLabel?.let { BasicText(it, style = changesMono(9f, FontWeight.Normal, colors.fgMuted)) }
        BasicText(relativeTime(comment.createdAt, now), style = changesSans(9.5f, FontWeight.Normal, colors.fgFaint), maxLines = 1)
      }
      BasicText(inlineCode(comment.body, colors), style = changesSans(11.5f, FontWeight.Normal, colors.fg2))
    }
    Box(Modifier.width(18.dp), contentAlignment = Alignment.TopCenter) {
      ActionText("×", colors.fgFaint, busy, Modifier.alpha(if (revealDelete) 1f else 0f), "Delete comment", deleteInteraction, onDelete)
    }
  }
}

private fun relativeTime(createdAt: Instant, now: Instant): String {
  val minutes = ((now - createdAt).inWholeMinutes).coerceAtLeast(0)
  return when {
    minutes < 1 -> "now"
    minutes < 60 -> "${minutes}m ago"
    minutes < 1440 -> "${minutes / 60}h ago"
    else -> "${minutes / 1440}d ago"
  }
}

@Composable
private fun ActionText(
  label: String,
  color: Color,
  disabled: Boolean = false,
  modifier: Modifier = Modifier,
  name: String? = null,
  interaction: MutableInteractionSource = remember { MutableInteractionSource() },
  onClick: () -> Unit,
) {
  BasicText(
    label,
    modifier
      .clickable(interaction, indication = null, enabled = !disabled, role = Role.Button, onClick = onClick)
      .then(if (name != null) Modifier.semantics { contentDescription = name } else Modifier)
      .padding(vertical = 3.dp),
    style = changesSans(10.5f, FontWeight.Medium, color.copy(alpha = if (disabled) .45f else 1f)),
  )
}

private fun stateColor(state: CommentState, colors: SkillBillColors) = when (state) {
  CommentState.Pending -> colors.accent
  CommentState.Open -> colors.stPlanning
  CommentState.Resolved -> colors.stDone
  CommentState.Declined -> colors.stPaused
}

private fun outcomeColor(outcome: CommentOutcome, colors: SkillBillColors) = when (outcome) {
  CommentOutcome.Fixed -> colors.stDone
  CommentOutcome.Refuted -> colors.fg2
  CommentOutcome.Skipped -> colors.fgMuted
  CommentOutcome.Failed -> colors.stFailed
}

private fun severityColor(severity: String, colors: SkillBillColors) = when {
  severity.equals("blocker", ignoreCase = true) -> colors.stFailed
  severity.equals("major", ignoreCase = true) -> colors.stBlocked
  severity.equals("minor", ignoreCase = true) -> colors.fg2
  else -> colors.fgMuted
}

private fun inlineCode(body: String, colors: SkillBillColors) = buildAnnotatedString {
  var code = false
  body.split('`').forEachIndexed { index, part ->
    if (index > 0) code = !code
    if (code) withStyle(SpanStyle(color = colors.accentText, background = colors.bgActive)) { append(part) } else append(part)
  }
}
