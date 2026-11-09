package dev.skillbill.desktop.core.designsystem.usage

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.hoverable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsHoveredAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicText
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import dev.skillbill.desktop.core.designsystem.LocalContentColor
import dev.skillbill.desktop.core.designsystem.SkillBillRadius
import dev.skillbill.desktop.core.designsystem.SkillBillTheme
import dev.skillbill.desktop.core.designsystem.component.SbButton
import dev.skillbill.desktop.core.designsystem.component.SbButtonVariant
import dev.skillbill.desktop.core.designsystem.component.SbControlSize
import dev.skillbill.desktop.core.designsystem.component.agentDataSlot
import dev.skillbill.desktop.core.designsystem.component.dataColor
import dev.skillbill.desktop.core.designsystem.component.sbFocusRing
import dev.skillbill.desktop.core.designsystem.composite.handCursor
import dev.skillbill.desktop.core.designsystem.icon.LucideIcons
import dev.skillbill.desktop.core.designsystem.icon.SbIcon
import dev.skillbill.desktop.core.designsystem.icon.SbIconSize

/** The most meter rows the card shows; the popup lists every window. */
const val USAGE_CARD_METER_SLOTS = 2

private val HeaderHeight = 24.dp
private val SlotHeight = 26.dp
private val Gap = 8.dp
private val Padding = 12.dp

/** Rows under the header: none for a name-only agent, one for a message, one per window up to [USAGE_CARD_METER_SLOTS]. */
fun usageCardRows(state: UsageUiState): Int = when {
  state.nameOnly -> 0
  state.meters.isEmpty() -> if (state.message != null) 1 else 0
  else -> state.meters.size.coerceAtMost(USAGE_CARD_METER_SLOTS)
}

/** The card's height for [rows] rows under the header. */
fun usageCardHeight(rows: Int): Dp = Padding * 2 + HeaderHeight + (SlotHeight + Gap) * rows

/** The agent's avatar color, or the default text color when no agent is known. */
@Composable
fun usageAgentColor(agentId: String?): Color =
  agentId?.let { SkillBillTheme.colors.dataColor(agentDataSlot(it)) } ?: SkillBillTheme.colors.fg

@Composable
fun UsageCard(
  state: UsageUiState,
  onOpen: () -> Unit,
  modifier: Modifier = Modifier,
  onRefresh: (() -> Unit)? = null,
  refreshEnabled: Boolean = true,
  refreshLabel: String = "Refresh",
) {
  val colors = SkillBillTheme.colors
  val interaction = remember { MutableInteractionSource() }
  val hovered by interaction.collectIsHoveredAsState()
  val shape = RoundedCornerShape(SkillBillRadius.md)
  Column(
    modifier.fillMaxWidth().height(usageCardHeight(usageCardRows(state)))
      .background(if (hovered) colors.bgRaised else colors.bgPanel, shape)
      .border(1.dp, if (hovered) colors.borderStrong else colors.border, shape)
      .hoverable(interaction).clickable(interactionSource = interaction, indication = null, onClick = onOpen).handCursor()
      .sbFocusRing(interaction, shape)
      .semantics(mergeDescendants = true) { role = Role.Button }
      .padding(Padding),
    verticalArrangement = Arrangement.spacedBy(Gap),
  ) {
    Row(Modifier.fillMaxWidth().height(HeaderHeight), verticalAlignment = Alignment.CenterVertically) {
      BasicText(state.provider, style = SkillBillTheme.type.tSmMedium.copy(color = usageAgentColor(state.agentId)), maxLines = 1, overflow = TextOverflow.Ellipsis)
      Spacer(Modifier.weight(1f))
      if (onRefresh != null) SbButton(
        refreshLabel, onRefresh, enabled = refreshEnabled,
        size = SbControlSize.Small, variant = SbButtonVariant.Ghost,
        tooltip = "Refresh ${state.provider} usage",
      )
      CompositionLocalProvider(LocalContentColor provides colors.fgFaint) { SbIcon(LucideIcons.chevronRight, SbIconSize.Xs) }
    }
    val meters = state.meters.take(USAGE_CARD_METER_SLOTS)
    repeat(usageCardRows(state)) { index ->
      Box(Modifier.fillMaxWidth().height(SlotHeight), contentAlignment = Alignment.CenterStart) {
        val meter = meters.getOrNull(index)
        when {
          meter != null -> UsageMeterRow(meter)
          index == 0 && state.message != null ->
            BasicText(state.message, style = usageText().copy(color = colors.fgMuted), maxLines = 1, overflow = TextOverflow.Ellipsis)
        }
      }
    }
  }
}
