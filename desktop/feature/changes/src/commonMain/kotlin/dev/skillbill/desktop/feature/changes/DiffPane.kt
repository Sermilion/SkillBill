package dev.skillbill.desktop.feature.changes

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.text.BasicText
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.remember
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.setValue
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.disabled
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.dp
import dev.skillbill.desktop.core.designsystem.SkillBillTheme
import dev.skillbill.desktop.core.designsystem.composite.Edge
import dev.skillbill.desktop.core.designsystem.composite.hairline
import dev.skillbill.desktop.core.designsystem.component.FeedbackTone
import dev.skillbill.desktop.core.designsystem.component.Hint
import dev.skillbill.desktop.core.designsystem.toastInSpec
import dev.skillbill.desktop.core.designsystem.component.SbTag
import dev.skillbill.desktop.core.domain.model.ChangeStatus
import dev.skillbill.desktop.core.domain.model.ChangedFile
import dev.skillbill.desktop.core.domain.model.DiffCommentsState
import dev.skillbill.desktop.core.ui.changes.AddCount
import dev.skillbill.desktop.core.designsystem.component.SbButton
import dev.skillbill.desktop.core.ui.changes.DelCount
import dev.skillbill.desktop.core.ui.changes.changesMono
import dev.skillbill.desktop.core.ui.changes.changesSans
import dev.skillbill.desktop.core.ui.diff.DiffWordHighlight

@Composable
internal fun DiffPane(state: ChangesWindowUiState, onAction: (ChangesWindowAction) -> Unit, modifier: Modifier = Modifier, authoring: DiffAuthoringInput, wordHighlight: DiffWordHighlight, onReplyFocusChanged: (String, Boolean) -> Unit) {
  val colors = SkillBillTheme.colors
  var lastToast by remember { mutableStateOf("") }
  var reviewBarHeight by remember { mutableIntStateOf(0) }
  val reviewPadding = if (state.pendingCount > 0) with(LocalDensity.current) { reviewBarHeight.toDp() } + 36.dp else 0.dp
  LaunchedEffect(state.reviewToast) { state.reviewToast?.let { lastToast = it } }
  Column(modifier.background(colors.bgInset)) {
    val file = when (val diff = state.diff) {
      DiffPaneUi.NoSelection -> null
      is DiffPaneUi.Loading -> diff.file
      is DiffPaneUi.Shown -> diff.file
    }
    Column(
      Modifier.fillMaxWidth().background(colors.bgPanel).hairline(Edge.Bottom, colors.border).padding(horizontal = 14.dp, vertical = 10.dp),
      verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
      if (file == null) {
        BasicText("Select a file", Modifier.heightIn(min = 16.dp), style = changesMono(12f, FontWeight.Medium, colors.fgMuted, lineHeight = 15.6f), maxLines = 1)
      } else {
        DiffTitle(file, showCounts = state.list is ScopeListUi.Listed, canOpen = state.selectedFile?.let { it.status != ChangeStatus.Deleted } == true, canComment = state.canAuthor, onComment = { onAction(ChangesWindowAction.OpenComposer(file.path, null, null)) }) {
          onAction(ChangesWindowAction.OpenSelectedFile)
        }
      }
      state.openProblem?.let { Hint(it, tone = FeedbackTone.Error) }
      state.authorHint?.let { Hint(it) }
      (state.diff as? DiffPaneUi.Shown)?.chips?.takeIf { it.isNotEmpty() }?.let { chips -> SubtaskChips(chips, onAction) }
    }
    Box(Modifier.weight(1f)) {
      if (state.list is ScopeListUi.Listed) {
        key(state.scope, state.filter) {
          val threads = (state.comments as? DiffCommentsState.Available)?.threads.orEmpty()
          FileDiffStream(
            files = state.visibleFiles,
            loaded = state.fileDiffs,
            selectedPath = state.selectedPath,
            scrollRequest = state.scrollRequest,
            threads = threads,
            threadContent = { id ->
              threads.firstOrNull { it.id == id }?.let { thread ->
                DiffCommentThread(thread, state.threadUi[id] ?: ThreadUi(), onAction, onReplyFocusChanged,
                  editing = state.editing?.takeIf { it.threadId == thread.id }, onEditFocusChanged = { authoring.commentTextFocused = it })
              }
            },
            onAction = onAction,
            composer = state.composer,
            authoring = authoring,
            canAuthor = state.canAuthor,
            onCommentFocusChanged = { authoring.commentTextFocused = it },
            bottomPadding = reviewPadding,
            wordHighlight = wordHighlight,
            modifier = Modifier.fillMaxSize(),
          )
        }
      }
      androidx.compose.animation.AnimatedVisibility(state.pendingCount > 0, Modifier.align(Alignment.BottomCenter).padding(horizontal = 12.dp, vertical = 18.dp), enter = slideInVertically(toastInSpec()) { it } + fadeIn(toastInSpec()), exit = slideOutVertically(toastInSpec()) { it } + fadeOut(toastInSpec())) {
        PendingReviewBar(state.pendingCount, state.review, onAction, Modifier.onSizeChanged { reviewBarHeight = it.height }.absorbTaps())
      }
      androidx.compose.animation.AnimatedVisibility(state.reviewToast != null, Modifier.align(Alignment.BottomEnd).padding(18.dp), enter = slideInVertically(toastInSpec()) { it } + fadeIn(toastInSpec()), exit = slideOutVertically(toastInSpec()) { it } + fadeOut(toastInSpec())) {
        ReviewToast(lastToast, Modifier.absorbTaps())
      }
    }
  }
}

private fun Modifier.absorbTaps(): Modifier = pointerInput(Unit) { detectTapGestures { } }

@Composable
private fun DiffTitle(file: ChangedFile, showCounts: Boolean, canOpen: Boolean, canComment: Boolean, onComment: () -> Unit, onOpen: () -> Unit) {
  val colors = SkillBillTheme.colors
  Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(10.dp), verticalAlignment = Alignment.CenterVertically) {
    BasicText(file.status.letter(), style = changesMono(11f, FontWeight.SemiBold, file.status.statusColor()), maxLines = 1)
    val path = buildAnnotatedString {
      withStyle(SpanStyle(color = colors.fgFaint)) { append(file.directory()) }
      append(file.path.substringAfterLast('/'))
    }
    BasicText(path, Modifier.weight(1f), style = changesMono(12f, FontWeight.Medium, colors.fg, lineHeight = 15.6f), maxLines = 1, overflow = TextOverflow.Ellipsis)
    if (showCounts) {
      if (file.binary) {
        BasicText("binary", style = changesMono(11f, FontWeight.Medium, colors.fgFaint), maxLines = 1, softWrap = false)
      } else {
        AddCount(file.insertions ?: 0)
        DelCount(file.deletions ?: 0)
      }
    }
    if (canComment) dev.skillbill.desktop.core.designsystem.component.SbIconButton(dev.skillbill.desktop.core.designsystem.icon.LucideIcons.compose, "Comment on file", onComment, size = dev.skillbill.desktop.core.designsystem.component.SbControlSize.Small)
    SbButton("Open in editor", onOpen, enabled = canOpen)
  }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun SubtaskChips(chips: List<SubtaskChipUi>, onAction: (ChangesWindowAction) -> Unit) {
  FlowRow(horizontalArrangement = Arrangement.spacedBy(5.dp), verticalArrangement = Arrangement.spacedBy(5.dp), itemVerticalAlignment = Alignment.CenterVertically) {
    BasicText(
      if (chips.size == 1) "Changed by subtask" else "Changed by subtasks",
      style = changesSans(11f, FontWeight.Normal, SkillBillTheme.colors.fgFaint),
      maxLines = 1,
      softWrap = false,
    )
    chips.forEach { chip ->
      val modifier = Modifier.alpha(if (chip.selectable) 1f else 0.4f).then(
        if (chip.selectable) Modifier else Modifier.semantics(mergeDescendants = true) { role = Role.Button; disabled() },
      )
      SbTag(
        "${chip.id}",
        modifier = modifier,
        mono = true,
        outlined = !chip.inScope,
        tone = if (chip.inScope) SkillBillTheme.colors.accent else null,
        onClick = if (chip.selectable) ({ onAction(ChangesWindowAction.NarrowToSubtask(chip.id)) }) else null,
        tooltip = chip.name,
      )
    }
  }
}

/** The file's directory with its trailing slash; empty for a root-level file. */
internal fun ChangedFile.directory(): String = path.substringBeforeLast('/', "").let { if (it.isEmpty()) "" else "$it/" }
