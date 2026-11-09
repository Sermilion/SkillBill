package dev.skillbill.desktop.core.designsystem.detail

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicText
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.contentDescription
import dev.skillbill.desktop.core.designsystem.LocalContentColor
import dev.skillbill.desktop.core.designsystem.SkillBillRadius
import dev.skillbill.desktop.core.designsystem.SkillBillTheme
import dev.skillbill.desktop.core.designsystem.component.FindingRow
import dev.skillbill.desktop.core.designsystem.component.SbSpinner
import dev.skillbill.desktop.core.designsystem.component.sbPulse
import androidx.compose.ui.graphics.vector.ImageVector
import dev.skillbill.desktop.core.designsystem.icon.SbIcon
import dev.skillbill.desktop.core.designsystem.icon.LucideIcons
import dev.skillbill.desktop.core.designsystem.status.StatusIcon
import dev.skillbill.desktop.core.designsystem.status.StatusKind
import dev.skillbill.desktop.core.designsystem.component.SbTooltip

enum class PeekCell { Ok, Bad, Warn, Run, Empty }
enum class PeekStatTone { Good, Bad, Warn, Muted }
enum class PeekCheckState { Ok, Bad, Run, Waiting }
class PeekStat(val value: String, val label: String, val tone: PeekStatTone? = null)

@Composable
fun PeekPart(title: String, end: String? = null, endControl: (@Composable () -> Unit)? = null, live: Boolean = false, body: @Composable () -> Unit) {
  val colors = SkillBillTheme.colors
  Column(Modifier.fillMaxWidth().drawBehind { drawLine(colors.borderSubtle, Offset(0f, 0f), Offset(size.width, 0f), 1.dp.toPx()) }.then(if (live) Modifier.background(colors.stActive.copy(alpha = .06f)) else Modifier).padding(9.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
      if (live) Box(Modifier.padding(end = 5.dp).size(5.dp).sbPulse().background(colors.stActive, CircleShape))
      BasicText(title, Modifier.weight(1f), style = SkillBillTheme.type.tXs.copy(fontWeight = FontWeight.Medium, color = colors.fg2), maxLines = 1)
      end?.let { BasicText(it, style = SkillBillTheme.type.t2xs.copy(color = colors.fgMuted)) }
      endControl?.invoke()
    }
    body()
  }
}

@Composable
fun PeekSignal(text: String, live: Boolean, icon: ImageVector, keep: AnnotatedString? = null, ago: String? = null) {
  val colors = SkillBillTheme.colors
  Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
    if (live) Box(Modifier.size(6.dp).sbPulse().background(colors.stActive, CircleShape)) else CompositionLocalProvider(LocalContentColor provides colors.fgMuted) { SbIcon(icon, 12.dp) }
    BasicText(text, Modifier.weight(1f), style = SkillBillTheme.type.tXs.copy(color = colors.fg2), maxLines = 1, overflow = TextOverflow.Ellipsis)
    keep?.let { BasicText(it, style = SkillBillTheme.type.tXs.copy(color = colors.fg2), maxLines = 1, softWrap = false) }
    ago?.let { BasicText(it, style = SkillBillTheme.type.t2xs.copy(color = colors.fgFaint)) }
  }
}

@Composable
fun PeekCmd(text: String) { val row: @Composable () -> Unit = { Row(Modifier.fillMaxWidth().border(1.dp, SkillBillTheme.colors.borderSubtle, RoundedCornerShape(SkillBillRadius.xs)).padding(7.dp), horizontalArrangement = Arrangement.spacedBy(6.dp)) { SbIcon(dev.skillbill.desktop.core.designsystem.icon.LucideIcons.terminal, 13.dp); BasicText(text, style = SkillBillTheme.type.monoSm.copy(color = SkillBillTheme.colors.fg2), maxLines = 1, overflow = TextOverflow.Ellipsis) } }; SbTooltip(text) { row() } }

@Composable
fun PeekCells(cells: List<PeekCell>) {
  val colors = SkillBillTheme.colors
  Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(3.dp)) {
    cells.forEach { cell ->
      val color = when (cell) { PeekCell.Ok -> colors.stDone; PeekCell.Bad -> colors.stFailed; PeekCell.Warn -> colors.stBlocked; PeekCell.Run -> colors.stActive; PeekCell.Empty -> colors.fgFaint }
      Box(Modifier.weight(1f).size(5.dp).then(if (cell == PeekCell.Run) Modifier.sbPulse() else Modifier).background(if (cell == PeekCell.Empty) colors.bgActive else color, RoundedCornerShape(2.dp)))
    }
  }
}

@Composable
fun PeekStats(items: List<PeekStat>) {
  val colors = SkillBillTheme.colors
  Row(Modifier.fillMaxWidth().clip(RoundedCornerShape(SkillBillRadius.xs)).border(1.dp, colors.borderSubtle, RoundedCornerShape(SkillBillRadius.xs))) {
    items.forEach { stat ->
      val color = when (stat.tone) { PeekStatTone.Good -> colors.stDone; PeekStatTone.Bad -> colors.stFailed; PeekStatTone.Warn -> colors.stBlocked; PeekStatTone.Muted, null -> colors.fg2 }
      Column(Modifier.weight(1f).then(if (stat != items.firstOrNull()) Modifier.drawBehind { drawLine(colors.borderSubtle, Offset(0f,0f), Offset(0f,size.height),1.dp.toPx()) } else Modifier).padding(6.dp)) { BasicText(stat.value, style = SkillBillTheme.type.mono.copy(color = color, fontWeight = FontWeight.Medium)); BasicText(stat.label, style = SkillBillTheme.type.t2xs.copy(color = colors.fgMuted), maxLines = 2, overflow = TextOverflow.Ellipsis) }
    }
  }
}

@Composable
fun PeekOutput(icon: ImageVector, text: String, end: String? = null, onClick: (() -> Unit)? = null, external: Boolean = false, tooltip: String? = null, endSlot: (@Composable () -> Unit)? = null) {
  val colors = SkillBillTheme.colors
  val row: @Composable () -> Unit = {
    Row(Modifier.fillMaxWidth().background(colors.bgHover.copy(alpha = if (onClick == null) 0f else .45f), RoundedCornerShape(SkillBillRadius.xs))
      .then(if (onClick == null) Modifier else Modifier.clickable(role = Role.Button, onClick = onClick).semantics { contentDescription = tooltip ?: text })
      .padding(horizontal = 7.dp, vertical = 6.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
      CompositionLocalProvider(LocalContentColor provides colors.fgMuted) { SbIcon(icon, 13.dp) }
      BasicText(text, Modifier.weight(1f), style = SkillBillTheme.type.tXs.copy(color = if (onClick == null) colors.fg2 else colors.fg), maxLines = 1, overflow = TextOverflow.Ellipsis)
      end?.let { BasicText(it, style = SkillBillTheme.type.t2xs.copy(color = colors.fgMuted)) }
      endSlot?.invoke()
      if (onClick != null) CompositionLocalProvider(LocalContentColor provides colors.fgFaint) { SbIcon(if (external) LucideIcons.external else LucideIcons.arrowRight, 11.dp) }
    }
  }
  if (tooltip != null) dev.skillbill.desktop.core.designsystem.component.SbTooltip(tooltip) { row() } else row()
}

@Composable
fun PeekFinding(severity: String, title: String, state: String?) {
  val colors = SkillBillTheme.colors
  val stateColor = when (state?.lowercase()) { "fixed" -> colors.stDone; "open", "refuted" -> colors.fgFaint; else -> null }
  Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) { BasicText(severity.uppercase(), style = SkillBillTheme.type.t2xs.copy(color = peekSeverityColor(severity), fontWeight = FontWeight.SemiBold)); Box(Modifier.weight(1f)) { SbTooltip(title) { BasicText(title, Modifier.fillMaxWidth(), style = SkillBillTheme.type.tXs.copy(color = colors.fg2), maxLines = 1, overflow = TextOverflow.Ellipsis) } }; (when (state?.lowercase()) { "fixed" -> "Fixed"; "open" -> "Open"; "refuted" -> "Refuted"; else -> null })?.let { BasicText(it, style = SkillBillTheme.type.t2xs.copy(color = stateColor ?: colors.fgFaint)) } }
}

@Composable
fun PeekCheck(name: String, state: PeekCheckState, command: String? = null, time: String? = null) {
  val colors = SkillBillTheme.colors
  Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
    when (state) {
      PeekCheckState.Ok -> StatusIcon(StatusKind.Completed, null)
      PeekCheckState.Bad -> StatusIcon(StatusKind.Failed, null)
      PeekCheckState.Run -> SbSpinner(12.dp, colors.stActive, contentDescription = "Running")
      PeekCheckState.Waiting -> Box(Modifier.size(12.dp).border(1.dp, colors.stBlocked, CircleShape).semantics { contentDescription = "Waiting" })
    }
    Column(Modifier.weight(1f)) {
      SbTooltip(name) { BasicText(name, style = SkillBillTheme.type.tXs.copy(color = colors.fg2), maxLines = 1, overflow = TextOverflow.Ellipsis) }
      command?.let { cmd -> SbTooltip(cmd) { BasicText(cmd, style = SkillBillTheme.type.monoSm.copy(color = colors.fgFaint), maxLines = 1, overflow = TextOverflow.Ellipsis) } }
    }
    time?.let { BasicText(it, style = SkillBillTheme.type.t2xs.copy(color = colors.fgMuted)) }
  }
}

@Composable
fun PeekFacts(rows: List<Pair<String, String?>>) {
  val present = rows.filter { !it.second.isNullOrBlank() }
  if (present.isEmpty()) return
  PropertyList { present.forEach { (label, value) -> PropertyRow(label, tooltip = value) { PropertyText(value.orEmpty()) } } }
}

@Composable
fun PeekTimeline(rows: List<PeekTimelineRow>) {
  val colors = SkillBillTheme.colors
  if (rows.isEmpty()) return
  PeekPart("Activity") {
    Column(Modifier.fillMaxWidth().drawBehind {
      val trackX = if (layoutDirection == androidx.compose.ui.unit.LayoutDirection.Ltr) 10.5.dp.toPx() else size.width - 10.5.dp.toPx()
      drawLine(colors.border, Offset(trackX, 0f), Offset(trackX, size.height - 9.dp.toPx()), 1.dp.toPx())
    }, verticalArrangement = Arrangement.spacedBy(3.dp)) {
      rows.takeLast(5).forEach { row ->
        val dotColor = row.tone?.let { peekTone(it) } ?: colors.borderStrong
        Row(Modifier.fillMaxWidth().padding(vertical = 3.dp), horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.Top) {
          Box(Modifier.padding(start = 7.dp).size(7.dp).background(colors.bgRaised, CircleShape), contentAlignment = Alignment.Center) {
            Box(Modifier.size(5.dp).then(if (row.tone == PeekState.Now) Modifier.sbPulse() else Modifier).background(dotColor, CircleShape))
          }
          PeekActivityText(row.text, Modifier.weight(1f))
          BasicText(row.time, style = SkillBillTheme.type.t2xs.copy(color = colors.fgFaint), maxLines = 1)
        }
      }
    }
  }
}
