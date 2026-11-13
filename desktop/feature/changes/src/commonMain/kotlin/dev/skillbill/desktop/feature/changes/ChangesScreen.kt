package dev.skillbill.desktop.feature.changes

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicText
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
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.semantics.paneTitle
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import dev.skillbill.desktop.core.designsystem.SkillBillTheme
import dev.skillbill.desktop.core.designsystem.composite.Edge
import dev.skillbill.desktop.core.designsystem.composite.hairline
import dev.skillbill.desktop.core.designsystem.component.Hint
import dev.skillbill.desktop.core.designsystem.component.SbControlSize
import dev.skillbill.desktop.core.designsystem.component.SbIconButton
import dev.skillbill.desktop.core.designsystem.icon.LucideIcons
import dev.skillbill.desktop.core.designsystem.ShadowBackdrop
import dev.skillbill.desktop.core.domain.model.ChangeScope
import dev.skillbill.desktop.core.ui.changes.SubtaskStrip
import dev.skillbill.desktop.core.ui.changes.CommitStrip
import dev.skillbill.desktop.core.ui.changes.changesSans
import dev.skillbill.desktop.core.ui.diff.DiffWordHighlight
import dev.skillbill.desktop.core.ui.feedback.readProblemText

@Composable
fun ChangesScreen(viewModel: ChangesWindowViewModel, keys: ChangesWindowKeys, onClose: () -> Unit) {
  val state by viewModel.state.collectAsState()
  val onAction = viewModel::onAction
  val filterFocus = remember { FocusRequester() }
  val pickerSearch = remember { FocusRequester() }
  var filterFocused by remember { mutableStateOf(false) }
  val authoring = remember { DiffAuthoringInput() }
  val wordHighlight = remember { DiffWordHighlight() }
  var focusedReplyId by remember { mutableStateOf<String?>(null) }
  var filterFocusRequests by remember { mutableIntStateOf(0) }
  // The request waits a frame so the `/` typed after its key-down does not land in the filter.
  LaunchedEffect(filterFocusRequests) {
    if (filterFocusRequests == 0) return@LaunchedEffect
    withFrameNanos { }
    filterFocus.requestFocus()
  }
  val focusManager = LocalFocusManager.current
  val textFocused by rememberUpdatedState(filterFocused)
  val replyFocused by rememberUpdatedState(focusedReplyId)
  val currentOnClose by rememberUpdatedState(onClose)
  DisposableEffect(keys, viewModel) {
    keys.handler = handler@{ event ->
      val key = windowKeyFor(event) ?: return@handler false
      val current = viewModel.state.value
      val open = current.pickerOpen
      // The picker popup shares the window scene, so this handler sees its keys first.
      // While it is open its search owns typing. Esc unwinds one level: picker, then filter, then the panel.
      if (key == WindowKey.Escape) {
        when {
          current.review.confirmingDiscard -> viewModel.onAction(ChangesWindowAction.KeepPending)
          open -> viewModel.onAction(ChangesWindowAction.ClosePicker)
          replyFocused != null -> return@handler false
          current.editing != null -> { viewModel.onAction(ChangesWindowAction.CancelEdit); focusManager.clearFocus(); authoring.commentTextFocused = false }
          current.composer != null -> { viewModel.onAction(ChangesWindowAction.CancelComposer); focusManager.clearFocus(); authoring.commentTextFocused = false }
          textFocused -> focusManager.clearFocus()
          wordHighlight.selection != null -> wordHighlight.clear()
          else -> currentOnClose()
        }
        return@handler true
      }
      if (open) return@handler false
      if (textFocused || authoring.commentTextFocused || replyFocused != null) return@handler false
      if (key == WindowKey.AddReview) {
        if (current.pendingCount > 0) viewModel.onAction(ChangesWindowAction.SubmitReview)
        return@handler true
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
        WindowKey.Comment -> authoring.hovered?.takeIf { current.canAuthor }?.let { viewModel.onAction(ChangesWindowAction.OpenComposer(it.path, it.anchor.side, it.anchor.line)) }
        WindowKey.AddReview -> Unit
      }
      key != WindowKey.Comment || authoring.hovered?.let { current.canAuthor } == true
    }
    onDispose { keys.handler = { false } }
  }
  val colors = SkillBillTheme.colors
  Column(Modifier.fillMaxSize().background(colors.bgWindow)) {
    when (val content = state.content) {
      WindowContent.Loading -> CenterHint("Loading changes")
      WindowContent.Missing -> CenterHint("This execution is not in the open database.")
      WindowContent.NoBaseline -> CenterHint("No change baseline for this execution.")
      is WindowContent.Problem -> CenterHint(readProblemText(content.problem))
      is WindowContent.Ready -> {
        ScopeToolbar(state, onAction, pickerSearch, filterFocus) { filterFocused = it }
        if (state.view == ChangesView.Commits && state.commits.isEmpty()) {
          Box(Modifier.fillMaxWidth().padding(vertical = 18.dp), contentAlignment = Alignment.Center) { Hint("No commits on this branch yet.") }
        } else if (state.view == ChangesView.Commits) {
          Box(Modifier.fillMaxWidth().background(colors.bgPanel).hairline(Edge.Bottom, colors.border).padding(horizontal = 14.dp, vertical = 8.dp)) {
            CommitStrip(
              state.commits,
              state.commitStats,
              (state.scope as? ChangeScope.Commits)?.shas?.toSet().orEmpty(),
              onPick = { sha, extend -> onAction(ChangesWindowAction.PickCommit(sha, extend, false)) },
              onDrag = { from, to -> onAction(ChangesWindowAction.DragCommits(from, to)) },
            )
          }
        } else if (state.subtasksAvailable) {
          val range = (state.scope as? ChangeScope.Subtasks)?.let { it.from..it.to }
          Box(
            Modifier.fillMaxWidth().background(colors.bgPanel).hairline(Edge.Bottom, colors.border)
              .padding(start = 14.dp, top = 4.dp, end = 14.dp, bottom = 10.dp),
          ) {
            SubtaskStrip(
              state.subtasks,
              state.stats,
              selected = range,
              onPick = { id, extend -> onAction(ChangesWindowAction.PickSubtask(id, extend, fromPicker = false)) },
              onDrag = { from, to -> onAction(ChangesWindowAction.DragSubtasks(from, to)) },
            )
          }
        }
        ScopeBar(state, onAction)
        BoxWithConstraints(Modifier.fillMaxWidth().weight(1f)) {
          val listWidth = minOf(FILE_LIST_WIDTH, maxWidth * FILE_LIST_MAX_SHARE)
          Row(Modifier.fillMaxSize()) {
            FileListPane(state, onAction, Modifier.width(listWidth).fillMaxHeight())
            DiffPane(state, onAction, Modifier.weight(1f).fillMaxHeight(), authoring, wordHighlight, onReplyFocusChanged = { id, focused ->
              if (focused) focusedReplyId = id else if (focusedReplyId == id) focusedReplyId = null
            })
          }
        }
        ChangesFooter(state.subtasksAvailable, state.chatScopes, state.view, state.canAuthor)
      }
    }
  }
}

@Composable
private fun CenterHint(text: String) = Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) { Hint(text) }

@Composable
private fun ChangesFooter(subtasksAvailable: Boolean, chatScopes: Boolean, view: ChangesView, canAuthor: Boolean) {
  val colors = SkillBillTheme.colors
  val text = changesSans(11f, FontWeight.Normal, colors.fgFaint)
  Row(
    Modifier.fillMaxWidth().height(26.dp).background(colors.bgPanel).hairline(Edge.Top, colors.border).padding(horizontal = 14.dp),
    horizontalArrangement = Arrangement.spacedBy(14.dp),
    verticalAlignment = Alignment.CenterVertically,
  ) {
    if (subtasksAvailable || chatScopes && view == ChangesView.Commits) {
      FooterHint(listOf("s"), if (view == ChangesView.Commits) "pick commit" else "pick subtask")
      FooterHint(listOf("[", "]"), if (view == ChangesView.Commits) "previous / next commit" else "previous / next subtask")
    }
    FooterHint(listOf("a"), "all")
    FooterHint(listOf("j", "k"), "file")
    FooterHint(listOf("/"), "filter")
    FooterHint(listOf("o"), "open in editor")
    if (canAuthor) FooterHint(listOf("c"), "comment")
    Spacer(Modifier.weight(1f))
    if (!chatScopes) BasicText("Refreshes every 3 s while the ${if (subtasksAvailable) "goal" else "run"} runs", style = text, maxLines = 1, softWrap = false)
  }
}

@Composable
private fun FooterHint(keys: List<String>, label: String) {
  Row(horizontalArrangement = Arrangement.spacedBy(4.dp), verticalAlignment = Alignment.CenterVertically) {
    keys.forEach { KeyCap(it) }
    BasicText(label, style = changesSans(11f, FontWeight.Normal, SkillBillTheme.colors.fgFaint), maxLines = 1, softWrap = false)
  }
}

private val FILE_LIST_WIDTH = 380.dp
private const val FILE_LIST_MAX_SHARE = 0.45f

/**
 * The panel's window look: 12 dp corners, a hairline and a shadow around a title bar with a centered title. The close button sits at
 * the start of the bar when [closeAtStart], as macOS windows have it, else at the end.
 */
@Composable
fun ChangesWindowFrame(title: String, closeAtStart: Boolean, onClose: () -> Unit, content: @Composable () -> Unit) {
  val colors = SkillBillTheme.colors
  val shape = RoundedCornerShape(12.dp)
  val closeSlot: @Composable (Boolean) -> Unit = { shown ->
    Box(Modifier.width(TITLE_SIDE_WIDTH), contentAlignment = Alignment.Center) {
      if (shown) SbIconButton(LucideIcons.x, "Close changes", onClose, size = SbControlSize.Small)
    }
  }
  Box(Modifier.fillMaxSize()) {
    ShadowBackdrop(SkillBillTheme.shadows.modal, shape, Modifier.matchParentSize())
    Column(
      Modifier.fillMaxSize()
        .clip(shape)
        .background(colors.bgWindow, shape)
        .border(1.dp, colors.border, shape)
        .padding(1.dp)
        .semantics { paneTitle = title },
    ) {
      Row(
        Modifier.fillMaxWidth().height(38.dp).background(colors.bgInset).hairline(Edge.Bottom, colors.border).padding(horizontal = 8.dp),
        horizontalArrangement = Arrangement.spacedBy(10.dp),
        verticalAlignment = Alignment.CenterVertically,
      ) {
        closeSlot(closeAtStart)
        BasicText(
          title,
          Modifier.weight(1f),
          style = changesSans(12f, FontWeight.SemiBold, colors.fgMuted).copy(textAlign = TextAlign.Center),
          maxLines = 1,
          overflow = TextOverflow.Ellipsis,
        )
        closeSlot(!closeAtStart)
      }
      content()
    }
  }
}

private val TITLE_SIDE_WIDTH = 32.dp
