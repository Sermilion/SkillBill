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
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import dev.skillbill.desktop.core.designsystem.SkillBillTheme
import dev.skillbill.desktop.core.designsystem.icon.LucideIcons
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

@Composable
internal fun SpecsTree(state: SpecsUiState, onAction: (SpecsAction) -> Unit, modifier: Modifier = Modifier) {
  val shellInput = LocalShellInput.current
  DisposableEffect(shellInput) {
    onDispose { shellInput.setFocusSource(ShellInput.SPECS_TREE_SOURCE, false) }
  }
  var focusedPath by remember { mutableStateOf<String?>(null) }
  val listState = rememberLazyListState()
  val scope = rememberCoroutineScope()
  val requesters = remember { mutableMapOf<String, FocusRequester>() }

  fun focusRow(index: Int): Boolean {
    val path = state.rows.getOrNull(index)?.node?.path ?: return false
    scope.launch {
      val visible = listState.layoutInfo.visibleItemsInfo.any { it.key == path }
      if (!visible) listState.scrollToItem(index)
      requesters[path]?.requestFocus()
    }
    return true
  }

  LazyColumn(
    state = listState,
    modifier = modifier.selectableGroup().onPreviewKeyEvent { event ->
      if (event.type != KeyEventType.KeyDown) return@onPreviewKeyEvent false
      val index = state.rows.indexOfFirst { it.node.path == focusedPath }
      if (index < 0) return@onPreviewKeyEvent false
      val row = state.rows[index]
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
      DesignTreeRow(
        title = (row.node as? SpecNode.Bundle)?.title ?: row.node.name,
        onClick = { focusedPath = row.node.path; onAction(SpecsAction.Click(row.node.path)) },
        level = row.depth,
        expandable = row.hasChildren,
        expanded = row.expanded,
        focused = row.node.path == state.selectedPath,
        file = row.node is SpecNode.File,
        lead = { SpecLead(row.node) },
        id = (row.node as? SpecNode.Bundle)?.issueKey,
        meta = { SpecMeta(row.node) },
        modifier = Modifier.fillMaxWidth().focusRequester(requester).onFocusChanged {
          shellInput.setFocusSource(ShellInput.SPECS_TREE_SOURCE, it.hasFocus)
          if (it.isFocused) focusedPath = row.node.path
        }.focusable(),
      )
    }
  }
}

@Composable
private fun SpecLead(node: SpecNode) {
  when (node) {
    is SpecNode.Folder -> SbIcon(LucideIcons.folder, SbIconSize.Sm)
    is SpecNode.Bundle -> StatusIcon(node.status.toStatusKind(), progress(node.subtasksDone, node.subtasksTotal))
    is SpecNode.File -> if (node.kind == SpecFileKind.SubtaskSpec && node.status != null) {
      StatusIcon(requireNotNull(node.status).toStatusKind(), null)
    } else SbIcon(LucideIcons.file, SbIconSize.Sm)
  }
}

@Composable
private fun SpecMeta(node: SpecNode) {
  when (node) {
    is SpecNode.Folder -> BasicText(node.bundleCount.toString(), style = SkillBillTheme.type.monoSm.copy(color = SkillBillTheme.colors.fgFaint))
    is SpecNode.Bundle -> {
      node.subtasksDone?.let { done -> node.subtasksTotal?.let { total ->
        BasicText("$done/$total", style = SkillBillTheme.type.monoSm.copy(color = SkillBillTheme.colors.fgFaint))
      } }
    }
    is SpecNode.File -> node.subtaskId?.let { id ->
      BasicText(id.toString().padStart(2, '0'), style = SkillBillTheme.type.monoSm.copy(color = SkillBillTheme.colors.fgFaint), maxLines = 1, overflow = TextOverflow.Clip)
    }
  }
}

private fun SpecStatus.toStatusKind() = when (this) {
  SpecStatus.Complete -> StatusKind.Completed
  SpecStatus.InProgress, SpecStatus.Running -> StatusKind.Active
  SpecStatus.Pausing, SpecStatus.Paused -> StatusKind.Paused
  SpecStatus.Pending -> StatusKind.Pending
  SpecStatus.Blocked -> StatusKind.Blocked
  SpecStatus.Canceled -> StatusKind.Canceled
}

private fun progress(done: Int?, total: Int?): Float? = if (done != null && total != null && total > 0) done.toFloat() / total else null
