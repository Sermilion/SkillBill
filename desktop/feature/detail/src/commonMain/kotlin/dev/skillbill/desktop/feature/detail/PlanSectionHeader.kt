package dev.skillbill.desktop.feature.detail

import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicText
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.em
import dev.skillbill.desktop.core.designsystem.SkillBillTheme
import dev.skillbill.desktop.core.designsystem.SkillBillRadius
import dev.skillbill.desktop.core.designsystem.component.sbFocusRing
import dev.skillbill.desktop.core.designsystem.icon.LucideIcons
import dev.skillbill.desktop.core.designsystem.icon.SbIcon

@OptIn(ExperimentalLayoutApi::class)
@Composable
internal fun PlanSectionHeader(
  title: String,
  count: String?,
  open: Boolean,
  onToggle: () -> Unit,
  tools: (@Composable RowScope.() -> Unit)? = null,
  modifier: Modifier = Modifier,
) {
  val colors = SkillBillTheme.colors
  val interaction = remember { MutableInteractionSource() }
  FlowRow(
    modifier.fillMaxWidth().heightIn(min = 26.dp),
    horizontalArrangement = Arrangement.SpaceBetween,
    verticalArrangement = Arrangement.spacedBy(6.dp),
    itemVerticalAlignment = Alignment.CenterVertically,
  ) {
    Row(
      Modifier.heightIn(min = 26.dp)
        .sbFocusRing(interaction, RoundedCornerShape(SkillBillRadius.xs))
        .clickable(interactionSource = interaction, indication = null, role = Role.Button, onClick = onToggle)
        .semantics { stateDescription = if (open) "Expanded" else "Collapsed" },
      horizontalArrangement = Arrangement.spacedBy(6.dp),
      verticalAlignment = Alignment.CenterVertically,
    ) {
      SbIcon(LucideIcons.chevronDown, 12.dp, modifier = Modifier.rotate(if (open) 0f else -90f))
      BasicText(title, Modifier.semantics { heading() }, style = SkillBillTheme.type.tSm.copy(color = colors.fgMuted, fontWeight = FontWeight.Medium, lineHeight = 1.em), maxLines = 1)
      count?.let { BasicText(it, style = SkillBillTheme.type.tXs.copy(color = colors.fgFaint), maxLines = 1) }
    }
    if (tools != null && open) {
      Row(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalAlignment = Alignment.CenterVertically, content = tools)
    }
  }
}

internal val planSectionBodyGap = 6.dp
