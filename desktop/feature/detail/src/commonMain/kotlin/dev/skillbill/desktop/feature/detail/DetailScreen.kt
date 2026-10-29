package dev.skillbill.desktop.feature.detail

import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicText
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import dev.skillbill.desktop.core.designsystem.SkillBillRadius
import dev.skillbill.desktop.core.designsystem.SkillBillTheme
import dev.skillbill.desktop.core.designsystem.component.EmptyState
import dev.skillbill.desktop.core.designsystem.component.SbButton
import dev.skillbill.desktop.core.designsystem.component.SbIconButton
import dev.skillbill.desktop.core.designsystem.icon.LucideIcons
import dev.skillbill.desktop.core.designsystem.navigation.Crumb
import dev.skillbill.desktop.core.designsystem.navigation.Pager
import dev.skillbill.desktop.core.designsystem.navigation.ViewHeader
import dev.skillbill.desktop.core.domain.model.WorkLiveSection
import dev.skillbill.desktop.core.ui.feedback.StaleIndicator
import dev.skillbill.desktop.core.ui.feedback.readProblemText
import dev.skillbill.desktop.core.ui.navigation.WorkRoute
import dev.skillbill.desktop.core.ui.shell.DetailsPaneSlot

/** The shell facts the detail shows without depending on the shell. */
internal class DetailScreenHost(val detailsPaneOpen: Boolean, val detailsShortcut: String, val onToggleDetails: () -> Unit)

private fun sectionCrumb(section: WorkLiveSection?): String = when (section) {
  WorkLiveSection.NeedsYou -> "Needs you"
  WorkLiveSection.Goals -> "Goal runs"
  WorkLiveSection.Phases -> "Standalone phases"
  WorkLiveSection.Operations -> "Operations"
  null -> "History"
}

@Composable
internal fun DetailScreen(state: DetailUiState, host: DetailScreenHost, onAction: (DetailAction) -> Unit) {
  val item = state.header
  Column(Modifier.fillMaxSize()) {
    ViewHeader(
      crumbs = listOf(
        Crumb("Work", onClick = { onAction(DetailAction.Navigate(WorkRoute(null))) }),
        Crumb(sectionCrumb(item.liveSection)),
        Crumb(rowKey(item)),
      ),
      actions = {
        state.boardPosition?.let { position ->
          Pager(position.index, position.total, onPrevious = { onAction(DetailAction.Previous) }, onNext = { onAction(DetailAction.Next) })
        }
        if (state.stale) StaleIndicator()
        SbIconButton(LucideIcons.panelRight, "Toggle details", host.onToggleDetails, shortcut = host.detailsShortcut, selected = host.detailsPaneOpen)
      },
    )
    Row(Modifier.weight(1f).fillMaxWidth()) {
      LazyColumn(
        Modifier.weight(1f).fillMaxSize(),
        contentPadding = PaddingValues(horizontal = 32.dp, vertical = 24.dp),
        verticalArrangement = Arrangement.spacedBy(20.dp),
      ) {
        // Subtask 17 inserts its run actions and pause hint after `header`, and the incident after `reason`.
        item(key = "header") {
          DetailHeader(item, state.stale, { onAction(DetailAction.RevealFolder) }, { onAction(DetailAction.OpenBranch) })
        }
        item(key = "reason") { DetailReason(item) { onAction(DetailAction.Copy(it)) } }
        document(state, onAction)
      }
      DetailsPaneSlot { DetailsPane(state) }
    }
  }
}

/** The sections in HANDOFF 9.2 order; placeholders hold their place until the first read lands. */
private fun LazyListScope.document(state: DetailUiState, onAction: (DetailAction) -> Unit) {
  val item = state.header
  when (val sections = state.sections) {
    SectionsUi.Pending -> {
      placeholder("metrics", 72.dp)
      placeholder("subtasks", 160.dp)
      placeholder("activity", 96.dp)
    }
    is SectionsUi.Loaded -> {
      val detail = sections.detail
      item(key = "metrics") { DetailMetrics(item, detail) }
      subtaskSection(item, detail, state, onAction)
      pipelineSection(item, detail)
      dependencySection(detail, state, onAction)
      activitySection(item.kind, detail, state.renderedAt)
      validationSection(detail, state, onAction)
      phaseResultsSection(detail, state, onAction)
      goalQaSection(detail)
      goalAssessmentSection(detail)
      qaReportSection(item, detail, onAction)
      planReportSection(item, detail)
      historySection(detail, state, onAction)
      specBundleSection(detail, onAction)
    }
    SectionsUi.Gone -> item(key = "problem") {
      EmptyState("This work item is no longer in the database") {
        SbButton("Back to Work", { onAction(DetailAction.Navigate(WorkRoute(null))) })
      }
    }
    is SectionsUi.Failed -> item(key = "problem") {
      EmptyState("Couldn't load this work item", body = readProblemText(sections.problem)) {
        SbButton("Retry", { onAction(DetailAction.Retry) })
      }
    }
  }
}

private fun LazyListScope.placeholder(key: String, height: Dp) {
  item(key = key) {
    val shape = RoundedCornerShape(SkillBillRadius.md)
    Box(Modifier.fillMaxWidth().heightIn(min = height).border(1.dp, SkillBillTheme.colors.borderSubtle, shape).padding(12.dp), contentAlignment = Alignment.TopStart) {
      BasicText("Loading", style = SkillBillTheme.type.tSm.copy(color = SkillBillTheme.colors.fgFaint))
    }
  }
}
