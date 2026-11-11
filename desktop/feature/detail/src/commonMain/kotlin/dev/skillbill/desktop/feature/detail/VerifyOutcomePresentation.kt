package dev.skillbill.desktop.feature.detail

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.text.BasicText
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import dev.skillbill.desktop.core.designsystem.LocalContentColor
import dev.skillbill.desktop.core.designsystem.SkillBillTheme
import dev.skillbill.desktop.core.designsystem.component.SbClock
import dev.skillbill.desktop.core.designsystem.component.rememberRuntimeText
import dev.skillbill.desktop.core.designsystem.composite.Edge
import dev.skillbill.desktop.core.designsystem.composite.hairline
import dev.skillbill.desktop.core.designsystem.detail.BarSegment
import dev.skillbill.desktop.core.designsystem.detail.SbBox
import dev.skillbill.desktop.core.designsystem.detail.SegmentBar
import dev.skillbill.desktop.core.designsystem.icon.LucideIcons
import dev.skillbill.desktop.core.designsystem.icon.SbIcon
import dev.skillbill.desktop.core.domain.model.QaAssertionOutcome
import dev.skillbill.desktop.core.domain.model.VerifyRunDetails

internal fun verifyScoreNotes(run: VerifyRunDetails): Map<String, String> {
  val grades = run.completenessAudit?.grades
  fun tally(values: List<String>) = values.groupingBy { it.lowercase() }.eachCount().entries.joinToString(" · ") { "${it.value} ${it.key}" }
  val partial = grades.orEmpty().count { it.status.equals("PARTIAL", true) }
  val missing = grades.orEmpty().count { it.status.equals("FAIL", true) }
  return mapOf(
    "Criteria" to if (grades == null) "not audited" else listOfNotNull("$partial partial".takeIf { partial > 0 }, "$missing missing".takeIf { missing > 0 }).joinToString(" · ").ifEmpty { "all met" },
    "Review" to (run.reviewFindings?.let { tally(it.items.map { row -> row.severity }).ifEmpty { "clean" } } ?: if (run.steps.any { it.stepId == "code_review" && it.status == "running" }) "running" else "not run"),
    "Tests" to (run.unitTestValue?.let { tally(it.rows.map { row -> row.verdict }).ifEmpty { "no tests changed" } } ?: "not run"),
    "QA" to (run.qa?.let { qa -> "${qa.assertions.count { it.outcome == QaAssertionOutcome.Passed }} of ${qa.assertions.size} checks passed" } ?: "not run"),
    "Flag audit" to (run.flagAudit?.flagName ?: if (run.flagAudit != null) "no flag required" else "not run"),
  )
}

@Composable
internal fun verifyTone(tone: VerifyScoreTone): Color = when (tone) {
  VerifyScoreTone.Green -> SkillBillTheme.colors.stDone
  VerifyScoreTone.Amber -> SkillBillTheme.colors.stBlocked
  VerifyScoreTone.Red -> SkillBillTheme.colors.stFailed
  VerifyScoreTone.Active -> SkillBillTheme.colors.stActive
  VerifyScoreTone.Faint -> SkillBillTheme.colors.fgFaint
}

@Composable
internal fun VerifyOutcomeCard(view: VerifyOutcomeView, onCellClick: (String) -> Unit) {
  val colors = SkillBillTheme.colors
  val tone = verifyTone(view.tone)
  SbBox(Modifier.padding(top = 20.dp), fill = colors.bgRaised, borderColor = colors.border) {
    Row(Modifier.fillMaxWidth().drawBehind { drawRect(tone, size = Size(3.dp.toPx(), size.height)) }.padding(16.dp), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
      CompositionLocalProvider(LocalContentColor provides tone) {
        SbIcon(when (view.tone) {
          VerifyScoreTone.Green -> LucideIcons.check
          VerifyScoreTone.Red -> LucideIcons.x
          VerifyScoreTone.Active -> LucideIcons.play
          VerifyScoreTone.Amber -> LucideIcons.info
          VerifyScoreTone.Faint -> LucideIcons.clock
        }, 24.dp)
      }
      Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(6.dp)) {
        if (view.title.isNotBlank()) BasicText(view.title, style = SkillBillTheme.type.tXs.copy(color = tone))
        BasicText(view.headline, style = SkillBillTheme.type.tLgStrong.copy(color = if (view.title.startsWith("No verdict")) colors.fg else tone))
        if (view.message.isNotBlank()) BasicText(rememberRuntimeText(view.message), style = SkillBillTheme.type.tSm.copy(color = colors.fg2))
        if (view.qaLowered) BasicText("QA had open findings, so the runtime lowered APPROVE to APPROVE WITH FIXES.", style = SkillBillTheme.type.tXs.copy(color = colors.fgFaint))
      }
      Column {
        if (view.startedAt != null) SbClock(0L, view.startedAt, live = true, showSeconds = true, style = SkillBillTheme.type.tSm.copy(color = colors.fg2))
        else view.elapsed?.let { BasicText(it, style = SkillBillTheme.type.tSm.copy(color = colors.fg2)) }
        view.finishedAt?.let { BasicText(formatInstant(it, "d MMM HH:mm"), style = SkillBillTheme.type.tXs.copy(color = colors.fgFaint)) }
      }
    }
    if (view.cells.isNotEmpty()) VerifyScoreGrid(view.cells, onCellClick)
  }
}

@Composable
private fun VerifyScoreGrid(cells: List<VerifyScoreCell>, onCellClick: (String) -> Unit) {
  val colors = SkillBillTheme.colors
  BoxWithConstraints(Modifier.fillMaxWidth().hairline(Edge.Top, colors.borderSubtle)) {
    val columns = (maxWidth / 140.dp).toInt().coerceIn(1, cells.size)
    Column {
      cells.chunked(columns).forEach { row ->
        Row(Modifier.fillMaxWidth()) {
          row.forEach { cell ->
            Column(
              Modifier.weight(1f).hairline(Edge.End, colors.borderSubtle)
                .clickable(role = Role.Button, onClickLabel = "Go to ${cell.title}", onClick = { onCellClick(cell.targetKey) })
                .semantics { contentDescription = "${cell.title}: ${cell.value}. Go to ${cell.title}" }.padding(horizontal = 12.dp, vertical = 10.dp),
              verticalArrangement = Arrangement.spacedBy(4.dp),
            ) {
              BasicText(cell.title.uppercase(), style = SkillBillTheme.type.t2xs.copy(color = colors.fgFaint))
              BasicText(cell.value, style = SkillBillTheme.type.tMd.copy(color = colors.fg), maxLines = 1, overflow = TextOverflow.Ellipsis)
              BasicText(cell.note, style = SkillBillTheme.type.tXs.copy(color = colors.fgMuted), maxLines = 1, overflow = TextOverflow.Ellipsis)
              SegmentBar(cell.segments.map { BarSegment(it.count.toFloat(), verifyTone(it.tone)) }, description = "${cell.title} score breakdown")
            }
          }
          repeat(columns - row.size) { Spacer(Modifier.weight(1f)) }
        }
      }
    }
  }
}
