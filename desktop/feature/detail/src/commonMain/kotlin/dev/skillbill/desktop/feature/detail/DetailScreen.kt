package dev.skillbill.desktop.feature.detail

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.text.BasicText
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.saveable.rememberSaveableStateHolder
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.foundation.focusable
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import dev.skillbill.desktop.core.designsystem.SkillBillTheme
import dev.skillbill.desktop.core.designsystem.component.BuildStrip
import dev.skillbill.desktop.core.designsystem.component.EmptyState
import dev.skillbill.desktop.core.designsystem.component.FeedbackTone
import dev.skillbill.desktop.core.designsystem.component.Hint
import dev.skillbill.desktop.core.designsystem.component.SbButton
import dev.skillbill.desktop.core.designsystem.component.SbIconButton
import dev.skillbill.desktop.core.designsystem.detail.DocBlockGap
import dev.skillbill.desktop.core.designsystem.detail.SbBox
import dev.skillbill.desktop.core.designsystem.detail.Section
import dev.skillbill.desktop.core.designsystem.detail.rememberSectionCollapse
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
import dev.skillbill.desktop.core.ui.navigation.LocalEntryKey
import dev.skillbill.desktop.core.ui.input.LocalShellInput
import dev.skillbill.desktop.core.ui.shell.LocalShellLayout
import dev.skillbill.desktop.core.ui.status.toSbBuild

/** `.sb-doc` max-width, padding included. */
private val DOC_MAX_WIDTH = 780.dp

/** The shell facts the detail shows without depending on the shell. */
internal class DetailScreenHost(
  val detailsPaneOpen: Boolean,
  val detailsShortcut: String,
  val onToggleDetails: () -> Unit,
  val onOpenDetails: (drawerMode: Boolean) -> Unit,
  val specsOpen: Boolean = false,
  val terminalOpen: Boolean = false,
  val onToggleSpecs: () -> Unit = {},
  val onToggleTerminal: () -> Unit = {},
  val chatOpen: Boolean = false,
  val chatPaneOpen: Boolean = false,
  val chatShortcut: String = "",
  val onToggleChat: () -> Unit = {},
  val detailsWidthDp: Int? = null,
  val onResizeDetails: (Int) -> Unit = {},
  val onResetDetails: () -> Unit = {},
  val onOpenParkedVerify: (adjust: Boolean) -> Unit = {},
)

internal fun DetailScreenHost.detailsSurfaceVisible(drawerMode: Boolean, itemResolved: Boolean): Boolean =
  itemResolved && detailsPaneOpen && !chatPaneOpen && !(drawerMode && specsOpen)

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
  val drawerMode = LocalShellLayout.current.drawerMode
  val paneShown = host.detailsSurfaceVisible(drawerMode, state.itemResolved)
  val recoverFocus = remember { FocusRequester() }
  val detailsFocus = remember { FocusRequester() }
  val shellInput = LocalShellInput.current
  val entryKey = LocalEntryKey.current
  val completeDetailFocusReturn = {
    detailsFocus.requestFocus()
    onAction(DetailAction.SubtaskListFocused)
  }
  val currentCompleteDetailFocusReturn = rememberUpdatedState(completeDetailFocusReturn)
  val closeDetails = {
    if (paneShown) {
      host.onToggleDetails()
      if (state.subtaskListTargetAvailable) {
        onAction(DetailAction.FocusSubtaskList)
      } else {
        completeDetailFocusReturn()
      }
    }
  }
  val toggleDetails = { if (paneShown) closeDetails() else host.onOpenDetails(drawerMode) }
  val currentToggleDetails = rememberUpdatedState(toggleDetails)
  val currentCloseDetails = rememberUpdatedState(closeDetails)
  val currentPaneShown = rememberUpdatedState(paneShown)
  val dispatchAction: (DetailAction) -> Unit = { action ->
    val detail = (state.sections as? SectionsUi.Loaded)?.detail
    when (action) {
      is DetailAction.SelectSubtask -> {
        if (detail != null && action.key in subtaskKeys(item, detail)) {
          onAction(action)
          host.onOpenDetails(drawerMode)
        }
      }
      is DetailAction.MoveSubtaskSelection -> {
        if (detail != null) {
          if (!paneShown) {
            selectedSubtask(item, detail, state.subtaskChoice.key)?.let { key ->
              onAction(DetailAction.SelectSubtask(key))
              host.onOpenDetails(drawerMode)
            }
          } else onAction(action)
        }
      }
      DetailAction.SubtaskListFocusUnavailable -> {
        completeDetailFocusReturn()
      }
      else -> onAction(action)
    }
  }
  LaunchedEffect(state.focusSubtaskList, state.subtaskListTargetAvailable) {
    if (state.focusSubtaskList && !state.subtaskListTargetAvailable) {
      currentCompleteDetailFocusReturn.value()
    }
  }
  androidx.compose.runtime.DisposableEffect(shellInput, entryKey) {
    val controls = dev.skillbill.desktop.core.ui.input.ShellInput.DetailControls(
      toggle = { currentToggleDetails.value() },
      close = { if (currentPaneShown.value) { currentCloseDetails.value(); true } else false },
    )
    shellInput.registerDetails(entryKey, controls)
    onDispose { shellInput.unregisterDetails(entryKey, controls) }
  }
  val listState = rememberLazyListState()
  val planScreenState = rememberPlanScreenState(item.id)
  val qaRow = isStandaloneQa(item)
  val validationRow = isStandaloneValidation(item)
  val loaded = state.sections as? SectionsUi.Loaded
  val qaView = if (qaRow && loaded != null) qaChecklistView(item, loaded.detail, state.selectedQaRun, state.qaCaseOverrides) else null
  val keys = remember { LazyKeyIndex() }
  val scrollToFinding = rememberQaFindingScroller(listState, collapse, keys)
  val validationResult = loaded?.detail?.selectedValidationResult
  val validationShape = if (validationRow) shapeValidationReport(validationResult) else null
  val validationChecksShown = loaded?.let { currentExecution(item, it.detail)?.let { run -> run.liveChecks && run.validationChecks.isNotEmpty() } } == true
  val validationPage = if (validationRow && loaded != null) rememberValidationPage(validationShape, item.control.invocationId, listState, collapse, keys, validationChecksShown) else null
  val scrollToVerifySection = rememberVerifySectionScroller(listState, collapse, keys, loaded?.detail?.verifyRun?.workflowId)
  val peekState = rememberSaveableStateHolder()
  Column(Modifier.fillMaxSize().focusRequester(detailsFocus).focusable()) {
    ViewHeader(
      crumbs = listOf(
        Crumb("Work", onClick = { onAction(DetailAction.Navigate(WorkRoute(null))) }),
        Crumb(sectionCrumb(item.liveSection), onClick = { onAction(DetailAction.Navigate(sectionRoute(item.liveSection))) }),
        Crumb(rowKey(item)),
      ),
      actions = {
        SbIconButton(
          LucideIcons.star,
          when {
            !state.favoriteAvailable -> "Favorite status unavailable"
            state.favorite -> "Remove favorite"
            else -> "Add favorite"
          },
          { onAction(DetailAction.ToggleFavorite) },
          modifier = Modifier.semantics { selected = state.favorite },
          selected = state.favorite,
          enabled = state.favoriteAvailable && !state.favoriteInFlight,
        )
        if (qaRow) {
          loaded?.let { loadedDetail ->
            qaRunPager(item, loadedDetail.detail, state.selectedQaRun)?.let { pager ->
              Pager(pager.index, pager.total, onPrevious = { onAction(qaRunPreviousAction(pager, loadedDetail.moreLists)) }, onNext = { onAction(qaRunNextAction(pager)) }, showShortcuts = false)
            }
          }
        } else state.boardPosition?.let { position ->
          Pager(position.index, position.total, onPrevious = { onAction(DetailAction.Previous) }, onNext = { onAction(DetailAction.Next) })
        }
        if (state.stale) StaleIndicator()
        // paneToggles(st), then the details toggle.
        SbIconButton(LucideIcons.refresh, "Refresh", { onAction(DetailAction.Retry) })
        qaView?.reportPath?.let { path -> SbIconButton(LucideIcons.file, "Open report", { onAction(DetailAction.OpenQaReport(path)) }) }
        SbIconButton(LucideIcons.fileText, if (host.specsOpen) "Close specs pane" else "Specs", host.onToggleSpecs, shortcut = "G S", selected = host.specsOpen)
        SbIconButton(LucideIcons.terminal, if (host.terminalOpen) "Close terminal" else if (qaRow) "Open terminal" else "Terminal", host.onToggleTerminal, shortcut = "⌃ `", selected = host.terminalOpen)
        SbIconButton(LucideIcons.bot, if (host.chatOpen) "Close chat" else "Chat", host.onToggleChat, shortcut = host.chatShortcut, selected = host.chatOpen)
        SbIconButton(LucideIcons.panelRight, "Toggle details", toggleDetails, shortcut = host.detailsShortcut, selected = host.detailsPaneOpen)
      },
    )
    Box(Modifier.weight(1f).fillMaxWidth()) {
      // `.sb-doc`: a 780 column (padding included) centred in the scroll area, 32/40/64 padding, 20/16/48 in a narrow window.
      // The list adds no spacing of its own: each block brings its 28 gap, so a section's rows can sit flush in one box.
      BoxWithConstraints(Modifier.fillMaxSize()) {
        val narrow = LocalShellLayout.current.drawerMode
        val inset = if (narrow) 16.dp else 40.dp
        val side = maxOf(inset, (maxWidth - (DOC_MAX_WIDTH - inset * 2)) / 2)
        val verifyRun = loaded?.detail?.verifyRun
        val fixBarVisible = verifyRun != null && verifySelectionAllowed(verifyRun) && state.fixSelection.isNotEmpty()
        val fixBarHeight = remember(verifyRun?.workflowId) { mutableStateOf(0.dp) }
        val density = LocalDensity.current
        LazyColumn(
          Modifier.fillMaxSize(),
          state = listState,
          contentPadding = PaddingValues(start = side, end = side, top = if (narrow) 20.dp else 32.dp, bottom = maxOf(if (narrow) 48.dp else 64.dp, if (fixBarVisible) fixBarHeight.value + 16.dp else 0.dp)),
        ) {
          val sections = DetailSections(this, collapse, listState, assessmentOrder, keys, planScreenState)
          sections.item(key = "header") {
            val verifyRun = loaded?.detail?.verifyRun
            if (verifyRun != null) VerifyHeader(verifyRun, onAction) {
              if (state.sections != SectionsUi.Gone) {
                VerifyHeaderActions(state, verifyRun, view, recoverFocus, onAction, host.onOpenParkedVerify)
              }
            } else DetailHeader(item, state.stale, { onAction(DetailAction.RevealFolder) }, { item.branch?.let { onAction(DetailAction.OpenBranch(it)) } }, qa = if (qaRow) QaHeaderFacts(qaView?.testedCommit, qaView?.criteriaSource) else null, validation = validationRow, planReady = item.isStandalonePlan() && planFinished(item, loaded?.detail), planBlocked = item.isStandalonePlan() && hasBlockedSubtasks(loaded?.detail?.standalonePlan)) {
              if (state.sections != SectionsUi.Gone) {
                val agentChoices = (state.sections as? SectionsUi.Loaded)?.runOptions?.agentChoices.orEmpty()
                RunActions(view, agentChoices, state.controls.agentMenuOpen, recoverFocus, dispatchAction)
                val report = validationResult
                if (validationRow && item.lifecycle != Lifecycle.Active && !report.isNullOrBlank()) {
                  SbButton("Copy report", { onAction(DetailAction.Copy(report)) })
                }
              }
            }
          }
          val build = item.build
          // HANDOFF 4.1: the build strip sits under the header, except for validation and verify runs.
          if (build != null && !validationRow && loaded?.detail?.verifyRun == null) sections.item(key = "build-strip") {
            Column(Modifier.padding(top = DocBlockGap)) { BuildStrip(remember(build) { build.toSbBuild() }) }
          }
          // HANDOFF 9.2 item 5: the pause notice is its own block under the header.
          if (view.pausePending) sections.item(key = "pause-notice") {
            Column(Modifier.padding(top = DocBlockGap)) { Hint("Pause recorded. The goal pauses at its next checkpoint.", FeedbackTone.Info, LucideIcons.pause) }
          }
          if (!validationRow && item.reason != null && item.lifecycle != Lifecycle.Terminal) sections.item(key = "reason") {
            Column(Modifier.padding(top = DocBlockGap)) { DetailReason(item) { onAction(DetailAction.Copy(it)) } }
          }
          sections.document(state, view, paneShown, dispatchAction, qaView, scrollToFinding, validationPage, scrollToVerifySection)
        }
        if (fixBarVisible) Box(Modifier.align(Alignment.BottomCenter).padding(horizontal = side, vertical = 16.dp).onSizeChanged { fixBarHeight.value = with(density) { it.height.toDp() } }) {
          VerifyFixBar(verifyFixItems(verifyRun, state.criterionMarks), state, true, onAction)
        }
      }
      DetailPeek(
        open = paneShown,
        wide = detailsPaneWide(item),
        requestedWidthDp = host.detailsWidthDp,
        onResize = host.onResizeDetails,
        onReset = host.onResetDetails,
        onDismiss = closeDetails,
      ) {
        peekState.SaveableStateProvider("${item.id}:${state.subtaskChoice.key}") {
          DetailsPane(state, assessmentOrder, closeDetails, dispatchAction)
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
internal fun DetailSections.incident(state: DetailUiState, view: RunActionsView, onAction: (DetailAction) -> Unit, planHeader: Boolean = false) {
  val detail = (state.sections as? SectionsUi.Loaded)?.detail
  val incident = detail?.incident
  val decision: @Composable () -> Unit = {
    view.decision?.let { OperatorDecisionNotice(it) { text -> onAction(DetailAction.Copy(text)) } }
  }
  val sectionCollapse = collapse
  if (incident != null) item(key = "incident") {
    IncidentSection(incident, view, decision, onAction, sectionCollapse, { onAction(DetailAction.Copy(it)) }, Modifier.padding(top = if (planHeader) 20.dp else DocBlockGap), planHeader)
  }
  if (incident == null && view.decision != null && state.sections != SectionsUi.Gone) {
    if (planHeader) planSection("decision", "Operator decision") { item(key = "decision-body") { decision() } }
    else item(key = "decision") { Section(title = "Operator decision", Modifier.padding(top = DocBlockGap)) { decision() } }
  }
}

/** Detail sections with Activity last; placeholders hold their place until the first read lands. */
internal fun DetailSections.document(state: DetailUiState, view: RunActionsView, paneShown: Boolean, onAction: (DetailAction) -> Unit, qaView: QaChecklistView?, onOpenFinding: (String) -> Unit, validationPage: ValidationPageState? = null, onScrollToVerifySection: (String) -> Unit = {}) {
  val item = state.header
  when (val sections = state.sections) {
    SectionsUi.Pending -> {
      placeholder("metrics", 72.dp)
      placeholder("subtasks", 160.dp)
      placeholder("activity", 96.dp)
    }
    is SectionsUi.Loaded -> {
      val detail = sections.detail
      if (validationPage != null) {
        validationDocument(state, view, detail, validationPage, onAction)
        return
      }
      if (qaView != null) {
        qaDocument(state, view, detail, qaView, onAction, onOpenFinding)
        return
      }
      if (item.isStandalonePlan()) {
        planDocument(item, detail, state, view, onAction)
        return
      }
      detail.verifyRun?.let { run ->
        verifyDocument(state, run, onAction, onScrollToVerifySection)
        return
      }
      // HANDOFF 9.2 order: the incident (item 7) comes before the metrics (item 8).
      incident(state, view, onAction)
      item(key = "metrics") {
        Column(Modifier.padding(top = DocBlockGap)) { DetailMetrics(item, detail) }
      }
      subtaskSection(item, detail, state, paneShown, onAction)
      pipelineSection(item, detail)
      if (item.showsChanges) changesSection(state.changes, onAction)
      briefingSection(item, detail, onAction)
      reviewActionsSection(item, detail, onAction)
      standaloneResultSection(item, detail)
      // Standalone phases and operations keep their dependencies, validation, phase results and spec bundle in the document.
      dependencySection(item, detail, state, onAction)
      validationSection(item, detail)
      phaseResultsSection(item, detail)
      if (item.kind != WorkKind.Goal) pageFooter(DetailList.Validation, state, onAction)
      specBundleSection(item, detail, onAction)
      activitySection(item, detail, state, onAction)
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

private fun DetailSections.qaDocument(
  state: DetailUiState,
  view: RunActionsView,
  detail: dev.skillbill.desktop.core.domain.model.Detail,
  qaView: QaChecklistView,
  onAction: (DetailAction) -> Unit,
  onOpenFinding: (String) -> Unit,
) {
  val item = state.header
  val run = qaDisplayedRun(item, detail, state.selectedQaRun)
  val findings = run?.let(::qaFindings).orEmpty()
  val live = run != null && qaRunLive(item, run)
  val results = run?.let { qaResultsView(standaloneQaInput(item, it)) }
  incident(state, view, onAction)
  if (results != null && !live) qaResultsSection(QA_RESULTS_SECTION, "QA results", results, onAction)
  qaStatusStrip(item, run, qaView)
  qaTargetsSection(qaView)
  qaTestPlanSection(qaView, { id -> if (findings.any { it.id == id }) onOpenFinding(id) }, onAction)
  changesSection(state.changes, onAction)
  qaActivitySection(item, detail, state, onAction)
  if (results != null && live && (findings.isNotEmpty() || results.report != null)) {
    qaResultsSection(QA_RESULTS_SECTION, "QA results", results, onAction)
  }
  briefingSection(item, detail, onAction)
  dependencySection(item, detail, state, onAction)
  validationSection(item, detail)
  phaseResultsSection(item, detail)
  pageFooter(DetailList.Validation, state, onAction)
  specBundleSection(item, detail, onAction)
}

private fun DetailSections.placeholder(key: String, height: Dp) {
  item(key = key) {
    SbBox(Modifier.padding(top = DocBlockGap).heightIn(min = height)) {
      BasicText("Loading", Modifier.padding(12.dp), style = SkillBillTheme.type.tSm.copy(color = SkillBillTheme.colors.fgFaint))
    }
  }
}
