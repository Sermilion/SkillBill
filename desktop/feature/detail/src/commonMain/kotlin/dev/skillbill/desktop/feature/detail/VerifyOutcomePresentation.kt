package dev.skillbill.desktop.feature.detail

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.text.BasicText
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.em
import dev.skillbill.desktop.core.designsystem.LocalContentColor
import dev.skillbill.desktop.core.designsystem.SkillBillTheme
import dev.skillbill.desktop.core.designsystem.component.SbClock
import dev.skillbill.desktop.core.designsystem.composite.Edge
import dev.skillbill.desktop.core.designsystem.composite.hairline
import dev.skillbill.desktop.core.designsystem.detail.BarSegment
import dev.skillbill.desktop.core.designsystem.detail.SegmentBar
import dev.skillbill.desktop.core.designsystem.icon.LucideIcons
import dev.skillbill.desktop.core.designsystem.icon.SbIcon
import dev.skillbill.desktop.core.domain.model.QaAssertionOutcome
import dev.skillbill.desktop.core.domain.model.VerifyRunDetails

internal fun verifyScoreNotes(run: VerifyRunDetails): Map<String, String> {
  val grades = run.criteria.mapNotNull { criterion -> run.completenessAudit?.grades?.firstOrNull { it.number == criterion.number } }
  val ungraded = run.criteria.size - grades.size
  fun tally(values: List<String>) = values.groupingBy { it.lowercase() }.eachCount().entries.joinToString(" · ") { "${it.value} ${it.key}" }
  val partial = grades.count { it.gap && it.status == "PARTIAL" }
  val missing = grades.count { it.gap && it.status == "FAIL" }
  val resolved = grades.count { it.resolution != null }
  return mapOf(
    "Criteria" to when {
      run.completenessAudit == null -> "not audited"
      run.criteria.isEmpty() -> "no criteria"
      else -> listOfNotNull("$partial partial".takeIf { partial > 0 }, "$missing missing".takeIf { missing > 0 }, "$ungraded ungraded".takeIf { ungraded > 0 }, "$resolved resolved".takeIf { resolved > 0 }).joinToString(" · ").ifEmpty { "all met" }
    },
    "Review" to (run.reviewFindings?.let { review -> tally(review.items.map { row -> if (row.open) row.severity else row.status?.label().orEmpty() }).ifEmpty { "clean" } } ?: if (run.steps.any { it.stepId == "code_review" && it.status == "running" }) "running" else "not run"),
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
  VerifyBox(Modifier.padding(top = 20.dp)) {
    Row(Modifier.fillMaxWidth().drawBehind { drawRect(tone, size = Size(3.dp.toPx(), size.height)) }.padding(start = 19.dp, end = 16.dp, top = 16.dp, bottom = 16.dp), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
      Box(Modifier.size(32.dp).verifyLiveRing(view.state == VerifyRunState.Running, tone, 4.dp, .45f).background(tone.copy(alpha = .16f), CircleShape), contentAlignment = Alignment.Center) {
        CompositionLocalProvider(LocalContentColor provides tone) {
          SbIcon(when {
            view.state == VerifyRunState.Superseded -> LucideIcons.x
            view.state == VerifyRunState.Stopped -> LucideIcons.pause
            view.tone == VerifyScoreTone.Green -> LucideIcons.check
            view.tone == VerifyScoreTone.Red -> LucideIcons.x
            view.tone == VerifyScoreTone.Active -> LucideIcons.play
            view.tone == VerifyScoreTone.Amber -> LucideIcons.info
            else -> LucideIcons.clock
          }, 18.dp, strokeWidth = 2f)
        }
      }
      Column(Modifier.weight(1f)) {
        if (view.title.isNotBlank()) BasicText(view.title, Modifier.padding(bottom = 1.dp), style = SkillBillTheme.type.tXs.copy(color = colors.fgFaint, fontWeight = FontWeight.Normal, lineHeight = 1.45.em))
        BasicText(view.headline, style = SkillBillTheme.type.tLgStrong.copy(color = if (view.title.startsWith("No verdict")) colors.fg else tone, lineHeight = 1.45.em, letterSpacing = .01.em))
        if (view.message.isNotBlank()) BasicText(verifyText(view.message), Modifier.padding(top = 8.dp), style = SkillBillTheme.type.tSm.copy(color = colors.fg2))
        if (view.qaLowered) BasicText("QA had open findings, so the runtime lowered APPROVE to APPROVE WITH FIXES.", Modifier.padding(top = 8.dp), style = SkillBillTheme.type.tXs.copy(color = colors.fgFaint, fontWeight = FontWeight.Normal))
      }
      VerifyOutcomeTime(view)
    }
    if (view.cells.isNotEmpty()) VerifyScoreGrid(view.cells, onCellClick)
  }
}

@Composable
private fun VerifyOutcomeTime(view: VerifyOutcomeView) {
  val colors = SkillBillTheme.colors
  val valueStyle = SkillBillTheme.type.tSmMedium.copy(color = colors.fg2, lineHeight = 1.4.em)
  val noteStyle = SkillBillTheme.type.tXs.copy(color = colors.fgFaint, fontWeight = FontWeight.Normal)
  Column(horizontalAlignment = Alignment.End) {
    when (view.state) {
      VerifyRunState.Running -> view.startedAt?.let {
        SbClock(0L, it, live = true, showSeconds = true, style = valueStyle)
        BasicText("running", style = noteStyle)
      }
      VerifyRunState.Parked -> view.startedAt?.let {
        BasicText(formatInstant(it, "d MMM, HH:mm"), style = valueStyle)
        BasicText("parked", style = noteStyle)
      }
      VerifyRunState.Finished -> {
        view.elapsed?.let { BasicText(it, style = valueStyle) }
        view.finishedAt?.let { BasicText(formatInstant(it, "d MMM, HH:mm"), style = noteStyle) }
      }
      VerifyRunState.Stopped, VerifyRunState.Superseded -> {
        view.finishedAt?.let { BasicText(formatInstant(it, "d MMM, HH:mm"), style = valueStyle) }
        view.elapsed?.let { BasicText("$it after start", style = noteStyle) }
      }
      VerifyRunState.Pending -> Unit
    }
  }
}

@Composable
private fun VerifyScoreGrid(cells: List<VerifyScoreCell>, onCellClick: (String) -> Unit) {
  val colors = SkillBillTheme.colors
  BoxWithConstraints(Modifier.fillMaxWidth().hairline(Edge.Top, colors.borderSubtle)) {
    val columns = if (maxWidth < 440.dp) 2 else 4
    Column {
      cells.chunked(columns).forEach { row ->
        Row(Modifier.fillMaxWidth()) {
          row.forEachIndexed { index, cell ->
            Column(
              Modifier.weight(1f).then(if (index < row.lastIndex) Modifier.hairline(Edge.End, colors.borderSubtle) else Modifier)
                .clickable(role = Role.Button, onClickLabel = "Go to ${cell.title}", onClick = { onCellClick(cell.targetKey) })
                .semantics { contentDescription = "${cell.title}: ${cell.value}. Go to ${cell.title}" }.padding(horizontal = 12.dp, vertical = 10.dp),
              verticalArrangement = Arrangement.spacedBy(0.dp),
            ) {
              BasicText(cell.title.uppercase(), style = SkillBillTheme.type.t2xs.copy(color = colors.fgFaint, fontWeight = FontWeight.Normal, letterSpacing = .06.em, lineHeight = 1.45.em))
              BasicText(verifyScoreValue(cell), Modifier.padding(top = 2.dp, bottom = 4.dp), style = SkillBillTheme.type.tMd.copy(color = colors.fg, fontWeight = FontWeight.SemiBold, lineHeight = 1.45.em), maxLines = 1, overflow = TextOverflow.Ellipsis)
              BasicText(cell.note, style = SkillBillTheme.type.tXs.copy(color = colors.fgMuted, fontWeight = FontWeight.Normal, lineHeight = 1.45.em), maxLines = 1, overflow = TextOverflow.Ellipsis)
              Spacer(Modifier.height(4.dp))
              SegmentBar(cell.segments.map { BarSegment(it.count.toFloat(), verifyTone(it.tone)) }, description = "${cell.title} score breakdown")
            }
          }
          repeat(columns - row.size) { Spacer(Modifier.weight(1f)) }
        }
      }
    }
  }
}

@Composable
private fun verifyScoreValue(cell: VerifyScoreCell) = buildAnnotatedString {
  val small = SpanStyle(fontSize = SkillBillTheme.type.tSm.fontSize, fontWeight = FontWeight.Normal, color = SkillBillTheme.colors.fgMuted)
  val split = cell.value.indexOf(' ')
  if (cell.title in setOf("Criteria", "Review") && split > 0) {
    append(cell.value.take(split))
    withStyle(small) { append(cell.value.substring(split)) }
  } else if (cell.title !in setOf("Criteria", "Review") || cell.value == "—") {
    withStyle(small.copy(color = SkillBillTheme.colors.fg)) { append(cell.value) }
  } else append(cell.value)
}
