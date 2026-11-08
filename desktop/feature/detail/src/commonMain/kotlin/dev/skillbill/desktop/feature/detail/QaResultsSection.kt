package dev.skillbill.desktop.feature.detail

import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicText
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.input.pointer.PointerIcon
import androidx.compose.ui.input.pointer.pointerHoverIcon
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import dev.skillbill.desktop.core.designsystem.LocalContentColor
import dev.skillbill.desktop.core.designsystem.SkillBillRadius
import dev.skillbill.desktop.core.designsystem.SkillBillTheme
import dev.skillbill.desktop.core.designsystem.list.sbRowSurface
import dev.skillbill.desktop.core.designsystem.component.rememberRuntimeText
import dev.skillbill.desktop.core.designsystem.component.SbDivider
import dev.skillbill.desktop.core.designsystem.component.SbTag
import dev.skillbill.desktop.core.designsystem.component.sbFocusRing
import dev.skillbill.desktop.core.designsystem.detail.GroupLabel
import dev.skillbill.desktop.core.designsystem.detail.SectionCollapse
import dev.skillbill.desktop.core.designsystem.detail.sbBoxSegment
import dev.skillbill.desktop.core.designsystem.status.StatusDot
import dev.skillbill.desktop.core.designsystem.icon.LucideIcons
import dev.skillbill.desktop.core.designsystem.icon.SbIcon

/** Verdict and sentence, the groups that need the reader, the folds, then the targets line. Fold and row state lives in [collapse]. */
internal fun DetailSections.qaResultsSection(key: String, title: String, view: QaResultsView, onAction: (DetailAction) -> Unit) {
  val state = collapse
  val base = "qa-results:${view.runKey}"
  section(key, title, actions = { QaVerdictMark(view) }) {
    item(key = "$key-sentence") {
      BasicText(view.sentence, style = SkillBillTheme.type.tMd.copy(color = SkillBillTheme.colors.fg))
    }
    if (view.open.isNotEmpty()) {
<<<<<<< HEAD
      item(key = "$key-open-label") { QaGroupLabel(view.openLabel, view.open.size) }
=======
      item(key = "$key-open-label") {
        GroupLabel("Needs you", count = view.open.size.toString(), modifier = Modifier.padding(top = 16.dp, bottom = 6.dp))
      }
>>>>>>> origin/main
      view.open.forEachIndexed { index, row ->
        item(key = qaFindingRowKey(row.id)) { QaFindingItem(row, index, view.open.size, state, "$base:f:${row.id}", onAction, view.evidenceMode) }
      }
    }
    if (view.left.isNotEmpty()) {
      item(key = "$key-left-label") {
        GroupLabel("Left behind", count = view.left.size.toString(), modifier = Modifier.padding(top = 16.dp, bottom = 6.dp))
      }
      view.left.forEachIndexed { index, row ->
        item(key = "$key-left-$index") { QaLeftItem(row, index, view.left.size, onAction) }
      }
    }
    if (view.notTested.isNotEmpty()) {
      item(key = "$key-gap-label") {
        GroupLabel("Not tested", count = view.notTested.size.toString(), modifier = Modifier.padding(top = 16.dp, bottom = 6.dp))
      }
      view.notTested.forEachIndexed { index, row ->
        item(key = "$key-gap-$index") { QaGapItem(row, index, view.notTested.size, state, "$base:g:$index", onAction, view.evidenceMode) }
      }
    }
    if (view.unblock.isNotEmpty()) {
      item(key = "$key-unblock-label") {
        GroupLabel("Unblock", count = view.unblock.size.toString(), modifier = Modifier.padding(top = 16.dp, bottom = 6.dp))
      }
      view.unblock.forEachIndexed { index, entry ->
        item(key = "$key-unblock-$index") { QaUnblockItem(entry, index, view.unblock.size, onAction, view.evidenceMode) }
      }
      item(key = "$key-unblock-open") { QaUnblockOpen(onAction) }
    }
    val folds = qaFolds(view, base, state, onAction)
    if (folds.isNotEmpty()) {
      item(key = "$key-folds-gap") { Spacer(Modifier.height(16.dp)) }
      folds.forEachIndexed { index, fold ->
        item(key = "$key-fold-$index") {
          Column(Modifier.fillMaxWidth().sbBoxSegment(index, folds.size)) {
            if (index > 0) SbDivider()
            fold()
          }
        }
      }
    }
    view.targets?.let { targets ->
      item(key = "$key-targets") {
        BasicText(targets, Modifier.padding(top = 12.dp), style = SkillBillTheme.type.tSm.copy(color = SkillBillTheme.colors.fgFaint))
      }
    }
  }
}

@Composable
internal fun QaResultsContent(
  view: QaResultsView,
  state: SectionCollapse,
  onAction: (DetailAction) -> Unit,
  showVerdictMark: Boolean = false,
  beforeFindings: @Composable () -> Unit = {},
  findingExtra: @Composable (QaFindingRow) -> Unit = {},
) {
  val base = "qa-results:${view.runKey}"
  Column(Modifier.fillMaxWidth()) {
    if (showVerdictMark) Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
      QaVerdictMark(view)
      BasicText(view.sentence, style = SkillBillTheme.type.tMd.copy(color = SkillBillTheme.colors.fg))
    } else BasicText(view.sentence, style = SkillBillTheme.type.tMd.copy(color = SkillBillTheme.colors.fg))
    if (view.open.isNotEmpty()) {
      beforeFindings()
<<<<<<< HEAD
      QaGroupLabel(view.openLabel, view.open.size)
=======
      GroupLabel(
        "Needs you",
        count = view.open.size.toString(),
        modifier = Modifier.padding(top = 16.dp, bottom = 6.dp),
      )
>>>>>>> origin/main
      view.open.forEachIndexed { index, row ->
        QaFindingItem(row, index, view.open.size, state, "$base:f:${row.id}", onAction, view.evidenceMode)
        findingExtra(row)
      }
    }
    if (view.left.isNotEmpty()) {
      GroupLabel(
        "Left behind",
        count = view.left.size.toString(),
        modifier = Modifier.padding(top = 16.dp, bottom = 6.dp),
      )
      view.left.forEachIndexed { index, row -> QaLeftItem(row, index, view.left.size, onAction) }
    }
    if (view.notTested.isNotEmpty()) {
      GroupLabel(
        "Not tested",
        count = view.notTested.size.toString(),
        modifier = Modifier.padding(top = 16.dp, bottom = 6.dp),
      )
      view.notTested.forEachIndexed { index, row -> QaGapItem(row, index, view.notTested.size, state, "$base:g:$index", onAction, view.evidenceMode) }
    }
    if (view.unblock.isNotEmpty()) {
      GroupLabel(
        "Unblock",
        count = view.unblock.size.toString(),
        modifier = Modifier.padding(top = 16.dp, bottom = 6.dp),
      )
      view.unblock.forEachIndexed { index, entry -> QaUnblockItem(entry, index, view.unblock.size, onAction, view.evidenceMode) }
      QaUnblockOpen(onAction)
    }
    val folds = qaFolds(view, base, state, onAction)
    if (folds.isNotEmpty()) {
      Spacer(Modifier.height(16.dp))
      folds.forEachIndexed { index, fold ->
        Column(Modifier.fillMaxWidth().sbBoxSegment(index, folds.size)) {
          if (index > 0) SbDivider()
          fold()
        }
      }
    }
    view.targets?.let { targets ->
      BasicText(targets, Modifier.padding(top = 12.dp), style = SkillBillTheme.type.tSm.copy(color = SkillBillTheme.colors.fgFaint))
    }
  }
}

private fun qaFolds(view: QaResultsView, base: String, state: SectionCollapse, onAction: (DetailAction) -> Unit): List<@Composable () -> Unit> = buildList<@Composable () -> Unit> {
  view.fixed?.let { fold -> add { QaListFoldItem(fold, state, "$base:fixed", onAction, view.evidenceMode) } }
  view.passed?.let { fold -> add { QaListFoldItem(fold, state, "$base:passed", onAction, view.evidenceMode) } }
  view.runDetails?.let { details ->
    add {
      val colors = SkillBillTheme.colors
      val open = !state.isCollapsed("$base:details", default = true)
      QaFoldHead("Run details", "", LucideIcons.fileText, colors.fgFaint, open) { state.toggle("$base:details", default = true) }
      if (open) BasicText(rememberRuntimeText(details), Modifier.padding(start = 16.dp, end = 16.dp, bottom = 12.dp), style = SkillBillTheme.type.tSm.copy(color = colors.fg2))
    }
  }
  view.report?.let { report -> add { QaReportItem(report, view.reportPath, state, "$base:report", onAction) } }
}

@Composable
private fun QaVerdictMark(view: QaResultsView) {
  val colors = SkillBillTheme.colors
  val color = when (view.tone) {
    QaResultsTone.Failed -> colors.stFailed
    QaResultsTone.Blocked -> colors.stBlocked
    QaResultsTone.Done -> colors.stDone
    QaResultsTone.Faint -> colors.fgMuted
  }
  StatusDot(color, Modifier.clearAndSetSemantics { })
  BasicText(view.word, style = SkillBillTheme.type.tSmMedium.copy(color = color), maxLines = 1)
}

@Composable
private fun QaUnblockItem(entry: QaUnblockEntry, index: Int, count: Int, onAction: (DetailAction) -> Unit, mode: QaEvidenceMode) {
  Column(Modifier.fillMaxWidth().sbBoxSegment(index, count)) {
    if (index > 0) SbDivider()
    QaItemHead(LucideIcons.warn, SkillBillTheme.colors.stBlocked, null, entry.capability, null, entry.target?.let { "Target: $it" }, null) {}
    if (entry.remedy != null || entry.tried != null || entry.evidencePaths.isNotEmpty() || entry.snippet != null) {
      Column(Modifier.padding(start = 34.dp, end = 12.dp, bottom = 12.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        entry.remedy?.let { QaField("Remedy", it) }
        entry.tried?.let { QaField("Tried", it) }
        if (entry.evidencePaths.isNotEmpty() || entry.snippet != null) {
          QaEvidenceChips(entry.evidencePaths, entry.evidenceDirectory, onAction, mode) {
            entry.snippet?.let { snippet -> SbTag("Copy for qa.md", icon = LucideIcons.copy, onClick = { onAction(DetailAction.Copy(snippet)) }) }
          }
        }
      }
    }
  }
}

@Composable
private fun QaUnblockOpen(onAction: (DetailAction) -> Unit) {
  Row(Modifier.padding(start = 34.dp, end = 12.dp, bottom = 12.dp)) {
    SbTag("Open qa.md", Modifier.semantics { contentDescription = "Open .skill-bill/phases/qa.md" }, icon = LucideIcons.external, onClick = {
      onAction(DetailAction.OpenQaPhaseInstructions)
    })
  }
}

/** The click, focus and hover a row or fold head takes; [open] null means nothing expands, so there is no click at all. */
@Composable
private fun qaHeadModifier(open: Boolean?, onToggle: () -> Unit): Modifier {
  if (open == null) return Modifier
  val interaction = remember { MutableInteractionSource() }
  return Modifier
    .sbRowSurface(interaction, shape = RoundedCornerShape(SkillBillRadius.xs))
    .sbFocusRing(interaction, RoundedCornerShape(SkillBillRadius.xs))
    .pointerHoverIcon(PointerIcon.Hand)
    .clickable(interaction, indication = null, role = Role.Button, onClick = onToggle)
    .semantics { stateDescription = if (open) "Expanded" else "Collapsed" }
}

@Composable
private fun QaChevron(open: Boolean) {
  CompositionLocalProvider(LocalContentColor provides SkillBillTheme.colors.fgFaint) {
    SbIcon(if (open) LucideIcons.chevronDown else LucideIcons.chevronRight, 12.dp)
  }
}

@Composable
private fun QaItemHead(
  glyph: ImageVector,
  glyphColor: Color,
  ref: String?,
  title: String,
  state: String?,
  line: String?,
  open: Boolean?,
  onToggle: () -> Unit,
) {
  val colors = SkillBillTheme.colors
  val type = SkillBillTheme.type
  Row(
    Modifier.fillMaxWidth().then(qaHeadModifier(open, onToggle)).padding(horizontal = 12.dp, vertical = 10.dp),
    horizontalArrangement = Arrangement.spacedBy(8.dp),
  ) {
    CompositionLocalProvider(LocalContentColor provides glyphColor) { SbIcon(glyph, 14.dp, modifier = Modifier.padding(top = 2.dp)) }
    Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
      Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
        ref?.let { BasicText(it, style = type.monoSm.copy(color = colors.stFailed)) }
        BasicText(rememberRuntimeText(title), Modifier.weight(1f, fill = false), style = type.tSmMedium.copy(color = colors.fg))
      }
      line?.let {
        BasicText(
          rememberRuntimeText(it),
          style = type.tSm.copy(color = colors.fgMuted),
          maxLines = if (open == false) 2 else Int.MAX_VALUE,
          overflow = TextOverflow.Ellipsis,
        )
      }
    }
    Row(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalAlignment = Alignment.CenterVertically) {
      state?.let { BasicText(it, style = type.tXs.copy(color = colors.stFailed), maxLines = 1) }
      open?.let { QaChevron(it) }
    }
  }
}

@Composable
private fun QaField(label: String, text: String) {
  val colors = SkillBillTheme.colors
  Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
    BasicText(label, style = SkillBillTheme.type.tXs.copy(color = colors.fgFaint))
    BasicText(rememberRuntimeText(text), style = SkillBillTheme.type.tSm.copy(color = colors.fg2))
  }
}

/** Evidence opens against the row's own invocation directory, so without one no chip draws, as in [QaCaseCard]. */
@Composable
private fun QaEvidenceChips(paths: List<String>, directory: String?, onAction: (DetailAction) -> Unit, mode: QaEvidenceMode, extra: @Composable () -> Unit = {}) {
  FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
    when (mode) {
      QaEvidenceMode.Invocation -> if (directory != null) paths.forEach { path -> EvidenceChip(path) { onAction(DetailAction.OpenQaEvidence(path, directory)) } }
      QaEvidenceMode.VerifyNamesOnly -> paths.forEach { path ->
        SbTag(fileName(path))
      }
    }
    extra()
  }
}

@Composable
private fun QaFindingItem(row: QaFindingRow, index: Int, count: Int, state: SectionCollapse, part: String, onAction: (DetailAction) -> Unit, mode: QaEvidenceMode) {
  val colors = SkillBillTheme.colors
  val hasEvidence = row.evidencePaths.isNotEmpty() && (row.evidenceDirectory != null || mode == QaEvidenceMode.VerifyNamesOnly)
  val expandable = row.expected != null || row.steps != null || hasEvidence
  val open = expandable && !state.isCollapsed(part, default = true)
  Column(Modifier.fillMaxWidth().sbBoxSegment(index, count)) {
<<<<<<< HEAD
    if (index > 0) QaRowRule()
    QaItemHead(LucideIcons.alert, if (row.state == "Fixing") colors.stBlocked else colors.stFailed, row.id, row.title, row.state, row.observed, open.takeIf { expandable }) { state.toggle(part, default = true) }
=======
    if (index > 0) SbDivider()
    QaItemHead(LucideIcons.alert, colors.stFailed, row.id, row.title, row.state, row.observed, open.takeIf { expandable }) { state.toggle(part, default = true) }
>>>>>>> origin/main
    if (open) {
      Column(Modifier.padding(start = 34.dp, end = 12.dp, bottom = 12.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        row.expected?.let { QaField("Expected", it) }
        row.steps?.let { QaField("Steps", it) }
        if (hasEvidence || row.copySteps != null) {
          QaEvidenceChips(row.evidencePaths, row.evidenceDirectory, onAction, mode) {
            row.copySteps?.let { steps -> SbTag("Copy steps", icon = LucideIcons.copy, onClick = { onAction(DetailAction.Copy(steps)) }) }
          }
        }
      }
    }
  }
}

@Composable
private fun QaGapItem(row: QaDetailRow, index: Int, count: Int, state: SectionCollapse, part: String, onAction: (DetailAction) -> Unit, mode: QaEvidenceMode) {
  val colors = SkillBillTheme.colors
  val expandable = row.evidencePaths.isNotEmpty() && (row.evidenceDirectory != null || mode == QaEvidenceMode.VerifyNamesOnly)
  val open = expandable && !state.isCollapsed(part, default = true)
  Column(Modifier.fillMaxWidth().sbBoxSegment(index, count)) {
    if (index > 0) SbDivider()
    QaItemHead(LucideIcons.info, colors.stBlocked, null, row.title, null, row.detail.takeIf { it.isNotBlank() }, open.takeIf { expandable }) { state.toggle(part, default = true) }
    if (open) {
      Column(Modifier.padding(start = 34.dp, end = 12.dp, bottom = 12.dp)) { QaEvidenceChips(row.evidencePaths, row.evidenceDirectory, onAction, mode) }
    }
  }
}

@Composable
private fun QaLeftItem(row: QaLeftRow, index: Int, count: Int, onAction: (DetailAction) -> Unit) {
  val colors = SkillBillTheme.colors
  Column(Modifier.fillMaxWidth().sbBoxSegment(index, count)) {
    if (index > 0) SbDivider()
    QaItemHead(LucideIcons.warn, colors.stBlocked, null, row.title, null, row.detail.takeIf { it.isNotBlank() }, null) {}
    row.copyText?.let { text ->
      Row(Modifier.padding(start = 34.dp, end = 12.dp, bottom = 12.dp)) {
        SbTag(row.copyLabel, icon = LucideIcons.copy, onClick = { onAction(DetailAction.Copy(text)) })
      }
    }
  }
}

@Composable
private fun QaFoldHead(label: String, preview: String, glyph: ImageVector, glyphColor: Color, open: Boolean, onToggle: () -> Unit) {
  val colors = SkillBillTheme.colors
  Row(
    Modifier.fillMaxWidth().then(qaHeadModifier(open, onToggle)).padding(horizontal = 12.dp, vertical = 9.dp),
    horizontalArrangement = Arrangement.spacedBy(8.dp),
    verticalAlignment = Alignment.CenterVertically,
  ) {
    CompositionLocalProvider(LocalContentColor provides glyphColor) { SbIcon(glyph, 14.dp) }
    BasicText(label, style = SkillBillTheme.type.tSmMedium.copy(color = colors.fg2), maxLines = 1)
    Box(Modifier.weight(1f)) {
      if (!open && preview.isNotEmpty()) BasicText(rememberRuntimeText(preview), style = SkillBillTheme.type.tSm.copy(color = colors.fgFaint), maxLines = 1, overflow = TextOverflow.Ellipsis)
    }
    QaChevron(open)
  }
}

@Composable
private fun QaListFoldItem(fold: QaListFold, state: SectionCollapse, part: String, onAction: (DetailAction) -> Unit, mode: QaEvidenceMode) {
  val colors = SkillBillTheme.colors
  val open = !state.isCollapsed(part, default = true)
  QaFoldHead("${fold.label} · ${fold.rows.size}", fold.preview, LucideIcons.check, colors.stDone, open) { state.toggle(part, default = true) }
  if (open) {
    SbDivider()
    Column(Modifier.padding(start = 34.dp, end = 12.dp, top = 8.dp, bottom = 12.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
      fold.rows.forEach { row ->
        Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
          BasicText(rememberRuntimeText(row.title), style = SkillBillTheme.type.tSmMedium.copy(color = colors.fg2))
          if (row.detail.isNotBlank()) BasicText(rememberRuntimeText(row.detail), style = SkillBillTheme.type.tSm.copy(color = colors.fgMuted))
          if (row.evidencePaths.isNotEmpty() && (row.evidenceDirectory != null || mode == QaEvidenceMode.VerifyNamesOnly)) QaEvidenceChips(row.evidencePaths, row.evidenceDirectory, onAction, mode)
        }
      }
    }
  }
}

/** The first paragraph stays in this item, so opening the fold never changes the box's segment count. */
@Composable
private fun QaReportItem(report: QaReportFold, reportPath: String?, state: SectionCollapse, part: String, onAction: (DetailAction) -> Unit) {
  val colors = SkillBillTheme.colors
  val open = !state.isCollapsed(part, default = true)
  if (!open) {
    report.firstParagraph?.let {
      BasicText(rememberRuntimeText(it), Modifier.padding(start = 16.dp, end = 16.dp, top = 12.dp, bottom = 12.dp), style = SkillBillTheme.type.tSm.copy(color = colors.fg2))
      SbDivider()
    }
  }
  QaFoldHead("Full report", report.preview, LucideIcons.fileText, colors.fgFaint, open) { state.toggle(part, default = true) }
  if (open) {
    SbDivider()
    Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
      ReportText(report.text)
      reportPath?.let { path -> SbTag("Open report.md", icon = LucideIcons.external, onClick = { onAction(DetailAction.OpenQaReport(path)) }) }
    }
  }
}
