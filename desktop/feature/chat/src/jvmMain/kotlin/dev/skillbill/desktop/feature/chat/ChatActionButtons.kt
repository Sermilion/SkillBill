package dev.skillbill.desktop.feature.chat

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicText
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.PointerIcon
import androidx.compose.ui.input.pointer.pointerHoverIcon
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.unit.dp
import dev.skillbill.desktop.core.designsystem.LocalContentColor
import dev.skillbill.desktop.core.designsystem.SkillBillRadius
import dev.skillbill.desktop.core.designsystem.SkillBillTheme
import dev.skillbill.desktop.core.designsystem.component.SbTooltip
import dev.skillbill.desktop.core.designsystem.icon.LucideIcons
import dev.skillbill.desktop.core.designsystem.icon.SbIcon
import dev.skillbill.desktop.core.designsystem.icon.SbIconSize
import dev.skillbill.desktop.core.domain.model.ChatPermissionMode
import dev.skillbill.desktop.core.domain.model.ChatSuggestedAction

/** The agent's suggested next steps; the first is the recommended one. Hovering a row shows the instruction it sends. */
@Composable
internal fun ChatActionButtons(
  actions: List<ChatSuggestedAction>,
  enabled: Boolean,
  mode: ChatPermissionMode,
  onAction: (ChatSuggestedAction) -> Unit,
) {
  val colors = SkillBillTheme.colors
  Column(Modifier.fillMaxWidth().padding(top = 2.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
    BasicText("SUGGESTED", style = SkillBillTheme.type.t2xs.copy(color = colors.fgMuted))
    actions.forEachIndexed { index, action ->
      val pause = mode == ChatPermissionMode.ReadOnly && action.mode == ChatPermissionMode.Act
      ActionRow(
        label = if (pause) "Pause and ${action.label.replaceFirstChar { it.lowercase() }}" else action.label,
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
  val shape = RoundedCornerShape(SkillBillRadius.md)
  SbTooltip(action.instruction) {
    Row(
      Modifier.fillMaxWidth().alpha(if (enabled) 1f else .5f)
        .border(1.dp, if (primary) colors.borderStrong else colors.border, shape)
        .background(if (primary) colors.bgRaised else Color.Transparent, shape)
        .clickable(enabled = enabled, role = Role.Button, onClick = onClick)
        .then(if (enabled) Modifier.pointerHoverIcon(PointerIcon.Hand) else Modifier)
        .padding(horizontal = 10.dp, vertical = 8.dp),
      horizontalArrangement = Arrangement.spacedBy(10.dp),
      verticalAlignment = Alignment.CenterVertically,
    ) {
      val icon = if (action.mode == ChatPermissionMode.Act) LucideIcons.zap else LucideIcons.search
      CompositionLocalProvider(LocalContentColor provides if (primary) colors.accentText else colors.fgMuted) { SbIcon(icon, SbIconSize.Sm) }
      BasicText(label, Modifier.weight(1f), style = SkillBillTheme.type.tSm.copy(color = if (primary) colors.fg else colors.fg2))
      ModeBadge(action.mode, pause)
    }
  }
}
