package dev.skillbill.desktop.core.designsystem.detail

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.hoverable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsHoveredAsState
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicText
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusProperties
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.disabled
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.unit.dp
import dev.skillbill.desktop.core.designsystem.SkillBillRadius
import dev.skillbill.desktop.core.designsystem.LocalContentColor
import dev.skillbill.desktop.core.designsystem.SkillBillTheme
import androidx.compose.runtime.CompositionLocalProvider
import dev.skillbill.desktop.core.designsystem.component.SbTooltip
import dev.skillbill.desktop.core.designsystem.component.sbFocusRing
import dev.skillbill.desktop.core.designsystem.component.sbPulse
import dev.skillbill.desktop.core.designsystem.icon.LucideIcons
import dev.skillbill.desktop.core.designsystem.icon.SbIcon

@Immutable
class PhaseTab(val id: String, val label: String, val state: PipelineStepState, val openable: Boolean, val count: String? = null)

@OptIn(ExperimentalLayoutApi::class)
@Composable
fun PhaseTabs(
  tabs: List<PhaseTab>,
  selectedId: String?,
  onActivate: (String) -> Unit,
  modifier: Modifier = Modifier,
  label: String = "Phases",
  onNavigate: (String) -> Unit = onActivate,
) {
  var focusedId by remember(tabs.map { it.id }) { mutableStateOf<String?>(null) }
  val tabStop = phaseTabStop(tabs, focusedId, selectedId)
  val requesters = remember(tabs.map { it.id }) { tabs.associate { it.id to FocusRequester() } }
  var pendingFocusId by remember { mutableStateOf<String?>(null) }
  LaunchedEffect(pendingFocusId) {
    val target = pendingFocusId
    if (target != null) {
      requesters[target]?.requestFocus()
      pendingFocusId = null
    }
  }
  val connector = SkillBillTheme.colors.borderStrong
  FlowRow(
    modifier.semantics { contentDescription = label }.selectableGroup(),
    horizontalArrangement = Arrangement.spacedBy(4.dp),
    verticalArrangement = Arrangement.spacedBy(4.dp),
    itemVerticalAlignment = Alignment.CenterVertically,
  ) {
    tabs.forEachIndexed { index, tab ->
      if (index > 0) Box(Modifier.size(6.dp, 1.dp).background(connector))
      val colors = SkillBillTheme.colors
      val state = if (tab.openable) tab.state else PipelineStepState.Pending
      val (fg, bg) = pipelineStepColors(colors, state)
      val shape = RoundedCornerShape(SkillBillRadius.xs)
      val selected = tab.id == selectedId
      val interaction = requesters.getValue(tab.id)
      val interactions = remember(tab.id) { MutableInteractionSource() }
      val hovered by interactions.collectIsHoveredAsState()
      val move: (Key) -> Boolean = { key ->
        if (!tab.openable) false else {
          val target = phaseTabMove(tabs, tab.id, key)
          if (target == null) false else {
            pendingFocusId = target
            focusedId = target
            onNavigate(target)
            true
          }
        }
      }
      SbTooltip(when { !tab.openable && state == PipelineStepState.Skipped -> "${tab.label} was skipped"; !tab.openable -> "${tab.label} has not started"; else -> "Show ${tab.label}" }) {
        Row(
          Modifier.height(20.dp)
            .background(
              when {
                !tab.openable -> Color.Transparent
                hovered && state == PipelineStepState.Current -> colors.stActive.copy(alpha = .18f)
                hovered -> colors.bgHover
                state == PipelineStepState.Current || state == PipelineStepState.Waiting || state == PipelineStepState.Failed -> bg
                else -> Color.Transparent
              },
              shape,
            )
            .then(
              when {
                tab.openable && state == PipelineStepState.Current -> Modifier.border(1.dp, colors.stActive.copy(alpha = .35f), shape)
                tab.openable && (state == PipelineStepState.Waiting || state == PipelineStepState.Failed) -> Modifier.border(1.dp, fg.copy(alpha = .3f), shape)
                else -> Modifier
              },
            )
            .then(if (selected) Modifier.drawWithContent {
              drawContent()
              drawRoundRect(colors.fgMuted, topLeft = Offset(-2.dp.toPx(), -2.dp.toPx()), size = androidx.compose.ui.geometry.Size(size.width + 4.dp.toPx(), size.height + 4.dp.toPx()), cornerRadius = CornerRadius(SkillBillRadius.xs.toPx()), style = Stroke(1.dp.toPx()))
            } else Modifier)
            .focusRequester(interaction)
            .focusProperties { canFocus = tab.openable && tab.id == tabStop }
            .selectable(selected = selected, interactionSource = interactions, indication = null, enabled = tab.openable, role = Role.Tab) {
              focusedId = tab.id
              onActivate(tab.id)
            }
            .semantics {
              if (!tab.openable) disabled()
              else stateDescription = if (selected) "Expanded" else "Collapsed"
            }
            .onKeyEvent { event -> event.type == KeyEventType.KeyDown && move(event.key) }
            .hoverable(interactions, enabled = tab.openable)
            .sbFocusRing(interactions, shape)
            .padding(horizontal = 7.dp),
          horizontalArrangement = Arrangement.spacedBy(4.dp),
          verticalAlignment = Alignment.CenterVertically,
        ) {
          when (state) {
            PipelineStepState.Current -> Box(Modifier.size(5.dp).sbPulse().background(colors.stActive, CircleShape))
            PipelineStepState.Done -> CompositionLocalProvider(LocalContentColor provides colors.stDone) { SbIcon(LucideIcons.check, 10.dp, 2.5f) }
            else -> Unit
          }
          val text = if (tab.count != null) "${tab.label} · ${tab.count}" else tab.label
          BasicText(text, style = SkillBillTheme.type.t2xs.copy(fontFamily = SkillBillTheme.type.mono.fontFamily, fontWeight = FontWeight.Medium, color = if (selected && state == PipelineStepState.Done) colors.fg else fg, textDecoration = if (state == PipelineStepState.Skipped) TextDecoration.LineThrough else null), maxLines = 1)
        }
      }
    }
  }
}

/**
 * The one pill reachable by Tab: the last pill the user focused, else the open pill, else the first openable pill. Derived on every
 * composition, so a pill that becomes openable under an unchanged id list still receives the tab stop.
 */
internal fun phaseTabStop(tabs: List<PhaseTab>, focusedId: String?, selectedId: String?): String? {
  fun openable(id: String?) = id?.takeIf { wanted -> tabs.any { it.id == wanted && it.openable } }
  return openable(focusedId) ?: openable(selectedId) ?: tabs.firstOrNull { it.openable }?.id
}

internal fun phaseTabMove(tabs: List<PhaseTab>, fromId: String, key: Key): String? {
  val open = tabs.filter { it.openable }
  val index = open.indexOfFirst { it.id == fromId }
  if (index < 0) return null
  return when (key) {
    Key.DirectionLeft, Key.DirectionUp -> open[(index - 1 + open.size) % open.size].id
    Key.DirectionRight, Key.DirectionDown -> open[(index + 1) % open.size].id
    Key.MoveHome -> open.first().id
    Key.MoveEnd -> open.last().id
    else -> null
  }
}
