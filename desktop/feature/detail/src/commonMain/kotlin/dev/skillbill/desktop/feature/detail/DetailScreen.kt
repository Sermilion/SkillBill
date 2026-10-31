package dev.skillbill.desktop.feature.detail

import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicText
import androidx.compose.runtime.Composable
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveableStateHolder
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import dev.skillbill.desktop.core.designsystem.SkillBillRadius
import dev.skillbill.desktop.core.designsystem.SkillBillTheme
import dev.skillbill.desktop.core.designsystem.component.EmptyState
import dev.skillbill.desktop.core.designsystem.component.FeedbackTone
import dev.skillbill.desktop.core.designsystem.component.Hint
import dev.skillbill.desktop.core.designsystem.component.SbButton
import dev.skillbill.desktop.core.designsystem.component.SbIconButton
import dev.skillbill.desktop.core.designsystem.detail.DocBlockGap
import dev.skillbill.desktop.core.designsystem.detail.rememberSectionCollapse
import dev.skillbill.desktop.core.designsystem.detail.Section
import dev.skillbill.desktop.core.designsystem.icon.LucideIcons
import dev.skillbill.desktop.core.designsystem.navigation.Crumb
import dev.skillbill.desktop.core.designsystem.navigation.Pager
import dev.skillbill.desktop.core.designsystem.navigation.ViewHeader
import dev.skillbill.desktop.core.domain.model.BoardSectionKind
import dev.skillbill.desktop.core.domain.model.DetailList
import dev.skillbill.desktop.core.domain.model.Lifecycle
import dev.skillbill.desktop.core.domain.model.WorkKind
import dev.skillbill.desktop.core.domain.model.WorkLiveSection
import dev.skillbill.desktop.core.ui.feedback.StaleIndicator
import dev.skillbill.desktop.core.ui.feedback.readProblemText
import dev.skillbill.desktop.core.ui.navigation.AppRoute
import dev.skillbill.desktop.core.ui.navigation.HistoryRoute
import dev.skillbill.desktop.core.ui.navigation.WorkRoute
import dev.skillbill.desktop.core.ui.shell.LocalShellLayout

/** `.sb-doc` max-width, padding included. */
private val DOC_MAX_WIDTH = 780.dp

/** The shell facts the detail shows without depending on the shell. */
internal class DetailScreenHost(
  val detailsPaneOpen: Boolean,
  val detailsShortcut: String,
  val onToggleDetails: () -> Unit,
  val specsOpen: Boolean = false,
  val terminalOpen: Boolean = false,
  val onToggleSpecs: () -> Unit = {},
  val onToggleTerminal: () -> Unit = {},
  val chatOpen: Boolean = false,
  val chatPaneOpen: Boolean = false,
  val chatShortcut: String = "",
  val onToggleChat: () -> Unit = {},
)

private fun sectionCrumb(section: WorkLiveSection?): String = when (section) {
  WorkLiveSection.NeedsYou -> "Needs you"
  WorkLiveSection.Goals -> "Goal runs"
  WorkLiveSection.Phases -> "Standalone phases"
  WorkLiveSection.Operations -> "Operations"
  null -> "History"
}

/** screens.js `detail()`: the middle crumb opens its board section, or History for a finished item. */
private fun sectionRoute(section: WorkLiveSection?): AppRoute = when (section) {
  WorkLiveSection.NeedsYou -> WorkRoute(BoardSectionKind.NeedsYou)
  WorkLiveSection.Goals -> WorkRoute(BoardSectionKind.Goals)
  WorkLiveSection.Phases -> WorkRoute(BoardSectionKind.Phases)
  WorkLiveSection.Operations -> WorkRoute(BoardSectionKind.Operations)
  null -> HistoryRoute
}

@Composable
internal fun DetailScreen(state: DetailUiState, host: DetailScreenHost, onAction: (DetailAction) -> Unit) {
  // Collapsed sections stay collapsed while the reader pages between items.
  val collapse = rememberSectionCollapse()
  val assessmentOrder = remember { mutableStateOf(AssessmentOrder.Default) }
  if (!state.itemResolved) {
    when (val sections = state.sections) {
      SectionsUi.Pending -> EmptyState("Checking this work item in the current database")
      SectionsUi.Gone -> EmptyState("This work item is no longer in the database") {
        SbButton("Back to Work", { onAction(DetailAction.Navigate(WorkRoute(null))) })
      }
      is SectionsUi.Failed -> EmptyState("Couldn't load this work item", body = readProblemText(sections.problem))
      is SectionsUi.Loaded -> EmptyState("Checking this work item in the current database")
    }
    return
  }
  val item = state.header
  val view = runActionsView(state)
  val recoverFocus = remember { FocusRequester() }
  val listState = rememberLazyListState()
  val peekState = rememberSaveableStateHolder()
  Column(Modifier.fillMaxSize()) {
    ViewHeader(
      crumbs = listOf(
        Crumb("Work", onClick = { onAction(DetailAction.Navigate(WorkRoute(null))) }),
        Crumb(sectionCrumb(item.liveSection), onClick = { onAction(DetailAction.Navigate(sectionRoute(item.liveSection))) }),
        Crumb(rowKey(item)),
      ),
      actions = {
        state.boardPosition?.let { position ->
          Pager(position.index, position.total, onPrevious = { onAction(DetailAction.Previous) }, onNext = { onAction(DetailAction.Next) })
        }
        if (state.stale) StaleIndicator()
        // paneToggles(st), then the details toggle.
        SbIconButton(LucideIcons.refresh, "Refresh", { onAction(DetailAction.Retry) })
        SbIconButton(LucideIcons.fileText, if (host.specsOpen) "Close specs pane" else "Specs", host.onToggleSpecs, shortcut = "G S", selected = host.specsOpen)
        SbIconButton(LucideIcons.terminal, if (host.terminalOpen) "Close terminal" else "Terminal", host.onToggleTerminal, shortcut = "⌃ `", selected = host.terminalOpen)
        SbIconButton(LucideIcons.bot, if (host.chatOpen) "Close chat" else "Chat", host.onToggleChat, shortcut = host.chatShortcut, selected = host.chatOpen)
        SbIconButton(LucideIcons.panelRight, "Toggle details", host.onToggleDetails, shortcut = host.detailsShortcut, selected = host.detailsPaneOpen)
      },
    )
    Box(Modifier.weight(1f).fillMaxWidth()) {
      // `.sb-doc`: a 780 column (padding included) centred in the scroll area, 32/40/64 padding, 20/16/48 in a narrow window.
      // The list adds no spacing of its own: each block brings its 28 gap, so a section's rows can sit flush in one box.
      BoxWithConstraints(Modifier.fillMaxSize()) {
        val narrow = LocalShellLayout.current.drawerMode
        val inset = if (narrow) 16.dp else 40.dp
        val side = maxOf(inset, (maxWidth - (DOC_MAX_WIDTH - inset * 2)) / 2)
        LazyColumn(
          Modifier.fillMaxSize(),
          state = listState,
          contentPadding = PaddingValues(start = side, end = side, top = if (narrow) 20.dp else 32.dp, bottom = if (narrow) 48.dp else 64.dp),
        ) {
          val sections = DetailSections(this, collapse, assessmentOrder)
          sections.item(key = "header") {
            DetailHeader(item, state.stale, { onAction(DetailAction.RevealFolder) }, { onAction(DetailAction.OpenBranch) }) {
              if (state.sections != SectionsUi.Gone) {
                val agentChoices = (state.sections as? SectionsUi.Loaded)?.runOptions?.agentChoices.orEmpty()
                RunActions(view, agentChoices, state.controls.agentMenuOpen, recoverFocus, onAction)
              }
            }
          }
          // HANDOFF 9.2 item 5: the pause notice is its own block under the header.
          if (view.pausePending) sections.item(key = "pause-notice") {
            Column(Modifier.padding(top = DocBlockGap)) { Hint("Pause recorded. The goal pauses at its next checkpoint.", FeedbackTone.Info, LucideIcons.pause) }
          }
          if (item.reason != null && item.lifecycle != Lifecycle.Terminal) sections.item(key = "reason") {
            Column(Modifier.padding(top = DocBlockGap)) { DetailReason(item) { onAction(DetailAction.Copy(it)) } }
          }
          sections.document(state, view, onAction)
        }
      }
      DetailPeek(
        open = host.detailsPaneOpen && !host.specsOpen && !host.chatPaneOpen,
        wide = (state.sections as? SectionsUi.Loaded)?.detail?.let {
          selectedSubtask(item, it, state.subtaskChoice.key) == SubtaskKey.GoalFinish
        } == true,
        onDismiss = { if (host.detailsPaneOpen) host.onToggleDetails() },
      ) {
        peekState.SaveableStateProvider("${item.id}:${state.subtaskChoice.key}") {
          DetailsPane(state, assessmentOrder, onAction)
        }
      }
    }
  }
  state.controls.recoveryDialog?.let { preview ->
    RecoveryDialog(
      preview,
      applying = state.controls.inFlight == InFlightControl.Recover,
      returnFocusTo = recoverFocus,
      onApply = { onAction(ControlsAction.ApplyRecovery) },
      onDismiss = { onAction(ControlsAction.DismissRecovery) },
      onCopy = { onAction(DetailAction.Copy(it)) },
    )
  }
}

/** HANDOFF 9.2 item 7, with the operator decision inside it; a decision with no incident gets its own section. */
private fun DetailSections.incident(state: DetailUiState, view: RunActionsView, onAction: (DetailAction) -> Unit) {
  val detail = (state.sections as? SectionsUi.Loaded)?.detail
  val incident = detail?.incident
  val decision: @Composable () -> Unit = {
    view.decision?.let {
      OperatorDecisionBlock(it, view.recordEnabled, view.usable && state.controls.inFlight == null, state.controls.inFlight == InFlightControl.Decision, onAction)
    }
  }
  val sectionCollapse = collapse
  if (incident != null) item(key = "incident") {
    IncidentSection(incident, view, decision, onAction, sectionCollapse, { onAction(DetailAction.Copy(it)) }, Modifier.padding(top = DocBlockGap))
  }
  if (incident == null && view.decision != null && state.sections != SectionsUi.Gone) item(key = "decision") {
    Section(title = "Operator decision", Modifier.padding(top = DocBlockGap)) { decision() }
  }
}

/** The sections in HANDOFF 9.2 order; placeholders hold their place until the first read lands. */
private fun DetailSections.document(state: DetailUiState, view: RunActionsView, onAction: (DetailAction) -> Unit) {
  val item = state.header
  when (val sections = state.sections) {
    SectionsUi.Pending -> {
      placeholder("metrics", 72.dp)
      placeholder("subtasks", 160.dp)
      placeholder("activity", 96.dp)
    }
    is SectionsUi.Loaded -> {
      val detail = sections.detail
      // HANDOFF 9.2 order: the incident (item 7) comes before the metrics (item 8).
      incident(state, view, onAction)
      item(key = "metrics") {
        Column(Modifier.padding(top = DocBlockGap)) { DetailMetrics(item, detail) }
      }
      subtaskSection(item, detail, state, onAction)
      pipelineSection(item, detail)
      briefingSection(item, detail, onAction)
      activitySection(item, detail, state, onAction)
      // A standalone run's own result comes right after its activity.
      standaloneResultSection(item, detail)
      qaReportSection(item, detail, onAction)
      if (item.kind != WorkKind.Goal) {
        goalQaSection(detail)
        goalAssessmentSection(detail)
      }
      // Standalone phases and operations keep their dependencies, validation, phase results and spec bundle in the document.
      dependencySection(item, detail, state, onAction)
      validationSection(item, detail)
      phaseResultsSection(item, detail)
      if (item.kind != WorkKind.Goal) pageFooter(DetailList.Validation, state, onAction)
      specBundleSection(item, detail, onAction)
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

private fun DetailSections.placeholder(key: String, height: Dp) {
  item(key = key) {
    val shape = RoundedCornerShape(SkillBillRadius.md)
    Box(Modifier.padding(top = DocBlockGap).fillMaxWidth().heightIn(min = height).border(1.dp, SkillBillTheme.colors.borderSubtle, shape).padding(12.dp), contentAlignment = Alignment.TopStart) {
      BasicText("Loading", style = SkillBillTheme.type.tSm.copy(color = SkillBillTheme.colors.fgFaint))
    }
  }
}
