package dev.skillbill.desktop.feature.changes

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.animateItem
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.text.BasicText
import androidx.compose.runtime.Composable
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
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import dev.skillbill.desktop.core.designsystem.SkillBillTheme
import dev.skillbill.desktop.core.designsystem.LocalReducedMotion
import dev.skillbill.desktop.core.domain.model.ChangedFile
import dev.skillbill.desktop.core.domain.model.FileDiffState
import dev.skillbill.desktop.core.domain.model.CommentThread
import dev.skillbill.desktop.core.ui.diff.DiffLineSlots
import dev.skillbill.desktop.core.ui.changes.AddCount
import dev.skillbill.desktop.core.ui.changes.DelCount
import dev.skillbill.desktop.core.ui.changes.changesMono
import dev.skillbill.desktop.core.ui.changes.hairlineBottom
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
  modifier: Modifier = Modifier,
) {
  val listState = rememberLazyListState()
  val usable = files.map { file -> loaded[file.path]?.takeIf { it.file == file } }
  val placement = remember(files, usable, threads) { placeThreads(files, usable, threads) }
  val starts = fileStartIndexes(usable, placement)
  val currentFiles by rememberUpdatedState(files)
  val currentStarts by rememberUpdatedState(starts)
  val currentAction by rememberUpdatedState(onAction)
  val currentSelected by rememberUpdatedState(selectedPath)
  var jumping by remember { mutableStateOf(false) }
  var followedPath by remember { mutableStateOf(selectedPath) }
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
  BoxWithConstraints(modifier.fillMaxSize()) {
    val viewportWidth = maxWidth
    val threadWidth = minOf(viewportWidth, 760.dp)
    val horizontalScroll = rememberScrollState()
    DiffHorizontalScroll(usable.maxOfOrNull { it?.longestChars ?: 0 } ?: 0, Modifier.fillMaxSize(), horizontalScroll) {
      LazyColumn(state = listState) {
        if (placement.general.isNotEmpty()) {
          item(key = "general:header", contentType = "general-header") {
            BasicText(
              "General · ${placement.general.size}",
              threadItemModifier(threadWidth, horizontalScroll, Modifier).semantics { heading() },
              style = changesMono(12f, FontWeight.SemiBold, SkillBillTheme.colors.fg2),
            )
          }
          placement.general.forEach { thread ->
            item(key = "thread:${thread.id}", contentType = "thread") {
              val animation = if (LocalReducedMotion.current) Modifier else Modifier.animateItem()
              Column(threadItemModifier(threadWidth, horizontalScroll, animation)) {
                thread.path?.let { path -> BasicText(if (thread.line == null) path else "$path:${thread.line}", style = changesMono(11f, FontWeight.Medium, SkillBillTheme.colors.fgFaint)) }
                threadContent(thread.id)
              }
            }
          }
        }
        files.forEachIndexed { index, file ->
          item(key = "file:${file.path}", contentType = "file") { if (index == 0) Spacer(Modifier.height(0.dp)) else FileSeparator(file) }
          placement.files[index].fileSlot.forEach { thread ->
            item(key = "thread:${thread.id}", contentType = "thread") {
              val animation = if (LocalReducedMotion.current) Modifier else Modifier.animateItem()
              Box(threadItemModifier(threadWidth, horizontalScroll, animation)) { threadContent(thread.id) }
            }
          }
          val diff = usable[index]
          val state = diff?.state
          when {
            diff == null -> item(key = "wait:${file.path}", contentType = "wait") { Box(Modifier.fillMaxWidth().height(PLACEHOLDER_HEIGHT)) }
            state is FileDiffState.Shown && state.diff.hunks.isNotEmpty() -> {
              val lineSlots = DiffLineSlots(
                placement.files[index].afterLine.mapValues { (_, values) -> values.map { it.id } },
              ) { threadId ->
                val animation = if (LocalReducedMotion.current) Modifier else Modifier.animateItem()
                Box(threadItemModifier(threadWidth, horizontalScroll, animation)) { threadContent(threadId) }
              }
              diffRows(state.diff, file.path, lineSlots)
            }
            state != null -> item(key = "note:${file.path}", contentType = "note") { PaneMessage(fileDiffNotice(state)) }
          }
        }
      }
    }
  }
}

private fun threadItemModifier(width: Dp, scroll: ScrollState, animation: Modifier): Modifier =
  Modifier.width(width).offset { IntOffset(scroll.value, 0) }.then(animation).padding(start = 48.dp, end = 12.dp, bottom = 8.dp)

/** The list index of each file's separator row; a file with no read yet has one placeholder row after it. */
internal fun fileStartIndexes(loaded: List<LoadedDiff?>, placement: ThreadPlacement = ThreadPlacement(emptyList(), loaded.map { FileThreads() })): IntArray {
  val starts = IntArray(loaded.size)
  var next = if (placement.general.isEmpty()) 0 else 1 + placement.general.size
  loaded.forEachIndexed { index, diff ->
    starts[index] = next
    val fileThreads = placement.files.getOrNull(index) ?: FileThreads()
    next += 1 + fileThreads.fileSlot.size + (diff?.rows ?: 1) + fileThreads.afterLine.values.sumOf { it.size }
  }
  return starts
}

/** The file whose rows hold list index [item], given [starts] from [fileStartIndexes]. */
internal fun fileAt(starts: IntArray, item: Int): Int {
  val found = starts.toList().binarySearch(item)
  return if (found >= 0) found else (-found - 2).coerceAtLeast(0)
}

@Composable
private fun FileSeparator(file: ChangedFile) {
  val colors = SkillBillTheme.colors
  Row(
    Modifier.fillMaxWidth().height(38.dp).background(colors.bgPanel).hairlineBottom(colors.border).padding(horizontal = 14.dp),
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
  }
}
