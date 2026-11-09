package dev.skillbill.desktop.core.designsystem.detail

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicText
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.LayoutDirection
import dev.skillbill.desktop.core.designsystem.SkillBillRadius
import dev.skillbill.desktop.core.designsystem.SkillBillTheme
import dev.skillbill.desktop.core.designsystem.component.SbButton
import dev.skillbill.desktop.core.designsystem.component.Avatar
import dev.skillbill.desktop.core.designsystem.icon.LucideIcons
import dev.skillbill.desktop.core.designsystem.icon.SbIcon
import dev.skillbill.desktop.core.designsystem.status.StatusIcon

class PeekFooter(val label: String, val external: Boolean, val onClick: () -> Unit)
class PeekTimelineRow(val time: String, val text: String, val tone: PeekState?)

@Composable
fun PhasePeek(
  title: String,
  phaseId: String,
  state: PeekState,
  word: String? = null,
  tone: PeekState? = null,
  agentId: String? = null,
  model: String? = null,
  round: String? = null,
  startLabel: String? = null,
  durationLabel: String? = null,
  why: String? = null,
  activity: List<PeekTimelineRow> = emptyList(),
  footer: PeekFooter? = null,
  endControl: (@Composable () -> Unit)? = null,
  parts: @Composable androidx.compose.foundation.layout.ColumnScope.() -> Unit,
) {
  val colors = SkillBillTheme.colors
  val chosenTone = tone ?: state
  val toneColor = peekTone(chosenTone)
  Column(Modifier.fillMaxWidth().clip(RoundedCornerShape(SkillBillRadius.md)).background(colors.bgRaised).border(1.dp, colors.border, RoundedCornerShape(SkillBillRadius.md)).semantics { contentDescription = title }) {
    Row(Modifier.fillMaxWidth().drawBehind { val x = if (layoutDirection == LayoutDirection.Ltr) 0f else size.width - 2.dp.toPx(); drawRect(toneColor, Offset(x, 0f), Size(2.dp.toPx(), size.height)) }.padding(start = 10.dp, end = 10.dp, top = 9.dp, bottom = 8.dp), verticalAlignment = Alignment.Top, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
      StatusIcon(peekStatus(state), null)
      Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(3.dp)) {
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(5.dp)) {
          Box(Modifier.weight(1f)) { dev.skillbill.desktop.core.designsystem.component.SbTooltip(title) { BasicText(title, Modifier.fillMaxWidth(), style = SkillBillTheme.type.tSmMedium.copy(color = colors.fg), maxLines = 1, overflow = TextOverflow.Ellipsis) } }
          dev.skillbill.desktop.core.designsystem.component.SbTooltip(phaseId) { BasicText(phaseId, style = SkillBillTheme.type.monoSm.copy(color = colors.fgFaint), maxLines = 1, overflow = TextOverflow.Ellipsis) }
        }
        FlowRow(verticalArrangement = Arrangement.spacedBy(2.dp), horizontalArrangement = Arrangement.spacedBy(5.dp)) {
          agentId?.let { Avatar(it, small = true) }
          model?.let { BasicText(it, style = SkillBillTheme.type.t2xs.copy(color = colors.fgMuted), maxLines = 2, overflow = TextOverflow.Ellipsis) }
          round?.let { BasicText(it, style = SkillBillTheme.type.t2xs.copy(color = colors.fgMuted)) }
          startLabel?.let { BasicText(it, style = SkillBillTheme.type.t2xs.copy(color = colors.fgMuted)) }
        }
      }
      Column(horizontalAlignment = Alignment.End) {
        BasicText(word ?: peekWord(state), style = SkillBillTheme.type.tXs.copy(color = toneColor, fontWeight = FontWeight.Medium), maxLines = 1)
        durationLabel?.let { BasicText(it, style = SkillBillTheme.type.t2xs.copy(color = colors.fgFaint), maxLines = 1) }
      }
    }
    endControl?.let { control ->
      Box(Modifier.align(Alignment.End).padding(start = 10.dp, end = 10.dp, bottom = 8.dp)) { control() }
    }
    if (!why.isNullOrBlank()) BasicText(why, Modifier.fillMaxWidth().drawBehind { drawRect(toneColor.copy(alpha = .10f)) }.padding(9.dp), style = SkillBillTheme.type.tXs.copy(color = colors.fg2))
    Column(Modifier.fillMaxWidth(), content = parts)
    if (activity.isNotEmpty()) PeekTimeline(activity)
    footer?.let { f -> Row(Modifier.fillMaxWidth().background(colors.bgPanel).border(1.dp, colors.borderSubtle).padding(8.dp), horizontalArrangement = Arrangement.End) { SbButton(f.label, f.onClick, icon = if (f.external) LucideIcons.external else LucideIcons.arrowRight, size = dev.skillbill.desktop.core.designsystem.component.SbControlSize.Small) } }
  }
}
