package dev.skillbill.desktop.feature.chat

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.text.BasicText
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.input.pointer.PointerIcon
import androidx.compose.ui.input.pointer.pointerHoverIcon
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import dev.skillbill.desktop.core.designsystem.LocalContentColor
import dev.skillbill.desktop.core.designsystem.SkillBillRadius
import dev.skillbill.desktop.core.designsystem.SkillBillTheme
import dev.skillbill.desktop.core.designsystem.component.SbButton
import dev.skillbill.desktop.core.designsystem.component.SbButtonVariant
import dev.skillbill.desktop.core.designsystem.component.SbSpinner
import dev.skillbill.desktop.core.designsystem.component.SbTooltip
import dev.skillbill.desktop.core.designsystem.detail.SbBox
import dev.skillbill.desktop.core.designsystem.icon.LucideIcons
import dev.skillbill.desktop.core.designsystem.icon.SbIcon
import dev.skillbill.desktop.core.designsystem.icon.SbIconSize
import dev.skillbill.desktop.core.domain.model.ChatPermissionMode
import dev.skillbill.desktop.core.domain.model.ChatSuggestedAction
import dev.skillbill.desktop.core.domain.model.NewTaskActionKind

/**
 * Where a goal or plan start card would run. New task knows its repository and base branch; [starting] is true while a start
 * it sent is in flight. An execution chat passes none, so its cards name only the kind and any branch the action carries.
 */
data class StartCardContext(val repository: String?, val baseBranch: String?, val starting: Boolean = false)

/**
 * The agent's suggested next steps; the first is the recommended one. Goal and plan starts render as a start card, every
 * other action as a row. Hovering a row or card shows the instruction it sends.
 */
@Composable
internal fun ChatActionButtons(
  actions: List<ChatSuggestedAction>,
  enabled: Boolean,
  mode: ChatPermissionMode,
  startIssueKey: String? = null,
  startContext: StartCardContext? = null,
  onAction: (ChatSuggestedAction) -> Unit,
) {
  val colors = SkillBillTheme.colors
  val starting = startContext?.starting == true
  // Only the card the operator clicked says Starting; a finished or refused start returns it to its button.
  var launched by remember(actions) { mutableStateOf<ChatSuggestedAction?>(null) }
  LaunchedEffect(starting) { if (!starting) launched = null }
  Column(Modifier.fillMaxWidth().padding(top = 2.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
    BasicText("SUGGESTED", style = SkillBillTheme.type.t2xs.copy(color = colors.fgMuted))
    actions.forEachIndexed { index, action ->
      if (action.start in START_CARD_KINDS) {
        StartCard(
          action = action,
          issueKey = startIssueKey,
          context = startContext,
          starting = starting && launched === action,
          enabled = enabled,
        ) {
          launched = action
          onAction(action)
        }
        return@forEachIndexed
      }
      // A start never pauses the execution: on a running one the click only explains the refusal.
      val pause = mode == ChatPermissionMode.ReadOnly && action.mode == ChatPermissionMode.Act && action.start == null
      val label = if (startIssueKey != null && action.start in setOf(NewTaskActionKind.Goal, NewTaskActionKind.Plan)) {
        "${action.label} $startIssueKey"
      } else action.label
      ActionRow(
        label = if (pause) "Pause and ${action.label.replaceFirstChar { it.lowercase() }}" else label,
        action = action,
        pause = pause,
        primary = index == 0,
        enabled = enabled,
      ) { onAction(action) }
    }
  }
}

@Composable
private fun ActionRow(label: String, action: ChatSuggestedAction, pause: Boolean, primary: Boolean, enabled: Boolean, onClick: () -> Unit) {
  val colors = SkillBillTheme.colors
  SbTooltip(action.instruction) {
    SbBox(
      Modifier.alpha(if (enabled) 1f else .5f)
        .clickable(enabled = enabled, role = Role.Button, onClick = onClick)
        .then(if (enabled) Modifier.pointerHoverIcon(PointerIcon.Hand) else Modifier),
      fill = if (primary) colors.bgRaised else null,
      borderColor = if (primary) colors.borderStrong else colors.borderSubtle,
    ) {
      Row(Modifier.fillMaxWidth().padding(horizontal = 10.dp, vertical = 8.dp), horizontalArrangement = Arrangement.spacedBy(10.dp), verticalAlignment = Alignment.CenterVertically) {
        val icon = if (action.mode == ChatPermissionMode.Act) LucideIcons.zap else LucideIcons.search
        CompositionLocalProvider(LocalContentColor provides if (primary) colors.accentText else colors.fgMuted) { SbIcon(icon, SbIconSize.Sm) }
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
          BasicText(label, style = SkillBillTheme.type.tSm.copy(color = if (primary) colors.fg else colors.fg2))
          actionHint(action)?.let { hint ->
            BasicText(hint, style = SkillBillTheme.type.tXs.copy(color = colors.fgMuted))
          }
        }
        ModeBadge(action.mode, pause)
      }
    }
  }
}

private val START_CARD_KINDS = setOf(NewTaskActionKind.Goal, NewTaskActionKind.Plan)

@Composable
fun StartCard(
  action: ChatSuggestedAction,
  issueKey: String?,
  context: StartCardContext?,
  starting: Boolean,
  enabled: Boolean,
  onStart: () -> Unit,
) {
  val colors = SkillBillTheme.colors
  val type = SkillBillTheme.type
  val goal = action.start == NewTaskActionKind.Goal
  SbTooltip(action.instruction) {
    SbBox(
      Modifier.alpha(if (enabled || starting) 1f else .5f),
      fill = if (starting) colors.accentTint else colors.bgRaised,
      borderColor = if (starting) colors.accentRing else colors.border,
    ) {
      Row(Modifier.fillMaxWidth().padding(12.dp), horizontalArrangement = Arrangement.spacedBy(12.dp), verticalAlignment = Alignment.CenterVertically) {
        Box(
          Modifier.size(34.dp).background(colors.accentTint, RoundedCornerShape(SkillBillRadius.md)),
          contentAlignment = Alignment.Center,
        ) {
          CompositionLocalProvider(LocalContentColor provides colors.accent) { SbIcon(LucideIcons.target, SbIconSize.Md) }
        }
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
          BasicText(
            startCardTitle(action),
            style = type.tBaseMedium.copy(color = colors.fg),
            maxLines = 2,
            overflow = TextOverflow.Ellipsis,
          )
          BasicText(
            startCardSubtitle(action, issueKey, context),
            style = type.tSm.copy(color = colors.fgMuted),
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
          )
        }
        if (starting) {
          Row(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalAlignment = Alignment.CenterVertically) {
            SbSpinner(color = colors.accent)
            BasicText("Starting…", style = type.tSmMedium.copy(color = colors.accentText))
          }
        } else {
          SbButton(if (goal) "Start goal" else "Start plan", onStart, variant = SbButtonVariant.Primary, enabled = enabled)
        }
      }
    }
  }
}

/** The change the start names; an action without a title falls back to its kind. */
internal fun startCardTitle(action: ChatSuggestedAction): String =
  action.title?.trim()?.takeIf { it.isNotEmpty() } ?: if (action.start == NewTaskActionKind.Goal) "New goal" else "New plan"

/**
 * Kind, accepted issue key and where the run starts, as "Goal · SONA-12 · sona from main". A branch the action names wins
 * over the chat's base branch, since the run works on it as it is.
 */
internal fun startCardSubtitle(action: ChatSuggestedAction, issueKey: String?, context: StartCardContext?): String {
  val kind = if (action.start == NewTaskActionKind.Goal) "Goal" else "Plan"
  val branch = action.branch?.trim()?.takeIf { it.isNotEmpty() }
  val base = context?.baseBranch?.trim()?.takeIf { it.isNotEmpty() }
  val repository = context?.repository?.trim()?.takeIf { it.isNotEmpty() }
  val where = when {
    repository != null && branch != null -> "$repository on $branch"
    repository != null && base != null -> "$repository from $base"
    repository != null -> repository
    branch != null -> "on $branch"
    else -> null
  }
  return listOfNotNull(kind, issueKey?.trim()?.takeIf { it.isNotEmpty() }, where).joinToString(" · ")
}

private fun actionHint(action: ChatSuggestedAction): String? = when (action.start) {
  NewTaskActionKind.Goal -> "Plans and implements the work, then runs checks and prepares a pull request."
  NewTaskActionKind.Plan -> "Creates a plan and spec for the work without implementing it."
  NewTaskActionKind.Review -> "Reviews the current changes and reports verified findings."
  NewTaskActionKind.Validation -> "Runs project checks and repairs failures."
  NewTaskActionKind.Qa -> "Tests the requested behavior and reports findings."
  NewTaskActionKind.Pr -> "Commits and pushes changes, opens or updates a pull request, and monitors CI."
  NewTaskActionKind.Monitor -> "Watches CI and may repair and push failures or pull request conflicts."
  NewTaskActionKind.Verify -> "Proposes acceptance criteria for confirmation, then audits them."
  NewTaskActionKind.PrReviewFix -> "Proposes fixes for review comments, then applies selected fixes after confirmation."
  null -> when (action.label.trim().lowercase()) {
    "run operation:update-check" -> "Checks for Sona updates."
    "run operation:unit-test-value-check" -> "Reviews the value of tests and reports findings."
    "run operation:feature-guard" -> "Proposes a feature flag, then adds it after confirmation."
    "run operation:feature-guard-cleanup" -> "Proposes flag removal, then removes it and runs validation after confirmation."
    "run operation:release" -> "Proposes a release, then tags and pushes it after confirmation."
    else -> null
  }
}
