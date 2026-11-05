package dev.skillbill.desktop.feature.specs

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.hoverable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsHoveredAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicText
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
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
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.PointerIcon
import androidx.compose.ui.input.pointer.pointerHoverIcon
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import dev.skillbill.desktop.core.designsystem.LocalContentColor
import dev.skillbill.desktop.core.designsystem.SkillBillRadius
import dev.skillbill.desktop.core.designsystem.SkillBillTheme
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
import dev.skillbill.desktop.core.designsystem.component.Segmented
import dev.skillbill.desktop.core.designsystem.component.SegmentedOption
import dev.skillbill.desktop.core.designsystem.component.sbFocusRing
import dev.skillbill.desktop.core.designsystem.detail.SidePane
import dev.skillbill.desktop.core.designsystem.icon.LucideIcons
import dev.skillbill.desktop.core.designsystem.icon.SbIcon
import dev.skillbill.desktop.core.designsystem.icon.SbIconSize
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
        ProjectPicker(project?.displayName, { onAction(SpecsAction.OpenProjectMenu) })
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
      Modifier.fillMaxWidth().rule(bottom = true, colors.borderSubtle).padding(start = 16.dp, end = 16.dp, top = 10.dp, bottom = 11.dp),
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
      Modifier.fillMaxWidth().onFocusChanged { shellInput.setFocusSource(ShellInput.SPECS_BAR_SOURCE, it.hasFocus) }.rule(bottom = false, colors.borderSubtle).padding(start = 16.dp, end = 12.dp, top = 10.dp, bottom = 10.dp),
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

/** `.sb-pick`: a 24 px bordered button with the repository icon, the project name and a 12 px faint chevron. It has no tooltip. */
@Composable
private fun ProjectPicker(name: String?, onClick: () -> Unit) {
  val colors = SkillBillTheme.colors
  val shape = RoundedCornerShape(SkillBillRadius.sm)
  val interaction = remember { MutableInteractionSource() }
  val hovered = interaction.collectIsHoveredAsState().value
  // `.sb-pick` has no transition, so hover switches at once.
  val fill = if (hovered) colors.bgHover else Color.Transparent
  val text = if (hovered) colors.fg else colors.fg2
  Row(
    Modifier.height(24.dp).background(fill, shape).border(1.dp, colors.border, shape)
      .hoverable(interaction)
      .clickable(interaction, indication = null, onClick = onClick)
      .pointerHoverIcon(PointerIcon.Hand)
      .sbFocusRing(interaction, shape)
      .semantics {
        role = Role.Button
        contentDescription = name ?: CHOOSE_PROJECT
      }
      // 8 px of CSS padding inside the 1 px border.
      .padding(horizontal = 9.dp),
    horizontalArrangement = Arrangement.spacedBy(6.dp),
    verticalAlignment = Alignment.CenterVertically,
  ) {
    name?.let { RepoIcon(it) }
    BasicText(name ?: CHOOSE_PROJECT, style = SkillBillTheme.type.tSmMedium.copy(color = text), maxLines = 1, overflow = TextOverflow.Ellipsis)
    CompositionLocalProvider(LocalContentColor provides colors.fgFaint) { SbIcon(LucideIcons.chevronDown, SbIconSize.Xs) }
  }
}

/** A 1 px hairline on the bottom or top edge, as the pane's section borders draw it. */
private fun Modifier.rule(bottom: Boolean, color: Color) = drawBehind {
  val stroke = 1.dp.toPx()
  val y = if (bottom) size.height - stroke / 2 else stroke / 2
  drawLine(color, Offset(0f, y), Offset(size.width, y), stroke)
}

/** "No specs yet" body with the plan command in mono, as screens.js renders it. */
@Composable
private fun noSpecsBody(): AnnotatedString {
  val command = "/skill-bill phase:plan"
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
