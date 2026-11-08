package dev.skillbill.desktop.core.designsystem.usage

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.hoverable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsHoveredAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicText
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.runtime.getValue
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import dev.skillbill.desktop.core.designsystem.SkillBillRadius
import dev.skillbill.desktop.core.designsystem.SkillBillTheme
import dev.skillbill.desktop.core.designsystem.LocalContentColor
import dev.skillbill.desktop.core.designsystem.composite.handCursor
import dev.skillbill.desktop.core.designsystem.component.sbFocusRing
import dev.skillbill.desktop.core.designsystem.icon.SbIcon
import dev.skillbill.desktop.core.designsystem.icon.SbIconSize
import dev.skillbill.desktop.core.designsystem.icon.LucideIcons

@Composable
fun UsageCard(state: UsageUiState, onOpen: () -> Unit, modifier: Modifier = Modifier) {
  val colors = SkillBillTheme.colors
  val interaction = remember { MutableInteractionSource() }
  val hovered by interaction.collectIsHoveredAsState()
  val warning = state.cardMeters.take(2).any { it.warning }
  val exhausted = state.cardMeters.take(2).any { it.exhausted }
  val stale = state.cardMeters.take(2).any { it.stale }
  val markers = listOfNotNull(
    "Checking for updates".takeIf { state.checking },
    "Stale data".takeIf { state.stale },
    "Expired".takeIf { state.expired },
    "Updated by this session".takeIf { state.sessionOnly },
    state.requestPermission?.let { "Request permission: $it" },
  ).joinToString(". ")
  val statusColor = when {
    stale -> colors.fgMuted
    exhausted -> colors.stFailed
    warning -> colors.accentText
    else -> colors.fgMuted
  }
  Column(
    modifier.fillMaxWidth().background(if (hovered) colors.bgRaised else colors.bgPanel, RoundedCornerShape(SkillBillRadius.md))
      .border(1.dp, if (hovered) colors.borderStrong else colors.border, RoundedCornerShape(SkillBillRadius.md))
      .hoverable(interaction).clickable(interactionSource = interaction, indication = null, onClick = onOpen).handCursor()
      .sbFocusRing(interaction, RoundedCornerShape(SkillBillRadius.md))
      .semantics(mergeDescendants = true) {
        role = Role.Button
        stateDescription = listOfNotNull(markers.takeIf(String::isNotBlank), state.accountExplanation).joinToString(". ")
      }
      .padding(12.dp),
    verticalArrangement = Arrangement.spacedBy(10.dp),
  ) {
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = androidx.compose.ui.Alignment.CenterVertically) {
      BasicText("Usage limits", style = SkillBillTheme.type.tXs.copy(color = colors.fgMuted))
      if (state.contextLabel != null) {
        BasicText(state.contextLabel, Modifier.weight(1f), style = SkillBillTheme.type.t2xs.copy(color = colors.fgMuted), maxLines = 1, overflow = TextOverflow.Ellipsis)
      } else {
        Spacer(Modifier.weight(1f))
      }
      CompositionLocalProvider(LocalContentColor provides colors.fgFaint) { SbIcon(LucideIcons.chevronRight, SbIconSize.Xs) }
    }
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = androidx.compose.ui.Alignment.CenterVertically) {
      BasicText(state.provider, style = SkillBillTheme.type.tSmMedium.copy(color = colors.fg))
      BasicText(state.status, Modifier.weight(1f), style = SkillBillTheme.type.t2xs.copy(color = statusColor, textAlign = androidx.compose.ui.text.style.TextAlign.End), maxLines = 1, overflow = TextOverflow.Ellipsis)
    }
    state.cardMeters.take(2).forEach { meter -> UsageMeterRow(meter, compact = true) }
    Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
      state.account.takeIf(String::isNotBlank)?.let { BasicText(it, style = SkillBillTheme.type.tXs.copy(color = colors.fgMuted), maxLines = 1, overflow = TextOverflow.Ellipsis) }
      val sourceAndAge = listOfNotNull(state.source?.let { "Source: $it" }, state.observationAge).joinToString(". ")
      if (sourceAndAge.isNotBlank()) BasicText(sourceAndAge, style = SkillBillTheme.type.tXs.copy(color = colors.fgMuted), maxLines = 1, overflow = TextOverflow.Ellipsis)
      if (markers.isNotBlank()) BasicText(markers, style = SkillBillTheme.type.tXs.copy(color = colors.fg2), maxLines = 1, overflow = TextOverflow.Ellipsis)
      state.reason?.let { BasicText(it, style = SkillBillTheme.type.tXs.copy(color = colors.fgMuted), maxLines = 1, overflow = TextOverflow.Ellipsis) }
    }
  }
}
