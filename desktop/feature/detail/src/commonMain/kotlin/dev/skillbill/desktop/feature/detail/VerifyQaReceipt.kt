package dev.skillbill.desktop.feature.detail

import androidx.compose.foundation.Canvas
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.text.BasicText
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.dp
import dev.skillbill.desktop.core.designsystem.LocalContentColor
import dev.skillbill.desktop.core.designsystem.SkillBillTheme
import dev.skillbill.desktop.core.designsystem.composite.Edge
import dev.skillbill.desktop.core.designsystem.composite.hairline
import dev.skillbill.desktop.core.designsystem.detail.SbBox
import dev.skillbill.desktop.core.designsystem.detail.SectionCollapse
import dev.skillbill.desktop.core.designsystem.icon.LucideIcons
import dev.skillbill.desktop.core.designsystem.icon.SbIcon
import dev.skillbill.desktop.core.domain.model.VerifyRunDetails

@Composable
internal fun VerifyQaReceipt(run: VerifyRunDetails, view: QaResultsView, collapse: SectionCollapse, state: DetailUiState, items: List<FixItem>, onAction: (DetailAction) -> Unit) {
  val colors = SkillBillTheme.colors
  val tone = when (view.tone) { QaResultsTone.Failed -> colors.stFailed; QaResultsTone.Blocked -> colors.stBlocked; QaResultsTone.Done -> colors.stDone; QaResultsTone.Faint -> colors.fgFaint }
  val base = "verify-qa:${run.workflowId}"
  Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
    Row(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalAlignment = Alignment.CenterVertically) {
      Box(Modifier.size(8.dp).background(tone, CircleShape))
      BasicText(view.word, style = SkillBillTheme.type.tSmMedium.copy(color = tone))
    }
    BasicText(view.sentence, style = SkillBillTheme.type.tBase.copy(color = colors.fg))
    if (view.open.isNotEmpty()) Column {
      VerifyQaGroupLabel("Needs you", view.open.size)
      SbBox(fill = colors.bgPanel, borderColor = colors.border) {
        Box(Modifier.padding(horizontal = 12.dp, vertical = 4.dp)) { VerifyFixSelectAll(items, FixKind.QaFinding, state, verifySelectionAllowed(run), onAction) }
        view.open.forEach { finding ->
          val fix = items.filterIsInstance<FixItem.QaFinding>().firstOrNull { it.finding.id == finding.id }
          VerifyQaFinding(finding, "$base:${finding.id}", collapse, fix, state, items, verifySelectionAllowed(run), onAction)
        }
      }
    }
    if (view.notTested.isNotEmpty()) Column {
      VerifyQaGroupLabel("Not tested", view.notTested.size)
      SbBox(fill = colors.bgPanel, borderColor = colors.border) {
        view.notTested.forEach { row -> VerifyQaDetail(row, colors.stBlocked, false) }
      }
    }
    if (view.left.isNotEmpty()) Column {
      VerifyQaGroupLabel("Left behind", view.left.size)
      view.left.forEach { row -> VerifyReceiptField(row.title, row.detail) }
    }
    if (view.passed != null || view.fixed != null || view.report != null || view.runDetails != null) SbBox(fill = colors.bgPanel, borderColor = colors.border) {
      listOfNotNull(view.passed, view.fixed).forEach { fold ->
        val key = "$base:${fold.label}"
        val open = !collapse.isCollapsed(key, default = true)
        VerifyQaFold("${fold.label} · ${fold.rows.size}", fold.preview, open, { collapse.toggle(key, default = true) }, colors.stDone) {
          fold.rows.forEach { row -> VerifyQaDetail(row, colors.stDone, true) }
        }
      }
      view.runDetails?.let { details ->
        val key = "$base:details"
        VerifyQaFold("Run details", "", !collapse.isCollapsed(key, default = true), { collapse.toggle(key, default = true) }, colors.fgFaint) { VerifyReportText(details, Modifier.padding(12.dp)) }
      }
      view.report?.let { report ->
        val key = "$base:report"
        VerifyQaFold("Full report", report.preview, !collapse.isCollapsed(key, default = true), { collapse.toggle(key, default = true) }, colors.fgFaint) { VerifyReportText(report.text, Modifier.padding(16.dp)) }
      }
    }
    view.targets?.let { BasicText(it, style = SkillBillTheme.type.tSm.copy(color = colors.fgFaint)) }
  }
}

@Composable
private fun VerifyQaGroupLabel(title: String, count: Int) {
  Row(Modifier.padding(bottom = 6.dp), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
    BasicText(title, style = SkillBillTheme.type.tXs.copy(color = SkillBillTheme.colors.fgMuted, fontWeight = FontWeight.Medium))
    BasicText(count.toString(), style = SkillBillTheme.type.tXs.copy(color = SkillBillTheme.colors.fgFaint, fontWeight = FontWeight.Normal))
  }
}

@Composable
private fun VerifyQaFinding(row: QaFindingRow, key: String, collapse: SectionCollapse, fix: FixItem.QaFinding?, state: DetailUiState, items: List<FixItem>, allowed: Boolean, onAction: (DetailAction) -> Unit) {
  val colors = SkillBillTheme.colors
  val open = !collapse.isCollapsed(key, default = true)
  Column(Modifier.fillMaxWidth().hairline(Edge.Top, colors.borderSubtle)) {
    Row {
      if (allowed) Box(Modifier.width(30.dp).padding(start = 12.dp, top = 11.dp)) { fix?.let { VerifyFixCheckbox(it, state, onAction) } }
      Column(Modifier.weight(1f).clickable(role = Role.Button, onClick = { collapse.toggle(key, default = true) }).semantics { stateDescription = if (open) "Expanded" else "Collapsed" }.padding(start = if (allowed) 2.dp else 12.dp, end = 12.dp, top = 10.dp, bottom = 10.dp)) {
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.Top) {
          CompositionLocalProvider(LocalContentColor provides colors.stFailed) { SbIcon(LucideIcons.alert, 16.dp) }
          BasicText(androidx.compose.ui.text.buildAnnotatedString { withStyle(androidx.compose.ui.text.SpanStyle(color = colors.stFailed, fontFamily = SkillBillTheme.type.monoSm.fontFamily, fontSize = SkillBillTheme.type.tSm.fontSize, fontWeight = FontWeight.Normal)) { append(row.id) }; append("  "); append(row.title) }, Modifier.weight(1f), style = SkillBillTheme.type.tBaseMedium.copy(color = colors.fg))
          BasicText("Open", style = SkillBillTheme.type.tXs.copy(color = colors.stFailed))
          VerifyChevron(open)
        }
        row.observed?.let {
          if (open) VerifyReportText(it, Modifier.padding(start = 24.dp))
          else BasicText(verifyText(it), Modifier.padding(start = 24.dp), style = SkillBillTheme.type.tSm.copy(color = colors.fgMuted), maxLines = 2, overflow = TextOverflow.Ellipsis)
        }
      }
      if (allowed) Box(Modifier.width(104.dp).padding(top = 8.dp, end = 10.dp), contentAlignment = Alignment.TopEnd) { fix?.let { VerifyFixRoute(it, state, onAction) } }
    }
    if (open) Column(Modifier.padding(start = if (allowed) 56.dp else 36.dp, end = 12.dp, bottom = 12.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
      row.expected?.let { VerifyQaField("Expected", it) }
      row.steps?.let { VerifyQaField("Steps", it) }
      VerifyQaEvidence(row.evidencePaths)
      if (fix != null && allowed) VerifyFixItemDetails(fix, items, onAction)
    }
  }
}

@Composable
private fun VerifyQaField(label: String, text: String) {
  Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
    BasicText(label, style = SkillBillTheme.type.tXs.copy(color = SkillBillTheme.colors.fgFaint))
    VerifyReportText(text)
  }
}

@Composable
private fun VerifyQaDetail(row: QaDetailRow, tone: Color, passed: Boolean) {
  if (passed) Column(Modifier.fillMaxWidth().padding(start = 36.dp, end = 12.dp, top = 6.dp, bottom = 6.dp), verticalArrangement = Arrangement.spacedBy(2.dp)) {
    BasicText(row.title, style = SkillBillTheme.type.tSmMedium.copy(color = SkillBillTheme.colors.fg2))
    VerifyReportText(row.detail)
    if (row.evidencePaths.isNotEmpty()) Box(Modifier.padding(top = 4.dp)) { VerifyQaEvidence(row.evidencePaths) }
  } else Row(Modifier.fillMaxWidth().hairline(Edge.Top, SkillBillTheme.colors.borderSubtle).padding(horizontal = 12.dp, vertical = 10.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
    Canvas(Modifier.size(16.dp)) {
      drawCircle(tone, radius = size.minDimension / 3, style = Stroke(width = 1.2.dp.toPx(), pathEffect = PathEffect.dashPathEffect(floatArrayOf(2.dp.toPx(), 2.1.dp.toPx()))))
    }
    Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
      BasicText(row.title, style = SkillBillTheme.type.tBaseMedium.copy(color = SkillBillTheme.colors.fg))
      VerifyReportText(row.detail)
      VerifyQaEvidence(row.evidencePaths)
    }
  }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun VerifyQaEvidence(paths: List<String>) {
  if (paths.isNotEmpty()) FlowRow(horizontalArrangement = Arrangement.spacedBy(4.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
    paths.forEach { path ->
      val image = path.substringAfterLast('.').lowercase() in setOf("png", "jpg", "jpeg", "webp", "mp4")
      VerifyFileChip(path.substringAfterLast('/'), path, if (image) LucideIcons.image else LucideIcons.file)
    }
  }
}

@Composable
private fun VerifyQaFold(label: String, preview: String, open: Boolean, onToggle: () -> Unit, tone: Color, content: @Composable () -> Unit) {
  Column(Modifier.fillMaxWidth().hairline(Edge.Top, SkillBillTheme.colors.borderSubtle)) {
    Row(Modifier.fillMaxWidth().clickable(role = Role.Button, onClick = onToggle).semantics { stateDescription = if (open) "Expanded" else "Collapsed" }.padding(horizontal = 12.dp, vertical = 9.dp), horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
      CompositionLocalProvider(LocalContentColor provides tone) { SbIcon(if (label.startsWith("Passed") || label.startsWith("Fixed")) LucideIcons.check else LucideIcons.fileText, 16.dp) }
      BasicText(label, style = SkillBillTheme.type.tBaseMedium.copy(color = SkillBillTheme.colors.fg2))
      BasicText(if (open) "" else preview, Modifier.weight(1f), style = SkillBillTheme.type.tSm.copy(color = SkillBillTheme.colors.fgFaint), maxLines = 1, overflow = TextOverflow.Ellipsis)
      VerifyChevron(open)
    }
    if (open) content()
  }
}
