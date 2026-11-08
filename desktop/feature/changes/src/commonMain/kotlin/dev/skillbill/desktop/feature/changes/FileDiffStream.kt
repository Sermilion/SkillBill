package dev.skillbill.desktop.feature.changes

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.EnterTransition
import androidx.compose.animation.ExitTransition
import androidx.compose.animation.core.MutableTransitionState
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.text.BasicText
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.foundation.ScrollState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import dev.skillbill.desktop.core.designsystem.SkillBillTheme
import dev.skillbill.desktop.core.designsystem.composite.Edge
import dev.skillbill.desktop.core.designsystem.composite.hairline
import dev.skillbill.desktop.core.designsystem.LocalReducedMotion
import dev.skillbill.desktop.core.domain.model.ChangedFile
import dev.skillbill.desktop.core.domain.model.FileDiffState
import dev.skillbill.desktop.core.domain.model.CommentThread
import dev.skillbill.desktop.core.ui.diff.DiffLineSlots
import dev.skillbill.desktop.core.ui.diff.DiffLineAddress
import dev.skillbill.desktop.core.ui.diff.DiffLineAffordance
import dev.skillbill.desktop.core.ui.diff.DiffWordHighlight
import dev.skillbill.desktop.core.ui.changes.AddCount
import dev.skillbill.desktop.core.ui.changes.DelCount
import dev.skillbill.desktop.core.ui.changes.changesMono
import dev.skillbill.desktop.core.ui.diff.DiffHorizontalScroll
import dev.skillbill.desktop.core.ui.diff.diffRows
import dev.skillbill.desktop.core.ui.diff.fileDiffNotice
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.onEach

/** Files below the viewport to read ahead, so scrolling into the next file rarely meets a placeholder. */
private const val READ_AHEAD_FILES = 2

/** Tall enough that a screen of unloaded files asks for only a few reads. */
private val PLACEHOLDER_HEIGHT = 240.dp

/**
 * Every visible file's diff in one scrolling list: each file is a separator row and then its diff, or a placeholder until the diff
 * is read. The first file has no separator, since the pane header above already names it. A user scroll selects the file at the top;
 * a growing [scrollRequest] jumps to the selected file.
 */
@Composable
internal fun FileDiffStream(
  files: List<ChangedFile>,
  loaded: Map<String, LoadedDiff>,
  selectedPath: String?,
  scrollRequest: Int,
  threads: List<CommentThread>,
  threadContent: @Composable (String) -> Unit,
  onAction: (ChangesWindowAction) -> Unit,
  composer: CommentComposerUi? = null,
  authoring: DiffAuthoringInput = DiffAuthoringInput(),
  canAuthor: Boolean = false,
  onCommentFocusChanged: (Boolean) -> Unit = {},
  bottomPadding: Dp = 0.dp,
  wordHighlight: DiffWordHighlight = remember { DiffWordHighlight() },
  modifier: Modifier = Modifier,
) {
  val listState = rememberLazyListState()
  val usable = files.map { file -> loaded[file.path]?.takeIf { it.file == file } }
  val placement = remember(files, usable, threads) { placeThreads(files, usable, threads) }
  val starts = fileStartIndexes(usable, placement, composer, files)
  val currentFiles by rememberUpdatedState(files)
  val currentStarts by rememberUpdatedState(starts)
  val currentAction by rememberUpdatedState(onAction)
  val currentSelected by rememberUpdatedState(selectedPath)
  var jumping by remember { mutableStateOf(false) }
  var followedPath by remember { mutableStateOf(selectedPath) }
  DisposableEffect(wordHighlight) { onDispose { wordHighlight.clear() } }
  LaunchedEffect(files) {
    wordHighlight.selection?.let { selected -> if (files.none { it.path == selected.fileKey }) wordHighlight.clear() }
  }
  LaunchedEffect(scrollRequest) {
    val index = currentFiles.indexOfFirst { it.path == currentSelected }
    if (index < 0) return@LaunchedEffect
    followedPath = currentSelected
    // scrollToItem before the first layout can wait on it forever, which also blocks wheel scrolling.
    snapshotFlow { listState.layoutInfo.totalItemsCount > 0 }.first { it }
    jumping = true
    try {
      listState.scrollToItem(currentStarts[index])
    } finally {
      jumping = false
    }
  }
  LaunchedEffect(composer?.path, composer?.side, composer?.line) {
    val draft = composer ?: return@LaunchedEffect
    if (draft.side != null) return@LaunchedEffect
    val index = files.indexOfFirst { it.path == draft.path }
    if (index < 0) return@LaunchedEffect
    followedPath = draft.path
    jumping = true
    try {
      listState.scrollToItem(starts[index] + 1 + placement.files[index].fileSlot.size)
    } finally {
      jumping = false
    }
  }
  LaunchedEffect(listState) {
    snapshotFlow { listState.firstVisibleItemIndex to jumping }
      .distinctUntilChanged()
      .onEach { (first, jump) ->
        currentFiles.getOrNull(fileAt(currentStarts, first))?.path?.let { path ->
          if (jump) {
            followedPath = path
          } else if (path != followedPath) {
            followedPath = path
            currentAction(ChangesWindowAction.FollowScroll(path))
          }
        }
      }
      .collect()
  }
  LaunchedEffect(listState) {
    snapshotFlow {
      val shown = listState.layoutInfo.visibleItemsInfo
      val first = fileAt(currentStarts, shown.firstOrNull()?.index ?: 0)
      val last = fileAt(currentStarts, shown.lastOrNull()?.index ?: 0)
      currentFiles.subList(first.coerceAtMost(currentFiles.size), (last + 1 + READ_AHEAD_FILES).coerceAtMost(currentFiles.size)).map { it.path }
    }.distinctUntilChanged().collect { currentAction(ChangesWindowAction.EnsureDiffs(it)) }
  }
  BoxWithConstraints(modifier.fillMaxSize().pointerInput(wordHighlight) { detectTapGestures(onTap = { wordHighlight.clear() }) }) {
    val viewportWidth = maxWidth
    val threadWidth = minOf(viewportWidth, 760.dp)
    val horizontalScroll = rememberScrollState()
    DiffHorizontalScroll(usable.maxOfOrNull { it?.longestChars ?: 0 } ?: 0, Modifier.fillMaxSize(), horizontalScroll) {
      LazyColumn(state = listState, contentPadding = PaddingValues(bottom = bottomPadding)) {
        if (placement.general.isNotEmpty()) {
          item(key = "general:header", contentType = "general-header") {
            BasicText(
              "General · ${placement.general.size}",
              threadItemModifier(threadWidth, horizontalScroll).semantics { heading() },
              style = changesMono(12f, FontWeight.SemiBold, SkillBillTheme.colors.fg2),
            )
          }
          placement.general.forEach { thread ->
            item(key = "thread:${thread.id}", contentType = "thread") {
              ThreadAppearing(threadWidth, horizontalScroll) {
                Column {
                  thread.path?.let { path -> BasicText(if (thread.line == null) path else "$path:${thread.line}", style = changesMono(11f, FontWeight.Medium, SkillBillTheme.colors.fgFaint)) }
                  threadContent(thread.id)
                }
              }
            }
          }
        }
        files.forEachIndexed { index, file ->
          val loadedState = usable[index]?.state
          val shownDiff = (loadedState as? FileDiffState.Shown)?.diff
          val intendedAddress = composer?.takeIf { it.path == file.path && it.side != null && it.line != null }?.let { draft ->
            shownDiff?.hunks?.asSequence()?.flatMapIndexed { h, hunk -> hunk.lines.asSequence().mapIndexed { i, line -> DiffLineAddress(h, i) to line } }
              ?.firstOrNull { (_, line) -> commentAnchorFor(line) == CommentAnchor(draft.side!!, draft.line!!) }?.first
          }
          val fileComposer = composer?.takeIf { it.path == file.path && (it.side == null || intendedAddress == null) }
          item(key = "file:${file.path}", contentType = "file") {
            if (index == 0) {
              Spacer(Modifier.height(0.dp))
            } else FileSeparator(file, if (canAuthor) ({ onAction(ChangesWindowAction.OpenComposer(file.path, null, null)) }) else null)
          }
          placement.files[index].fileSlot.forEach { thread ->
            item(key = "thread:${thread.id}", contentType = "thread") {
              ThreadAppearing(threadWidth, horizontalScroll) { threadContent(thread.id) }
            }
          }
          if (fileComposer != null) item(key = "composer:${file.path}", contentType = "composer") {
            ThreadAppearing(threadWidth, horizontalScroll) { CommentComposer(fileComposer.text, fileComposer.problem, fileComposer.saving, "Leave a comment", "Stays pending until you add the review", "Add to review", { onAction(ChangesWindowAction.ComposerText(it)) }, { onAction(ChangesWindowAction.SavePending) }, { onAction(ChangesWindowAction.CancelComposer) }, onCommentFocusChanged) }
          }
          val diff = usable[index]
          val state = diff?.state
          when {
            diff == null -> item(key = "wait:${file.path}", contentType = "wait") { Box(Modifier.fillMaxWidth().height(PLACEHOLDER_HEIGHT)) }
            state is FileDiffState.Shown && state.diff.hunks.isNotEmpty() -> {
              val lineComposerAddress = intendedAddress
              val lineSlots = DiffLineSlots(
                (placement.files[index].afterLine.keys + listOfNotNull(lineComposerAddress)).associateWith { address ->
                  placement.files[index].afterLine[address].orEmpty().map { it.id } +
                    if (address == lineComposerAddress) listOf("composer:${file.path}:${composer?.side}:${composer?.line}") else emptyList()
                },
              ) { slotId ->
                if (slotId.startsWith("composer:")) {
                  val draft = composer ?: return@DiffLineSlots
                  ThreadAppearing(threadWidth, horizontalScroll) { CommentComposer(draft.text, draft.problem, draft.saving, "Leave a comment", "Stays pending until you add the review", "Add to review", { onAction(ChangesWindowAction.ComposerText(it)) }, { onAction(ChangesWindowAction.SavePending) }, { onAction(ChangesWindowAction.CancelComposer) }, onCommentFocusChanged) }
                } else ThreadAppearing(threadWidth, horizontalScroll) { threadContent(slotId) }
              }
              val affordance = if (canAuthor) DiffLineAffordance(
                onAdd = { line -> commentAnchorFor(line)?.let { onAction(ChangesWindowAction.OpenComposer(file.path, it.side, it.line)) } },
                onHover = { line, entered ->
                  val hovered = commentAnchorFor(line)?.let { HoveredLine(file.path, it) }
                  if (entered) authoring.hovered = hovered else if (authoring.hovered == hovered) authoring.hovered = null
                },
              ) else null
              diffRows(state.diff, file.path, lineSlots, affordance, wordHighlight)
            }
            state != null -> item(key = "note:${file.path}", contentType = "note") { PaneMessage(fileDiffNotice(state)) }
          }
        }
      }
    }
  }
}

@Composable
private fun ThreadAppearing(width: Dp, scroll: ScrollState, content: @Composable () -> Unit) {
  val reduced = LocalReducedMotion.current
  val appear = remember(reduced) { MutableTransitionState(reduced).apply { if (!reduced) targetState = true } }
  AnimatedVisibility(
    appear,
    enter = if (reduced) EnterTransition.None else fadeIn() + expandVertically(),
    exit = if (reduced) ExitTransition.None else fadeOut() + shrinkVertically(),
  ) {
    Box(threadItemModifier(width, scroll)) { content() }
  }
}

private fun threadItemModifier(width: Dp, scroll: ScrollState): Modifier =
  Modifier.width(width).offset { IntOffset(scroll.value, 0) }.padding(start = 48.dp, end = 12.dp, bottom = 8.dp)

/** The list index of each file's separator row; a file with no read yet has one placeholder row after it. */
internal fun fileStartIndexes(loaded: List<LoadedDiff?>, placement: ThreadPlacement = ThreadPlacement(emptyList(), loaded.map { FileThreads() }), composer: CommentComposerUi? = null, files: List<ChangedFile>? = null): IntArray {
  val starts = IntArray(loaded.size)
  var next = if (placement.general.isEmpty()) 0 else 1 + placement.general.size
  loaded.forEachIndexed { index, diff ->
    starts[index] = next
    val fileThreads = placement.files.getOrNull(index) ?: FileThreads()
    val draft = composer?.takeIf { it.path == files?.getOrNull(index)?.path || it.path == diff?.file?.path }
    next += 1 + fileThreads.fileSlot.size + (if (draft != null) 1 else 0) + (diff?.rows ?: 1) + fileThreads.afterLine.values.sumOf { it.size }
  }
  return starts
}

/** The file whose rows hold list index [item], given [starts] from [fileStartIndexes]. */
internal fun fileAt(starts: IntArray, item: Int): Int {
  val found = starts.toList().binarySearch(item)
  return if (found >= 0) found else (-found - 2).coerceAtLeast(0)
}

@Composable
private fun FileSeparator(file: ChangedFile, onComment: (() -> Unit)?) {
  val colors = SkillBillTheme.colors
  Row(
    Modifier.fillMaxWidth().height(38.dp).background(colors.bgPanel).hairline(Edge.Bottom, colors.border).padding(horizontal = 14.dp),
    horizontalArrangement = Arrangement.spacedBy(10.dp),
    verticalAlignment = Alignment.CenterVertically,
  ) {
    BasicText(file.status.letter(), style = changesMono(11f, FontWeight.SemiBold, file.status.statusColor()), maxLines = 1)
    val path = buildAnnotatedString {
      withStyle(SpanStyle(color = colors.fgFaint)) { append(file.directory()) }
      append(file.path.substringAfterLast('/'))
    }
    BasicText(path, style = changesMono(12f, FontWeight.Medium, colors.fg, lineHeight = 15.6f), maxLines = 1, softWrap = false)
    if (file.binary) {
      BasicText("binary", style = changesMono(11f, FontWeight.Medium, colors.fgFaint), maxLines = 1, softWrap = false)
    } else {
      AddCount(file.insertions ?: 0)
      DelCount(file.deletions ?: 0)
    }
    if (onComment != null) dev.skillbill.desktop.core.designsystem.component.SbIconButton(dev.skillbill.desktop.core.designsystem.icon.LucideIcons.compose, "Comment on file", onComment, size = dev.skillbill.desktop.core.designsystem.component.SbControlSize.Small)
  }
}
