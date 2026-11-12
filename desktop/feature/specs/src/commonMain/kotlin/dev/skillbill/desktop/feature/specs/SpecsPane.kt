package dev.skillbill.desktop.feature.specs

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.text.BasicText
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.isAltPressed
import androidx.compose.ui.input.key.isCtrlPressed
import androidx.compose.ui.input.key.isMetaPressed
import androidx.compose.ui.input.key.isShiftPressed
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.input.key.utf16CodePoint
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import dev.skillbill.desktop.core.designsystem.LocalContentColor
import dev.skillbill.desktop.core.designsystem.SkillBillTheme
import dev.skillbill.desktop.core.designsystem.composite.Edge
import dev.skillbill.desktop.core.designsystem.composite.hairline
import dev.skillbill.desktop.core.designsystem.component.EmptyState
import dev.skillbill.desktop.core.designsystem.component.RepoIcon
import dev.skillbill.desktop.core.designsystem.component.SbButton
import dev.skillbill.desktop.core.designsystem.component.SbDialog
import dev.skillbill.desktop.core.designsystem.component.SbDialogText
import dev.skillbill.desktop.core.designsystem.component.SbButtonVariant
import dev.skillbill.desktop.core.designsystem.component.SbControlSize
import dev.skillbill.desktop.core.designsystem.component.SbIconButton
import dev.skillbill.desktop.core.designsystem.component.SbInput
import dev.skillbill.desktop.core.designsystem.component.SbMenu
import dev.skillbill.desktop.core.designsystem.component.SbMenuEntry
import dev.skillbill.desktop.core.designsystem.component.SbPopover
import dev.skillbill.desktop.core.designsystem.component.SbPickerButton
import dev.skillbill.desktop.core.designsystem.component.Segmented
import dev.skillbill.desktop.core.designsystem.component.SegmentedOption
import dev.skillbill.desktop.core.designsystem.detail.SidePane
import dev.skillbill.desktop.core.designsystem.icon.LucideIcons
import dev.skillbill.desktop.core.domain.model.SpecTreeState
import dev.skillbill.desktop.core.ui.feedback.readProblemText
import dev.skillbill.desktop.core.ui.input.ShellLayer
import dev.skillbill.desktop.core.ui.input.LocalShellInput
import dev.skillbill.desktop.core.ui.input.ShellEdit
import dev.skillbill.desktop.core.ui.input.ShellInput
import dev.skillbill.desktop.core.ui.input.gatesShortcutsWhileFocused

/** Specs pane with project actions, count filters, a weighted tree, and the selected bundle bar. */
@Composable
internal fun SpecsPane(state: SpecsUiState, onAction: (SpecsAction) -> Unit, onClose: () -> Unit) {
  val colors = SkillBillTheme.colors
  val shellInput = LocalShellInput.current
  DisposableEffect(shellInput) { onDispose { shellInput.setFocusSource(ShellInput.SPECS_BAR_SOURCE, false) } }
  val filterFocusRequester = remember { FocusRequester() }
  var navigateTree by remember { mutableStateOf<(Int) -> Boolean>({ false }) }
  var focusTreeSelection by remember { mutableStateOf<() -> Boolean>({ false }) }
  var previousSearchOpen by remember { mutableStateOf(state.searchOpen) }
  LaunchedEffect(state.searchOpen) {
    if (state.searchOpen) filterFocusRequester.requestFocus()
    else if (previousSearchOpen) focusTreeSelection()
    previousSearchOpen = state.searchOpen
  }
  if (state.searchOpen) ShellEdit(onCancel = { onAction(SpecsAction.CloseSearch) }, onSave = {})
  val project = state.project
  SidePane(
    modifier = Modifier.onPreviewKeyEvent { event ->
    if (event.type != KeyEventType.KeyDown || shellInput.layerOpen || shellInput.editOpen) return@onPreviewKeyEvent false
    val treeOrBarFocused = shellInput.specsTreeFocused || shellInput.specsBarFocused
    if (!treeOrBarFocused || event.isCtrlPressed || event.isMetaPressed || event.isAltPressed) return@onPreviewKeyEvent false
    val char = event.utf16CodePoint.takeIf { it >= ' '.code }?.toChar()?.lowercaseChar()
    if (char == '/') {
      onAction(SpecsAction.OpenSearch)
      true
    } else if (event.isShiftPressed) false else when (char) {
      'j' -> navigateTree(1)
      'k' -> navigateTree(-1)
      'r' -> state.selectedPath != null && run { onAction(SpecsAction.Start); true }
      'p' -> state.selectedPath != null && run { onAction(SpecsAction.Pause); true }
      'a' -> state.selectedPath != null && run { onAction(SpecsAction.OpenAgentMenu); true }
      'f' -> state.selectedPath != null && run { onAction(SpecsAction.ToggleFavorite); true }
      else -> false
    }
    },
    title = "Specs",
    head = {
      Box {
        val dismiss = { onAction(SpecsAction.DismissProjectMenu) }
        SbPickerButton(
          { onAction(SpecsAction.OpenProjectMenu) },
          Modifier.semantics { contentDescription = project?.displayName ?: CHOOSE_PROJECT },
          open = state.projectMenuOpen,
        ) {
          project?.displayName?.let { RepoIcon(it) }
          BasicText(
            project?.displayName ?: CHOOSE_PROJECT,
            Modifier.weight(1f, fill = false),
            style = SkillBillTheme.type.tSmMedium.copy(color = LocalContentColor.current),
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
          )
        }
        if (state.projectMenuOpen) {
          ShellLayer(dismiss)
          SbPopover(expanded = true, onDismissRequest = dismiss) {
            SbMenu(
              listOf(SbMenuEntry.Head(CHOOSE_PROJECT)) + state.projects.map { item ->
                SbMenuEntry.Item(
                  item.displayName,
                  { onAction(SpecsAction.PickProject(item.projectId)) },
                  lead = { RepoIcon(item.displayName) },
                  checked = item.projectId == project?.projectId,
                )
              },
            )
          }
        }
      }
    },
    actions = {
      Box {
        SbIconButton(LucideIcons.more, "Specs actions", { onAction(SpecsAction.OpenPanelMenu) }, size = SbControlSize.Small)
        if (state.panelMenuOpen) {
          val dismiss = { onAction(SpecsAction.DismissPanelMenu) }
          ShellLayer(dismiss)
          SbPopover(expanded = true, onDismissRequest = dismiss) {
            SbMenu(listOf(
              SbMenuEntry.Item(COLLAPSE_ALL, { onAction(SpecsAction.CollapseAll); dismiss() }, icon = LucideIcons.chevronUp),
              SbMenuEntry.Item("Reveal specs folder", { onAction(SpecsAction.Reveal); dismiss() }, icon = LucideIcons.folder, detail = state.storePath),
              SbMenuEntry.Item("Import work…", { onAction(SpecsAction.RequestImport); dismiss() }, icon = LucideIcons.file, enabled = !project?.repoRoot.isNullOrBlank(), reason = if (project?.repoRoot.isNullOrBlank()) NO_CHECKOUT else null),
            ))
          }
        }
      }
      SbIconButton(LucideIcons.x, CLOSE_SPECS, onClose, size = SbControlSize.Small)
    },
  ) {
    Column(
      Modifier.fillMaxWidth().hairline(Edge.Bottom, colors.borderSubtle).padding(start = 16.dp, end = 16.dp, top = 10.dp, bottom = 11.dp),
      verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
      Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(4.dp)) {
        if (state.searchOpen || state.filterText.isNotEmpty()) {
          SbInput(state.filterText, { onAction(SpecsAction.FilterChanged(it)) }, Modifier.weight(1f).focusRequester(filterFocusRequester).gatesShortcutsWhileFocused(), placeholder = FILTER_HINT, leadingIcon = LucideIcons.search)
          SbIconButton(LucideIcons.x, "Clear filters", { onAction(SpecsAction.ClearFilters) }, size = SbControlSize.Small)
        } else {
          Spacer(Modifier.weight(1f))
          SbIconButton(LucideIcons.search, "Search specs", { onAction(SpecsAction.OpenSearch) }, size = SbControlSize.Small)
        }
      }
      val counts = ((state.tree as? SpecTreeState.Loaded)?.tree?.counts)
      // While the tree loads the counts are unknown, and a 0 would read as an empty project.
      val filterLabel = { name: String, count: Int? -> if (state.tree == SpecTreeState.Loading) name else "$name ${count ?: 0}" }
      Segmented(
        options = listOf(
          SegmentedOption(SpecStatusFilter.ALL, filterLabel("All", counts?.all)),
          SegmentedOption(SpecStatusFilter.ACTIVE, filterLabel("Active", counts?.active)),
          SegmentedOption(SpecStatusFilter.PENDING, filterLabel("Pending", counts?.pending)),
          SegmentedOption(SpecStatusFilter.DONE, filterLabel("Done", counts?.done)),
        ),
        selected = state.statusFilter,
        onSelect = { onAction(SpecsAction.ChooseStatus(it)) },
        // `.sb-seg` stretches across the `.sb-filters` column with its options packed at the start.
        modifier = Modifier.fillMaxWidth(),
      )
    }
    Box(Modifier.fillMaxWidth().weight(1f)) {
      when (val tree = state.tree) {
        // `.sb-scroll` is a block box, so `.sb-empty` sits at the top (48 down from its own padding), not centered.
        is SpecTreeState.Unavailable -> TopEmpty {
          EmptyState(SPECS_UNAVAILABLE, icon = LucideIcons.alert, body = readProblemText(tree.problem), actions = {
            SbButton("Retry", { onAction(SpecsAction.Retry) })
          })
        }
        SpecTreeState.NoCheckout -> TopEmpty { EmptyState(NO_CHECKOUT, icon = LucideIcons.folder) }
        is SpecTreeState.NoSpecs -> TopEmpty { EmptyState(NO_SPECS, icon = LucideIcons.fileText, body = noSpecsBody()) }
        SpecTreeState.Loading -> SpecsLoadingPlaceholder()
        is SpecTreeState.Loaded -> if (state.rows.isEmpty() && state.filtersActive) {
          TopEmpty {
            EmptyState(NO_MATCH, icon = LucideIcons.search, body = NO_MATCH_BODY, actions = {
              SbButton("Clear filters", { onAction(SpecsAction.ClearFilters) })
            })
          }
        } else SpecsTree(state, onAction, { navigateTree = it }, { focusTreeSelection = it }, Modifier.fillMaxWidth())
      }
      state.deleteTarget?.let { DeleteSpecFilesDialog(it, onAction) }
    }
    if (state.toolbar.selection is ToolbarSelection.Bundle) SpecSelectionBar(
      state, onAction,
      Modifier.fillMaxWidth().onFocusChanged { shellInput.setFocusSource(ShellInput.SPECS_BAR_SOURCE, it.hasFocus) }.hairline(Edge.Top, colors.borderSubtle).padding(start = 16.dp, end = 12.dp, top = 10.dp, bottom = 10.dp),
    )
    state.importDialog?.let { target ->
      ShellLayer { onAction(SpecsAction.DismissImport) }
      SbDialog(
        title = "Import ${target.issueKey}?",
        onDismissRequest = { onAction(SpecsAction.DismissImport) },
        actions = {
          SbButton("Cancel", { onAction(SpecsAction.DismissImport) }, variant = SbButtonVariant.Ghost)
          SbButton("Import work", { onAction(SpecsAction.ConfirmImport) })
        },
      ) {
        SbDialogText("Issue key: ${target.issueKey}")
        SbDialogText("Branch: ${target.featureBranch}")
        SbDialogText("Commit: ${target.commitSha}")
      }
    }
  }
}

/** "No specs yet" body with the plan command in mono, as screens.js renders it. */
@Composable
private fun noSpecsBody(): AnnotatedString {
  val command = "/sona phase:plan"
  val start = NO_SPECS_BODY.indexOf(command)
  return buildAnnotatedString {
    append(NO_SPECS_BODY)
    if (start >= 0) addStyle(SpanStyle(fontFamily = SkillBillTheme.type.mono.fontFamily), start, start + command.length)
  }
}

/** A scroll column with unbounded height, so [EmptyState]'s fill takes its content height and stays at the top. */
@Composable
private fun TopEmpty(content: @Composable () -> Unit) {
  Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState())) { content() }
}
