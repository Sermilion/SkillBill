package dev.skillbill.desktop.feature.chat.newtask

import androidx.compose.foundation.clickable
import androidx.compose.foundation.focusable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicText
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusProperties
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.input.pointer.PointerIcon
import androidx.compose.ui.input.pointer.pointerHoverIcon
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.max
import dev.skillbill.desktop.core.designsystem.SkillBillRadius
import dev.skillbill.desktop.core.designsystem.SkillBillTheme
import dev.skillbill.desktop.core.designsystem.component.SbDivider
import dev.skillbill.desktop.core.designsystem.component.SbButton
import dev.skillbill.desktop.core.designsystem.component.SbButtonVariant
import dev.skillbill.desktop.core.designsystem.component.SbControlSize
import dev.skillbill.desktop.core.designsystem.component.SbMenuEntry
import dev.skillbill.desktop.core.designsystem.component.SbMenuItem
import dev.skillbill.desktop.core.designsystem.component.SkeletonBlock
import dev.skillbill.desktop.core.designsystem.component.SbTooltip
import dev.skillbill.desktop.core.designsystem.component.sbFocusRing
import dev.skillbill.desktop.core.designsystem.icon.LucideIcons
import dev.skillbill.desktop.core.ui.input.gatesShortcutsWhileFocused
import dev.skillbill.desktop.feature.chat.savedAgo
import java.io.File
import kotlin.time.Instant
import kotlinx.coroutines.launch

private val PICKER_HEIGHT = 264.dp
private val HEAD_ROW_HEIGHT = 32.dp
private val LEFT_COLUMN_FLOOR = 190.dp
private val ERROR_BLOCK_MAX_HEIGHT = 120.dp
private const val FILTER_ROW_THRESHOLD = 7
private const val REPOSITORY_FILTER_PLACEHOLDER = "Filter repositories"
private const val BRANCH_FILTER_PLACEHOLDER = "Filter branches"
private const val ADD_REPOSITORY_KEY = ""
private val SKELETON_WIDTHS = listOf(.62f, .78f, .54f, .70f)

internal data class NewTaskRepositoryRow(val repository: NewTaskRepository, val parent: String?)

internal fun repositoryRows(listed: List<NewTaskRepository>, added: List<NewTaskRepository>, selected: NewTaskRepository?): List<NewTaskRepositoryRow> {
  val merged = (listed + added + listOfNotNull(selected)).distinctBy { it.root }
  val nameCounts = merged.groupingBy { it.name }.eachCount()
  return merged.map { repository ->
    val shared = nameCounts.getValue(repository.name) > 1
    NewTaskRepositoryRow(repository, if (shared) File(repository.root).parentFile?.name else null)
  }
}

@Composable
internal fun NewTaskRepositoryPickerContent(
  state: NewTaskUiState,
  draft: NewTaskDraft,
  now: () -> Instant,
  repositoryFilterFocus: FocusRequester,
  branchFilterFocus: FocusRequester,
  composerFocus: FocusRequester,
  onAction: (NewTaskAction) -> Unit,
) {
  val colors = SkillBillTheme.colors
  var branchFilter by remember { mutableStateOf("") }
  val branchHighlight = remember { PickerHighlight() }
  val branchRows = branchListing(
    (state.branchRead as? BranchReadState.Loaded)?.takeIf { it.root == draft.repository?.root }?.branches,
    branchFilter,
  )
  val branchPreferred = draft.baseBranch.takeIf { it.isNotBlank() && branchRows.rows.any { row -> row.value == it } } ?: ""
  val branchKeys = if (draft.repository == null) emptyList() else branchRows.rows.map { it.value }
  val branchIndex = branchHighlight.index(branchKeys, branchPreferred)
  val branchListState = rememberLazyListState()
  val scrollScope = rememberCoroutineScope()
  var branchActionFocused by remember { mutableStateOf(false) }
  val seenRepositoryPicks = remember { mutableIntStateOf(state.repositoryPicks) }
  LaunchedEffect(state.repositoryPicks) {
    if (state.repositoryPicks != seenRepositoryPicks.intValue) {
      seenRepositoryPicks.intValue = state.repositoryPicks
      branchFilter = ""
      branchHighlight.target("")
      branchFilterFocus.requestFocus()
    }
  }
  val branchPicker = Modifier.pickerKeys(
    highlight = branchHighlight,
    keys = branchKeys,
    preferred = branchPreferred,
    actionFocused = branchActionFocused,
    onMove = { index ->
      branchLazyIndex(branchRows, index)?.let { target ->
        scrollScope.launch { branchListState.animateScrollToItem(target) }
      }
    },
  ) { index ->
    branchRows.rows.getOrNull(index)?.let { row ->
      composerFocus.requestFocus()
      onAction(NewTaskAction.ChooseBaseBranch(row.value))
    }
  }
  BoxWithConstraints(Modifier.fillMaxWidth()) {
    val leftWidth = max(LEFT_COLUMN_FLOOR, maxWidth / 3)
    Row(Modifier.fillMaxWidth().height(PICKER_HEIGHT)) {
      RepositoryColumn(state, draft, Modifier.width(leftWidth).fillMaxHeight(), repositoryFilterFocus, onAction)
      SbDivider(vertical = true)
      Column(
        branchPicker.weight(1f).fillMaxHeight().padding(10.dp),
      ) {
        Box(Modifier.fillMaxWidth().height(HEAD_ROW_HEIGHT).padding(horizontal = 12.dp), contentAlignment = Alignment.CenterStart) {
          BasicText("Base branch", style = SkillBillTheme.type.tXs.copy(color = colors.fgFaint), maxLines = 1)
        }
        val repository = draft.repository
        if (repository == null) {
          BranchEmptyState(Modifier.weight(1f).fillMaxWidth(), "Choose a repository first", "Branches come from the repository you pick.")
        } else {
          PickerFilter(branchFilter, BRANCH_FILTER_PLACEHOLDER, branchFilterFocus) {
            branchFilter = it
            branchHighlight.resetToFirst(branchListing(
              (state.branchRead as? BranchReadState.Loaded)?.takeIf { read -> read.root == repository.root }?.branches,
              it,
            ).rows.map { row -> row.value })
          }
          val read = state.branchRead
          val loaded = (read as? BranchReadState.Loaded)?.takeIf { it.root == repository.root }
          val failed = (read as? BranchReadState.Failed)?.takeIf { it.root == repository.root }
          val reading = loaded == null && failed == null
          val defaultRow = branchRows.default ?: BranchRow.Default(null)
          if (branchRows.rows.firstOrNull() is BranchRow.Default) {
            BranchMenuItem(
              item = SbMenuEntry.Item(
                defaultRow.name ?: "default branch",
                { composerFocus.requestFocus(); onAction(NewTaskAction.ChooseBaseBranch("")) },
                endText = "default",
                checked = draft.baseBranch.isBlank() || (defaultRow.name != null && draft.baseBranch == defaultRow.name),
                mono = true,
              ),
              highlighted = branchIndex == 0,
            )
          }
          when {
            reading -> {
              BranchSkeleton(Modifier.weight(1f).fillMaxWidth())
            }
            failed != null -> {
              Column(Modifier.weight(1f).fillMaxWidth().padding(8.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                BasicText("Could not read branches for ${repository.name}. The default branch still works.", style = SkillBillTheme.type.tSm.copy(color = colors.stFailed))
                SbButton(
                  "Retry", { onAction(NewTaskAction.RetryBranches) },
                  variant = SbButtonVariant.Ghost,
                  size = SbControlSize.Small,
                  modifier = Modifier.onFocusChanged { branchActionFocused = it.isFocused },
                )
              }
            }
            loaded != null && branchRows.rows.isEmpty() -> BranchEmptyState(Modifier.weight(1f).fillMaxWidth(), "No branch matches \"${branchFilter.trim()}\"", "Only branches already fetched appear here.")
            loaded != null -> BranchRows(Modifier.weight(1f).fillMaxWidth(), branchRows, branchFilter, draft, branchIndex, branchListState, now, onAction, composerFocus)
          }
        }
      }
    }
  }
}

@Composable
private fun RepositoryColumn(
  state: NewTaskUiState,
  draft: NewTaskDraft,
  modifier: Modifier,
  filterFocus: FocusRequester,
  onAction: (NewTaskAction) -> Unit,
) {
  val colors = SkillBillTheme.colors
  val rows = repositoryRows(state.repositories, draft.addedRepositories, draft.repository)
  val filterable = rows.size > FILTER_ROW_THRESHOLD
  var filter by remember { mutableStateOf("") }
  val visible = if (filterable) rows.filter { it.repository.name.contains(filter.trim(), ignoreCase = true) } else rows
  val highlight = remember { PickerHighlight() }
  val keys = visible.map { it.repository.root } + ADD_REPOSITORY_KEY
  val preferred = draft.repository?.root ?: visible.firstOrNull()?.repository?.root ?: ADD_REPOSITORY_KEY
  val index = highlight.index(keys, preferred)
  var actionFocused by remember { mutableStateOf(false) }
  val listState = rememberLazyListState()
  val scrollScope = rememberCoroutineScope()
  LaunchedEffect(index, visible, filter) {
    val rowIndex = index?.takeIf { it < visible.size }
    if (rowIndex != null) listState.animateScrollToItem(rowIndex)
  }
  Column(modifier.pickerKeys(highlight, keys, preferred, actionFocused, onMove = { movedIndex ->
    movedIndex?.takeIf { it < visible.size }?.let { target ->
      scrollScope.launch { listState.animateScrollToItem(target) }
    }
  }) { picked ->
    when (picked) {
      visible.size -> onAction(NewTaskAction.AddRepository)
      else -> visible.getOrNull(picked)?.let { onAction(NewTaskAction.ChooseRepository(it.repository)) }
    }
  }) {
    Box(Modifier.fillMaxWidth().height(HEAD_ROW_HEIGHT).padding(horizontal = 12.dp), contentAlignment = Alignment.CenterStart) {
      BasicText("Repository", style = SkillBillTheme.type.tXs.copy(color = colors.fgFaint), maxLines = 1)
    }
    if (filterable) PickerFilter(filter, REPOSITORY_FILTER_PLACEHOLDER, filterFocus) {
      filter = it
      highlight.resetToFirst(rows.filter { row -> row.repository.name.contains(it.trim(), ignoreCase = true) }.map { row -> row.repository.root })
    }
    Box(Modifier.weight(1f).fillMaxWidth().then(if (!filterable) Modifier.focusable() else Modifier)) {
      LazyColumn(
        Modifier.fillMaxSize(),
        state = listState,
        contentPadding = PaddingValues(horizontal = 4.dp),
      ) {
        items(visible, key = { it.repository.root }) { row ->
          val rowIndex = visible.indexOf(row)
          Box(Modifier.focusProperties { canFocus = false }) {
            SbTooltip(row.repository.root) {
              SbMenuItem(
                SbMenuEntry.Item(row.repository.name, { onAction(NewTaskAction.ChooseRepository(row.repository)) }, endText = row.parent, checked = row.repository.root == draft.repository?.root),
                highlighted = index == rowIndex,
              )
            }
          }
        }
        if (visible.isEmpty() && rows.isNotEmpty()) item {
          BasicText("No repository matches \"${filter.trim()}\"", Modifier.padding(8.dp), style = SkillBillTheme.type.tSm.copy(color = colors.fgFaint))
        }
      }
    }
    state.repositoryError?.let { error -> RepositoryError(error, { actionFocused = it }) { onAction(NewTaskAction.AddRepository) } }
    Box(Modifier.fillMaxWidth().padding(horizontal = 4.dp).focusProperties { canFocus = false }) {
      SbMenuItem(SbMenuEntry.Item("Add repository", { onAction(NewTaskAction.AddRepository) }, icon = LucideIcons.plus), highlighted = index == visible.size)
    }
  }
}

@Composable
private fun BranchRows(
  modifier: Modifier,
  listing: BranchListing,
  filter: String,
  draft: NewTaskDraft,
  index: Int?,
  listState: LazyListState,
  now: () -> Instant,
  onAction: (NewTaskAction) -> Unit,
  composerFocus: FocusRequester,
) {
  val lazyIndex = branchLazyIndex(listing, index)
  LaunchedEffect(lazyIndex, listing, filter) { lazyIndex?.let { listState.animateScrollToItem(it) } }
  LazyColumn(modifier, state = listState, contentPadding = PaddingValues(horizontal = 4.dp)) {
    listing.groups.forEach { group ->
      item(key = "head-${group.head}") { BranchGroupHead(group.head) }
      items(group.rows, key = { "${group.head}-${it.name}" }) { branch ->
        val rowIndex = listing.rows.indexOfFirst { it.value == branch.name }
        BranchMenuItem(
          SbMenuEntry.Item(branch.name, { composerFocus.requestFocus(); onAction(NewTaskAction.ChooseBaseBranch(branch.name)) }, endText = savedAgo(branch.committedAt, now()), checked = draft.baseBranch == branch.name, mono = true),
          highlighted = index == rowIndex,
        )
      }
    }
  }
}

private fun branchLazyIndex(listing: BranchListing, index: Int?): Int? {
  if (index == null || listing.rows.getOrNull(index) !is BranchRow.Branch) return null
  var flatIndex = if (listing.default == null) 0 else 1
  var lazyIndex = 0
  listing.groups.forEach { group ->
    lazyIndex++
    group.rows.forEach {
      if (flatIndex == index) return lazyIndex
      flatIndex++
      lazyIndex++
    }
  }
  return null
}

@Composable
private fun BranchMenuItem(item: SbMenuEntry.Item, highlighted: Boolean) {
  Box(Modifier.fillMaxWidth().focusProperties { canFocus = false }) {
    SbMenuItem(item, highlighted = highlighted)
  }
}

@Composable
private fun BranchGroupHead(text: String) {
  val colors = SkillBillTheme.colors
  Row(Modifier.fillMaxWidth().height(28.dp).padding(start = 12.dp), verticalAlignment = Alignment.CenterVertically) {
    BasicText(text, style = SkillBillTheme.type.tXs.copy(color = colors.fgFaint), maxLines = 1)
  }
}

@Composable
private fun BranchSkeleton(modifier: Modifier) {
  Column(
    modifier.semantics { contentDescription = "Reading branches" },
    verticalArrangement = Arrangement.spacedBy(0.dp),
  ) {
    SKELETON_WIDTHS.forEach { fraction ->
      Box(Modifier.fillMaxWidth().height(30.dp).padding(horizontal = 8.dp), contentAlignment = Alignment.CenterStart) {
        SkeletonBlock(Modifier.fillMaxWidth(fraction).height(8.dp))
      }
    }
  }
}

@Composable
private fun BranchEmptyState(modifier: Modifier, title: String, description: String) {
  val colors = SkillBillTheme.colors
  Column(modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
    BasicText(title, style = SkillBillTheme.type.tSm.copy(color = colors.fg2))
    BasicText(description, style = SkillBillTheme.type.tSm.copy(color = colors.fgFaint))
  }
}

@Composable
private fun PickerFilter(text: String, placeholder: String, focus: FocusRequester, onChange: (String) -> Unit) {
  val colors = SkillBillTheme.colors
  val style = SkillBillTheme.type.tSm.copy(color = colors.fg)
  BasicTextField(
    text, onChange,
    Modifier.fillMaxWidth().height(HEAD_ROW_HEIGHT).focusRequester(focus).gatesShortcutsWhileFocused()
      .semantics { contentDescription = placeholder },
    singleLine = true, textStyle = style, cursorBrush = SolidColor(colors.fg),
    decorationBox = { field ->
      Box(Modifier.fillMaxWidth().padding(horizontal = 12.dp), contentAlignment = Alignment.CenterStart) {
        if (text.isEmpty()) BasicText(placeholder, style = style.copy(color = colors.fgFaint), maxLines = 1, softWrap = false)
        field()
      }
    },
  )
}

private fun Modifier.pickerKeys(
  highlight: PickerHighlight,
  keys: List<String>,
  preferred: String?,
  actionFocused: Boolean,
  onMove: (Int?) -> Unit = {},
  onPick: (Int) -> Unit,
): Modifier = onPreviewKeyEvent { event ->
  if (event.type != KeyEventType.KeyDown) return@onPreviewKeyEvent false
  when (event.key) {
    Key.DirectionUp -> { highlight.move(-1, keys, preferred); onMove(highlight.index(keys, preferred)); true }
    Key.DirectionDown -> { highlight.move(1, keys, preferred); onMove(highlight.index(keys, preferred)); true }
    Key.Enter, Key.NumPadEnter -> {
      if (actionFocused) return@onPreviewKeyEvent false
      highlight.index(keys, preferred)?.let(onPick)
      true
    }
    else -> false
  }
}

@Composable
private fun RepositoryError(message: String, onActionFocusChanged: (Boolean) -> Unit, onChooseAnother: () -> Unit) {
  val colors = SkillBillTheme.colors
  val linkInteraction = remember { MutableInteractionSource() }
  Column(
    Modifier.fillMaxWidth().heightIn(max = ERROR_BLOCK_MAX_HEIGHT).verticalScroll(rememberScrollState()).padding(horizontal = 12.dp, vertical = 6.dp),
    verticalArrangement = Arrangement.spacedBy(4.dp),
  ) {
    BasicText(message, Modifier.semantics { liveRegion = LiveRegionMode.Polite }, style = SkillBillTheme.type.tSm.copy(color = colors.stFailed))
    BasicText(
      "Choose another folder",
      Modifier.pointerHoverIcon(PointerIcon.Hand).onFocusChanged { onActionFocusChanged(it.isFocused) }
        .clickable(linkInteraction, indication = null, role = Role.Button, onClick = onChooseAnother)
        .sbFocusRing(linkInteraction, RoundedCornerShape(SkillBillRadius.sm)),
      style = SkillBillTheme.type.tSm.copy(color = colors.accentText),
    )
  }
}
