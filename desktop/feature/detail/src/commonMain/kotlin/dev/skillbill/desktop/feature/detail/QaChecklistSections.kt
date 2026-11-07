package dev.skillbill.desktop.feature.detail

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicText
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.semantics.Role
import dev.skillbill.desktop.core.designsystem.LocalContentColor
import dev.skillbill.desktop.core.designsystem.SkillBillRadius
import dev.skillbill.desktop.core.designsystem.SkillBillTheme
import dev.skillbill.desktop.core.designsystem.component.LocalUiTicker
import dev.skillbill.desktop.core.designsystem.component.SbButton
import dev.skillbill.desktop.core.designsystem.component.SbButtonVariant
import dev.skillbill.desktop.core.designsystem.component.SbClock
import dev.skillbill.desktop.core.designsystem.component.SbSpinner
import dev.skillbill.desktop.core.designsystem.component.SbTag
import dev.skillbill.desktop.core.designsystem.component.sbPulse
import dev.skillbill.desktop.core.designsystem.detail.DocBlockGap
import dev.skillbill.desktop.core.designsystem.detail.MetricCell
import dev.skillbill.desktop.core.designsystem.detail.MetricStrip
import dev.skillbill.desktop.core.designsystem.detail.SectionCollapse
import dev.skillbill.desktop.core.designsystem.detail.metricValueStyle
import dev.skillbill.desktop.core.designsystem.detail.relativeAge
import dev.skillbill.desktop.core.designsystem.icon.LucideIcons
import dev.skillbill.desktop.core.designsystem.icon.SbIcon
import dev.skillbill.desktop.core.designsystem.status.StatusIcon
import dev.skillbill.desktop.core.designsystem.status.StatusKind
import dev.skillbill.desktop.core.domain.model.ExecutionInstance
import dev.skillbill.desktop.core.domain.model.QaTargetState
import dev.skillbill.desktop.core.domain.model.WorkItem
import dev.skillbill.desktop.core.domain.model.WorkKind
import kotlinx.coroutines.launch

internal const val QA_RESULTS_SECTION = "qa-results"

internal fun qaFindingRowKey(id: String) = "qa-finding-$id"

internal fun isStandaloneQa(item: WorkItem) = item.kind == WorkKind.Phase && item.control.phaseId == "qa"

@Composable
internal fun rememberQaFindingScroller(listState: LazyListState, collapse: SectionCollapse, keys: LazyKeyIndex): (String) -> Unit {
  val scope = rememberCoroutineScope()
  return remember(listState, collapse, keys, scope) {
    { id ->
      scope.launch {
        if (collapse.isCollapsed(QA_RESULTS_SECTION)) collapse.toggle(QA_RESULTS_SECTION)
        repeat(5) {
          val index = keys.indexOf(qaFindingRowKey(id))
          if (index != null) {
            listState.animateScrollToItem(index)
            return@launch
          }
          withFrameNanos { }
        }
      }
    }
  }
}

internal fun DetailSections.qaStatusStrip(item: WorkItem, run: ExecutionInstance?, view: QaChecklistView) {
  item(key = "qa-status-strip") {
    MetricStrip(qaStripCells(item, run, view), Modifier.padding(top = DocBlockGap))
  }
}

@Composable
@OptIn(kotlin.time.ExperimentalTime::class)
private fun qaStripCells(item: WorkItem, run: ExecutionInstance?, view: QaChecklistView): List<MetricCell> {
  val ticker = LocalUiTicker.current
  DisposableEffect(ticker) { ticker.retain(); onDispose { ticker.release() } }
  val now = ticker.now.value
  val current = run == null || run.invocationId == item.control.invocationId
  val duration = if (current) null else ((run.finishedAt ?: run.updatedAt) - run.startedAt).inWholeMilliseconds
  val age = view.lastStepUpdateAt?.let { relativeAge(now - it) }
  val elapsedNote = when {
    view.live && age != null -> "ticking · last step update $age"
    view.live -> "ticking"
    age != null -> "last step update $age"
    else -> null
  }
  val progress = view.progress
  return listOf(
    MetricCell("Now testing", content = { Column(Modifier.semantics(mergeDescendants = true) {}, verticalArrangement = Arrangement.spacedBy(6.dp)) {
      when (val nowTesting = view.nowTesting) {
        is QaNowTesting.Step -> {
          Row(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalAlignment = Alignment.CenterVertically) {
            SbTag(nowTesting.caseId, tone = SkillBillTheme.colors.stActive, mono = true)
            BasicText(nowTesting.caseTitle, style = SkillBillTheme.type.tMd.copy(color = SkillBillTheme.colors.fg), maxLines = 2)
          }
          Row(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalAlignment = Alignment.CenterVertically) {
            SbSpinner(contentDescription = null)
            BasicText("Step ${nowTesting.stepNumber} of ${nowTesting.stepCount} · ${nowTesting.stepText}", style = SkillBillTheme.type.tSm.copy(color = SkillBillTheme.colors.fg2), maxLines = 2)
          }
        }
        is QaNowTesting.Idle -> BasicText(nowTesting.text, style = SkillBillTheme.type.tMd.copy(color = SkillBillTheme.colors.fg))
      }
    } }),
    MetricCell("Progress", content = { Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
      if (progress == null) {
        BasicText(view.planText.orEmpty(), style = SkillBillTheme.type.tMd.copy(color = SkillBillTheme.colors.fg))
      } else {
        BasicText("${progress.done} of ${progress.total} steps", style = SkillBillTheme.type.tMd.copy(color = SkillBillTheme.colors.fg))
        QaProgressBar(progress)
        BasicText(progress.summary, style = SkillBillTheme.type.tSm.copy(color = SkillBillTheme.colors.fgMuted), maxLines = 2)
      }
    } }),
    MetricCell("Phase elapsed", note = elapsedNote) {
      SbClock(if (current) item.activeDurationMs else duration, if (current) item.activeDurationAsOf else null, live = current && view.live, showSeconds = true, style = metricValueStyle())
    },
  )
}

@Composable
private fun QaProgressBar(progress: QaProgressView) {
  val colors = SkillBillTheme.colors
  val shape = RoundedCornerShape(3.dp)
  Row(Modifier.fillMaxWidth().height(6.dp).clip(shape).background(colors.borderSubtle).semantics { contentDescription = progress.description }) {
    if (progress.total > 0) {
      ProgressSegment(progress.passed, colors.stDone)
      ProgressSegment(progress.failed, colors.stFailed)
      ProgressSegment(progress.running, colors.stActive, pulse = true)
      ProgressSegment(progress.remaining, colors.borderSubtle)
    }
  }
}

@Composable
private fun RowScope.ProgressSegment(count: Int, color: Color, pulse: Boolean = false) {
  if (count > 0) Box(Modifier.weight(count.toFloat()).fillMaxHeight().then(if (pulse) Modifier.sbPulse() else Modifier).background(color))
}

@OptIn(androidx.compose.foundation.layout.ExperimentalLayoutApi::class)
internal fun DetailSections.qaTargetsSection(view: QaChecklistView) {
  if (view.targets.isEmpty()) return
  section("qa-targets", "Targets") {
    item(key = "qa-targets-row") {
      FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        view.targets.forEach { target -> QaTargetChip(target, view.live) }
      }
    }
  }
}

@Composable
internal fun QaTargetChip(target: dev.skillbill.desktop.core.domain.model.QaChecklistTarget, live: Boolean) {
  val colors = SkillBillTheme.colors
  val color = when (target.state) {
    QaTargetState.Running -> colors.stActive
    QaTargetState.Ready -> colors.stDone
    else -> colors.border
  }
  val shape = RoundedCornerShape(SkillBillRadius.md)
  Row(Modifier.background(colors.bgRaised, shape).border(1.dp, color.copy(alpha = if (target.state == QaTargetState.Running || target.state == QaTargetState.Ready) .35f else 1f), shape).padding(horizontal = 12.dp, vertical = 8.dp), horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
    when (target.state) {
      QaTargetState.Running -> if (live) SbSpinner(size = 14.dp, contentDescription = "Running") else StatusDescription("Stopped") { StatusIcon(StatusKind.Canceled, null) }
      QaTargetState.Ready -> CompositionLocalProvider(LocalContentColor provides colors.stDone) { SbIcon(LucideIcons.check, 14.dp, contentDescription = "Ready") }
      QaTargetState.Skipped -> StatusDescription("Skipped") { StatusIcon(StatusKind.Skipped, null) }
      QaTargetState.Pending -> StatusDescription("Pending") { StatusIcon(StatusKind.Pending, null) }
    }
    BasicText(target.name, style = SkillBillTheme.type.tSm.copy(color = colors.fg2))
    target.detail?.takeIf(String::isNotBlank)?.let { detail ->
      BasicText(detail, style = SkillBillTheme.type.tSm.copy(color = colors.fgMuted))
    }
  }
}

@Composable
private fun StatusDescription(description: String, content: @Composable () -> Unit) {
  Box(Modifier.clearAndSetSemantics { contentDescription = description }) { content() }
}

@OptIn(androidx.compose.foundation.layout.ExperimentalLayoutApi::class)
internal fun DetailSections.qaTestPlanSection(view: QaChecklistView, onOpenFinding: (String) -> Unit, onAction: (DetailAction) -> Unit) {
  val cases = view.cases
  if (cases.isEmpty()) {
    section("qa-plan", "Test plan") {
      item(key = "qa-plan-empty") { BasicText(view.planText.orEmpty(), style = SkillBillTheme.type.tBase.copy(color = SkillBillTheme.colors.fgMuted)) }
    }
    return
  }
  val caseCount = "${cases.size} ${if (cases.size == 1) "case" else "cases"}"
  val stepCount = "${view.stepCount} ${if (view.stepCount == 1) "step" else "steps"}"
  val written = view.planRegisteredAt?.let { "written by QA at ${formatInstant(it, "HH:mm")}" }
  val count = listOfNotNull(caseCount, stepCount, written).joinToString(" · ")
  section("qa-plan", "Test plan", count = count, actions = {
    SbButton(view.toggleLabel, { onAction(DetailAction.SetAllQaCases(cases.map { it.key }, !view.allOpen)) }, variant = SbButtonVariant.Secondary, size = dev.skillbill.desktop.core.designsystem.component.SbControlSize.Small)
  }) {
    items(cases, key = { "qa-case-${it.id}" }) { case ->
      QaCaseCard(case, view.invocationDirectory, onOpenFinding) { action -> onAction(action) }
    }
  }
}
