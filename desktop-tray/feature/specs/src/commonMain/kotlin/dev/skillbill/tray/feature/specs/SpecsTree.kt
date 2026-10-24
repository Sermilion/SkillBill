package dev.skillbill.tray.feature.specs

import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEvent
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import dev.skillbill.tray.core.domain.model.SpecNode
import kotlinx.coroutines.launch

/** The visible rows as a lazy list keyed by node path, with keyboard navigation over the rows. */
@Composable
internal fun SpecsTree(state: SpecsUiState, onAction: (SpecsAction) -> Unit, modifier: Modifier = Modifier) {
  val listState = rememberLazyListState()
  val scope = rememberCoroutineScope()
  val requesters = remember { mutableMapOf<String, FocusRequester>() }
  var focusedPath by remember { mutableStateOf<String?>(null) }
  val rows = state.rows

  fun focusRow(index: Int): Boolean {
    val path = rows.getOrNull(index)?.node?.path ?: return false
    scope.launch {
      fun visible() = listState.layoutInfo.visibleItemsInfo.any { it.key == path }
      if (!visible()) listState.scrollToItem(index)
      if (visible()) requesters[path]?.requestFocus()
    }
    return true
  }

  fun onKey(event: KeyEvent): Boolean {
    val path = focusedPath ?: return false
    val index = rows.indexOfFirst { it.node.path == path }
    if (event.type != KeyEventType.KeyDown || index < 0) return false
    val row = rows[index]
    return when (event.key) {
      Key.DirectionDown -> focusRow(index + 1)
      Key.DirectionUp -> focusRow(index - 1)
      Key.DirectionRight -> when {
        !row.hasChildren -> false
        row.expanded -> focusRow(index + 1)
        else -> { onAction(SpecsAction.Expand(path)); true }
      }
      Key.DirectionLeft -> when {
        row.hasChildren && row.expanded -> { onAction(SpecsAction.Collapse(path)); true }
        else -> focusRow(rows.indexOfFirst { it.node.path == path.substringBeforeLast('/', "") })
      }
      Key.Enter, Key.NumPadEnter -> { onAction(SpecsAction.Activate(path)); true }
      Key.Spacebar -> { onAction(SpecsAction.Select(path)); true }
      else -> false
    }
  }

  LazyColumn(state = listState, modifier = modifier.selectableGroup().onPreviewKeyEvent(::onKey)) {
    items(rows, key = { it.node.path }) { row ->
      val path = row.node.path
      val requester = remember { FocusRequester() }
      DisposableEffect(path, requester) {
        requesters[path] = requester
        onDispose { requesters.remove(path) }
      }
      SpecRowItem(
        row = row,
        selected = path == state.selectedPath,
        openError = (row.node as? SpecNode.File)?.let { state.openErrors[it.absolutePath] },
        onClick = {
          requester.requestFocus()
          onAction(SpecsAction.Click(path))
        },
        onToggle = { onAction(SpecsAction.Toggle(path)) },
        modifier = Modifier.focusRequester(requester).onFocusChanged { if (it.isFocused) focusedPath = path },
      )
    }
  }
}
