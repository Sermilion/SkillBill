package dev.skillbill.desktop.feature.changes

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.unit.dp
import dev.skillbill.desktop.core.designsystem.SkillBillTheme
import dev.skillbill.desktop.core.designsystem.component.Hint
import dev.skillbill.desktop.core.domain.model.ChangeScope
import dev.skillbill.desktop.core.ui.changes.SubtaskStrip
import dev.skillbill.desktop.core.ui.feedback.readProblemText

@Composable
fun ChangesScreen(viewModel: ChangesWindowViewModel, keys: ChangesWindowKeys) {
  val state by viewModel.state.collectAsState()
  val onAction = viewModel::onAction
  val filterFocus = remember { FocusRequester() }
  val pickerSearch = remember { FocusRequester() }
  var filterFocused by remember { mutableStateOf(false) }
  var filterFocusRequests by remember { mutableIntStateOf(0) }
  // The request waits a frame so the `/` typed after its key-down does not land in the filter.
  LaunchedEffect(filterFocusRequests) {
    if (filterFocusRequests == 0) return@LaunchedEffect
    withFrameNanos { }
    filterFocus.requestFocus()
  }
  val focusManager = LocalFocusManager.current
  val textFocused by rememberUpdatedState(filterFocused)
  DisposableEffect(keys, viewModel) {
    keys.handler = handler@{ event ->
      val key = windowKeyFor(event) ?: return@handler false
      val open = viewModel.state.value.pickerOpen
      // The picker popup shares the window scene, so this handler sees its keys first.
      // While it is open its search owns typing.
      val inText = textFocused || open
      if (inText || key == WindowKey.Escape) {
        if (key != WindowKey.Escape) return@handler false
        if (open) viewModel.onAction(ChangesWindowAction.ClosePicker)
        focusManager.clearFocus()
        return@handler inText
      }
      when (key) {
        WindowKey.OpenPicker -> viewModel.onAction(ChangesWindowAction.OpenPicker)
        WindowKey.Previous -> viewModel.onAction(ChangesWindowAction.Step(forward = false))
        WindowKey.Next -> viewModel.onAction(ChangesWindowAction.Step(forward = true))
        WindowKey.All -> viewModel.onAction(ChangesWindowAction.PickAll)
        WindowKey.NextFile -> viewModel.onAction(ChangesWindowAction.MoveFile(1))
        WindowKey.PreviousFile -> viewModel.onAction(ChangesWindowAction.MoveFile(-1))
        WindowKey.FocusFilter -> filterFocusRequests++
        WindowKey.Open -> viewModel.onAction(ChangesWindowAction.OpenSelectedFile)
        WindowKey.Escape -> Unit
      }
      true
    }
    onDispose { keys.handler = { false } }
  }
  Column(Modifier.fillMaxSize()) {
    when (val content = state.content) {
      WindowContent.Loading -> CenterHint("Loading changes")
      WindowContent.Missing -> CenterHint("This execution is not in the open database.")
      WindowContent.NoBaseline -> CenterHint("No change baseline for this execution.")
      is WindowContent.Problem -> CenterHint(readProblemText(content.problem))
      is WindowContent.Ready -> {
        ScopeToolbar(state, onAction, pickerSearch)
        if (state.subtasksAvailable) {
          val range = (state.scope as? ChangeScope.Subtasks)?.let { it.from..it.to }
          SubtaskStrip(
            state.subtasks,
            state.stats,
            selected = range,
            onPick = { id, extend -> onAction(ChangesWindowAction.PickSubtask(id, extend, fromPicker = false)) },
            modifier = Modifier.padding(horizontal = 12.dp),
            onDrag = { from, to -> onAction(ChangesWindowAction.DragSubtasks(from, to)) },
          )
        }
        ScopeBar(state, onAction)
        Row(Modifier.fillMaxSize().padding(top = 4.dp)) {
          FileListPane(state, onAction, filterFocus, { filterFocused = it }, Modifier.width(340.dp).fillMaxHeight())
          Box(Modifier.width(1.dp).fillMaxHeight().background(SkillBillTheme.colors.border))
          DiffPane(state, onAction, Modifier.weight(1f).fillMaxHeight())
        }
      }
    }
  }
}

@Composable
private fun CenterHint(text: String) = Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) { Hint(text) }
