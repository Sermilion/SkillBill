package dev.skillbill.desktop.feature.chat

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.text.BasicText
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.input.pointer.PointerIcon
import androidx.compose.ui.input.pointer.pointerHoverIcon
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.unit.dp
import dev.skillbill.desktop.core.designsystem.LocalContentColor
import dev.skillbill.desktop.core.designsystem.SkillBillTheme
import dev.skillbill.desktop.core.designsystem.component.SbTooltip
import dev.skillbill.desktop.core.designsystem.detail.SbBox
import dev.skillbill.desktop.core.designsystem.icon.LucideIcons
import dev.skillbill.desktop.core.designsystem.icon.SbIcon
import dev.skillbill.desktop.core.designsystem.icon.SbIconSize
import dev.skillbill.desktop.core.domain.model.ChatPermissionMode
import dev.skillbill.desktop.core.domain.model.ChatSuggestedAction
import dev.skillbill.desktop.core.domain.model.NewTaskActionKind

/** The agent's suggested next steps; the first is the recommended one. Hovering a row shows the instruction it sends. */
@Composable
internal fun ChatActionButtons(
  actions: List<ChatSuggestedAction>,
  enabled: Boolean,
  mode: ChatPermissionMode,
  startIssueKey: String? = null,
  onAction: (ChatSuggestedAction) -> Unit,
) {
  val colors = SkillBillTheme.colors
  Column(Modifier.fillMaxWidth().padding(top = 2.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
    BasicText("SUGGESTED", style = SkillBillTheme.type.t2xs.copy(color = colors.fgMuted))
    actions.forEachIndexed { index, action ->
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
    "run operation:update-check" -> "Checks for skill-bill updates."
    "run operation:unit-test-value-check" -> "Reviews the value of tests and reports findings."
    "run operation:feature-guard" -> "Proposes a feature flag, then adds it after confirmation."
    "run operation:feature-guard-cleanup" -> "Proposes flag removal, then removes it and runs validation after confirmation."
    "run operation:release" -> "Proposes a release, then tags and pushes it after confirmation."
    else -> null
  }
}
