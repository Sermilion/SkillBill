package dev.skillbill.desktop.feature.specs

import androidx.compose.foundation.focusable
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.foundation.text.BasicText
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.text.font.FontWeight
import dev.skillbill.desktop.core.designsystem.SkillBillTheme
import dev.skillbill.desktop.core.designsystem.icon.LucideIcons
import dev.skillbill.desktop.core.designsystem.tabular
import dev.skillbill.desktop.core.designsystem.icon.SbIcon
import dev.skillbill.desktop.core.designsystem.icon.SbIconSize
import dev.skillbill.desktop.core.designsystem.list.TreeRow as DesignTreeRow
import dev.skillbill.desktop.core.designsystem.status.StatusIcon
import dev.skillbill.desktop.core.designsystem.status.StatusKind
import dev.skillbill.desktop.core.domain.model.SpecFileKind
import dev.skillbill.desktop.core.domain.model.SpecNode
import dev.skillbill.desktop.core.domain.model.SpecStatus
import dev.skillbill.desktop.core.ui.input.LocalShellInput
import dev.skillbill.desktop.core.ui.input.ShellInput
import kotlinx.coroutines.launch
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.size
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.input.key.isShiftPressed
import androidx.compose.ui.input.key.isCtrlPressed
import androidx.compose.ui.input.key.isMetaPressed
import androidx.compose.ui.input.key.isAltPressed
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.DpOffset
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import dev.skillbill.desktop.core.designsystem.SkillBillSize
import dev.skillbill.desktop.core.designsystem.component.SbMenu
import dev.skillbill.desktop.core.designsystem.component.SbMenuEntry
import dev.skillbill.desktop.core.designsystem.component.SbIconButton
import dev.skillbill.desktop.core.designsystem.component.SbPopover
import dev.skillbill.desktop.core.designsystem.component.SbPopoverPlacement
import dev.skillbill.desktop.core.ui.input.ShellLayer
import kotlin.math.roundToInt

internal fun shouldClearSpecsTreeFocus(focusedPath: String?, rowPath: String, hasFocus: Boolean): Boolean =
  !hasFocus && focusedPath == rowPath

@Composable
internal fun SpecsTree(state: SpecsUiState, onAction: (SpecsAction) -> Unit, onNavigate: ((Int) -> Boolean) -> Unit, onFocusSelection: (() -> Boolean) -> Unit, modifier: Modifier = Modifier) {
  val shellInput = LocalShellInput.current
  DisposableEffect(shellInput) {
    onDispose { shellInput.setFocusSource(ShellInput.SPECS_TREE_SOURCE, false) }
  }
  var focusedPath by remember { mutableStateOf<String?>(null) }
  var rowOwnsFocus by remember { mutableStateOf(false) }
  val listState = rememberLazyListState()
  val scope = rememberCoroutineScope()
  val requesters = remember { mutableMapOf<String, FocusRequester>() }
  // Where the bundle row was right-clicked, in row pixels; the row menu opens at that point, as WorkScreen's row menu does.
  var menuAt by remember { mutableStateOf(IntOffset.Zero) }
  val keyboardMenuAt = with(LocalDensity.current) { IntOffset(0, SkillBillSize.compactRow.roundToPx()) }

  val selectedVisible = state.rows.any { it.node.path == state.selectedPath }
  LaunchedEffect(state.selectedPath, selectedVisible) {
    val selected = state.selectedPath ?: return@LaunchedEffect
    val index = state.rows.indexOfFirst { it.node.path == selected }
    if (index < 0) return@LaunchedEffect
    val info = listState.layoutInfo
    val item = info.visibleItemsInfo.firstOrNull { it.key == selected }
    if (item == null) {
      listState.scrollToItem(index)
      return@LaunchedEffect
    }
    val end = item.offset + item.size
    if (item.offset < info.viewportStartOffset || end > info.viewportEndOffset) {
      listState.scrollToItem(index)
    }
  }

  fun focusRow(index: Int): Boolean {
    val path = state.rows.getOrNull(index)?.node?.path ?: return false
    onAction(SpecsAction.Select(path))
    scope.launch {
      val visible = listState.layoutInfo.visibleItemsInfo.any { it.key == path }
      if (!visible) listState.scrollToItem(index)
      requesters[path]?.requestFocus()
    }
    return true
  }

  val navigateBundles: (Int) -> Boolean = { delta ->
    val bundles = state.rows.withIndex().filter { it.value.node is SpecNode.Bundle }
    if (bundles.isEmpty()) false else {
      val selected = bundles.indexOfFirst {
        val path = state.selectedPath
        it.value.node.path == path || path?.startsWith("${it.value.node.path}/") == true
      }
      val next = if (selected < 0) {
        if (delta > 0) 0 else bundles.lastIndex
      } else (selected + delta).coerceIn(0, bundles.lastIndex)
      val rowIndex = bundles[next].index
      val path = bundles[next].value.node.path
      onAction(SpecsAction.Select(path))
      focusedPath = path
      rowOwnsFocus = true
      scope.launch {
        if (!listState.layoutInfo.visibleItemsInfo.any { it.key == path }) listState.scrollToItem(rowIndex)
        requesters[path]?.requestFocus()
      }
      true
    }
  }
  SideEffect { onNavigate(navigateBundles) }
  val focusSelection: () -> Boolean = {
    val path = state.selectedPath
    val index = state.rows.indexOfFirst { it.node.path == path }
    if (path == null || index < 0) false else {
      scope.launch {
        if (!listState.layoutInfo.visibleItemsInfo.any { it.key == path }) listState.scrollToItem(index)
        requesters[path]?.requestFocus()
      }
      true
    }
  }
  SideEffect { onFocusSelection(focusSelection) }

  LazyColumn(
    state = listState,
    modifier = modifier.selectableGroup().onPreviewKeyEvent { event ->
      if (event.type != KeyEventType.KeyDown) return@onPreviewKeyEvent false
      if (!rowOwnsFocus) return@onPreviewKeyEvent false
      val index = state.rows.indexOfFirst { it.node.path == focusedPath }
      if (index < 0) return@onPreviewKeyEvent false
      val row = state.rows[index]
      val modified = event.isCtrlPressed || event.isMetaPressed || event.isAltPressed || event.isShiftPressed
      if (modified && when (event.key) {
          Key.DirectionDown, Key.DirectionUp, Key.DirectionRight, Key.DirectionLeft,
          Key.Enter, Key.NumPadEnter, Key.Spacebar -> true
          else -> false
        }
      ) return@onPreviewKeyEvent false
      when (event.key) {
        Key.DirectionDown -> focusRow(index + 1)
        Key.DirectionUp -> focusRow(index - 1)
        Key.DirectionRight -> when {
          !row.hasChildren -> false
          row.expanded -> focusRow(index + 1)
          else -> { onAction(SpecsAction.Expand(row.node.path)); true }
        }
        Key.DirectionLeft -> when {
          row.hasChildren && row.expanded -> { onAction(SpecsAction.Collapse(row.node.path)); true }
          else -> focusRow(state.rows.indexOfFirst { it.node.path == row.node.path.substringBeforeLast('/', "") })
        }
        Key.Enter, Key.NumPadEnter -> { onAction(SpecsAction.Activate(row.node.path)); true }
        Key.Spacebar -> { onAction(SpecsAction.Select(row.node.path)); true }
        Key.Menu, Key.F10 -> if ((event.key == Key.Menu || event.isShiftPressed) && (row.node is SpecNode.Bundle || row.node is SpecNode.File)) {
          menuAt = keyboardMenuAt
          onAction(SpecsAction.OpenRowMenu(row.node.path))
          true
        } else false
        else -> false
      }
    },
  ) {
    items(state.rows, key = { it.node.path }) { row ->
      val requester = remember { FocusRequester() }
      DisposableEffect(row.node.path, requester) {
        requesters[row.node.path] = requester
        onDispose { requesters.remove(row.node.path) }
      }
      Box {
        DesignTreeRow(
          title = row.node.treeTitle(),
          onClick = { focusedPath = row.node.path; onAction(SpecsAction.Click(row.node.path)); requester.requestFocus() },
          level = row.depth,
          expandable = row.hasChildren,
          expanded = row.expanded,
          focused = row.node.path == state.selectedPath,
          file = row.node is SpecNode.File,
          muted = (row.node as? SpecNode.Bundle)?.status == SpecStatus.Canceled,
          lead = { SpecLead(row.node) },
          id = row.node.treeId(),
          meta = { SpecMeta(row.node) },
          chevron = if (row.node is SpecNode.Bundle && row.hasChildren) ({
            SbIconButton(if (row.expanded) LucideIcons.chevronDown else LucideIcons.chevronRight, if (row.expanded) "Collapse ${row.node.title}" else "Expand ${row.node.title}", { onAction(SpecsAction.Toggle(row.node.path)) }, size = dev.skillbill.desktop.core.designsystem.component.SbControlSize.Small)
          }) else null,
          rowAction = if (row.node is SpecNode.Bundle) ({
            SbIconButton(LucideIcons.more, "${row.node.issueKey ?: row.node.title} actions", {
              menuAt = keyboardMenuAt
              onAction(SpecsAction.OpenRowMenu(row.node.path))
            }, size = dev.skillbill.desktop.core.designsystem.component.SbControlSize.Small)
          }) else null,
          modifier = Modifier.fillMaxWidth().focusRequester(requester).onFocusChanged {
            if (it.hasFocus) {
              shellInput.setFocusSource(ShellInput.SPECS_TREE_SOURCE, true)
              rowOwnsFocus = it.isFocused
              if (it.isFocused) focusedPath = row.node.path
            } else if (shouldClearSpecsTreeFocus(focusedPath, row.node.path, it.hasFocus)) {
              focusedPath = null
              rowOwnsFocus = false
              shellInput.setFocusSource(ShellInput.SPECS_TREE_SOURCE, false)
            }
          }.focusable(),
          onContextMenu = if (row.node is SpecNode.Bundle || row.node is SpecNode.File) { at: Offset ->
            menuAt = IntOffset(at.x.roundToInt(), at.y.roundToInt())
            onAction(SpecsAction.OpenRowMenu(row.node.path))
          } else null,
        )
        val targetBundle = when (val node = row.node) {
          is SpecNode.Bundle -> node
          is SpecNode.File -> bundleFor((state.tree as? dev.skillbill.desktop.core.domain.model.SpecTreeState.Loaded)?.tree?.roots.orEmpty(), node.path)
          is SpecNode.Folder -> null
        }
        if (state.rowMenuAnchorPath == row.node.path && targetBundle != null && targetBundle.path == state.rowMenuPath) {
          Box(Modifier.offset { menuAt }.size(0.dp)) { SpecActionsMenu(targetBundle, state, onAction) }
        }
      }
    }
  }
}

/** Shared bundle actions for row context menus and the selection bar. */
@Composable
internal fun SpecActionsMenu(bundle: SpecNode.Bundle, state: SpecsUiState, onAction: (SpecsAction) -> Unit, placement: SbPopoverPlacement = SbPopoverPlacement.Auto, dismissAction: SpecsAction = SpecsAction.DismissRowMenu) {
  val close = { onAction(dismissAction) }
  val actions = specMenuActions(bundle, state)
  val actionItems = actions.filterIsInstance<SpecMenuAction.Item>()
  val shortcuts = actionItems.filter { it.enabled }.mapNotNull { entry ->
    val key = when (entry.shortcut) {
      "R" -> Key.R
      "P" -> Key.P
      "A" -> Key.A
      "F" -> Key.F
      else -> null
    } ?: return@mapNotNull null
    key to { onAction(entry.action); close() }
  }.toMap()
  val entries = actions.map { entry ->
    when (entry) {
      SpecMenuAction.Separator -> SbMenuEntry.Separator
      is SpecMenuAction.Item -> SbMenuEntry.Item(
        entry.label,
        { onAction(entry.action); close() },
        icon = entry.icon,
        shortcut = entry.shortcut,
        enabled = entry.enabled,
        danger = entry.danger,
        reason = entry.reason,
      )
    }
  }
  ShellLayer(close)
  // The anchor is the click point; -4 cancels the popover's anchor gap so the menu's corner sits on the pointer.
  SbPopover(expanded = true, onDismissRequest = close, offset = DpOffset(0.dp, (-4).dp), placement = placement, specMenuKeyboard = true, keyboardShortcuts = shortcuts) { SbMenu(entries) }
}

// screens.js specTree: status icons take the default half fill; the n/m text carries the progress.
@Composable
private fun SpecLead(node: SpecNode) {
  when (node) {
    is SpecNode.Folder -> SbIcon(LucideIcons.folder, SbIconSize.Sm)
    is SpecNode.Bundle -> StatusIcon(node.status.toStatusKind(), null)
    is SpecNode.File -> when {
      node.kind == SpecFileKind.SubtaskSpec && node.status != null -> StatusIcon(requireNotNull(node.status).toStatusKind(), null)
      node.kind == SpecFileKind.ParentSpec -> SbIcon(LucideIcons.fileText, SbIconSize.Sm)
      else -> SbIcon(LucideIcons.file, SbIconSize.Sm)
    }
  }
}

/** The folder's bundle count and the bundle's `done/total`, in the row's 11.5 px tabular faint text. Canceled bundles show none. */
@Composable
private fun SpecMeta(node: SpecNode) {
  val style = SkillBillTheme.type.tXs.copy(color = SkillBillTheme.colors.fgFaint, fontWeight = FontWeight.Normal).tabular()
  when (node) {
    is SpecNode.Folder -> BasicText(node.bundleCount.toString(), style = style, maxLines = 1)
    is SpecNode.Bundle -> if (node.status != SpecStatus.Canceled) {
      node.subtasksDone?.let { done -> node.subtasksTotal?.let { total -> BasicText("$done/$total", style = style, maxLines = 1) } }
    }
    is SpecNode.File -> Unit
  }
}

private fun SpecNode.treeTitle(): String = when (this) {
  is SpecNode.Bundle -> title
  is SpecNode.File -> subtaskName?.takeIf { kind == SpecFileKind.SubtaskSpec && it.isNotBlank() } ?: name
  is SpecNode.Folder -> name
}

/** The bundle's issue key, or a subtask spec's two-digit number, shown as the id tag before the title. */
private fun SpecNode.treeId(): String? = when (this) {
  is SpecNode.Bundle -> issueKey
  is SpecNode.File -> subtaskId?.toString()?.padStart(2, '0')
  is SpecNode.Folder -> null
}

private fun SpecStatus.toStatusKind() = when (this) {
  SpecStatus.Complete -> StatusKind.Completed
  SpecStatus.InProgress, SpecStatus.Running -> StatusKind.Active
  SpecStatus.Pausing, SpecStatus.Paused -> StatusKind.Paused
  SpecStatus.Pending -> StatusKind.Pending
  SpecStatus.Blocked -> StatusKind.Blocked
  SpecStatus.Canceled -> StatusKind.Canceled
}
