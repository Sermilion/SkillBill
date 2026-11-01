package dev.skillbill.desktop.feature.changes

import dev.skillbill.desktop.core.domain.model.ChangeScope
import dev.skillbill.desktop.core.domain.model.ChangeScopeTarget
import dev.skillbill.desktop.core.domain.model.ChangeSet
import dev.skillbill.desktop.core.domain.model.ChangedFile
import dev.skillbill.desktop.core.domain.model.FileDiffState
import dev.skillbill.desktop.core.domain.model.OutsideCommit
import dev.skillbill.desktop.core.domain.model.ReadProblem
import dev.skillbill.desktop.core.domain.model.ScopeChangesState
import dev.skillbill.desktop.core.domain.model.ScopeKind
import dev.skillbill.desktop.core.domain.model.ScopeSubtask
import dev.skillbill.desktop.core.domain.model.SubtaskStats
import dev.skillbill.desktop.core.domain.model.WorkItem
import dev.skillbill.desktop.core.domain.model.availableScopes

data class ChangesWindowUiState(
  val item: WorkItem,
  val content: WindowContent = WindowContent.Loading,
  val scope: ChangeScope,
  val filter: String = "",
  val subtasks: List<ScopeSubtask> = emptyList(),
  val stats: Map<Int, SubtaskStats> = emptyMap(),
  val list: ScopeListUi = ScopeListUi.Loading,
  val groups: List<FileGroupUi> = emptyList(),
  val selectedPath: String? = null,
  val diff: DiffPaneUi = DiffPaneUi.NoSelection,
  /** The subtask a Shift-click extends from. */
  val anchor: Int? = null,
  val pickerOpen: Boolean = false,
  val pickerQuery: String = "",
  /** The latest settled All list, for the All button's count; null until All has been read. */
  val allRead: ScopeListUi? = null,
  val outsideRead: ScopeChangesState? = null,
  val uncommittedRead: ScopeChangesState? = null,
  /** Why the last open request failed; cleared when the selection changes. */
  val openProblem: String? = null,
) {
  val target: ChangeScopeTarget? get() = (content as? WindowContent.Ready)?.target
  val subtasksAvailable: Boolean get() = target?.let { ScopeKind.Subtasks in availableScopes(it) } == true
  val outsideCommits: List<OutsideCommit> get() = (outsideRead as? ScopeChangesState.Listed)?.outsideCommits.orEmpty()
  val visibleFiles: List<ChangedFile> get() = groups.flatMap { group -> group.rows.map { it.file } }
  val selectedFile: ChangedFile? get() = visibleFiles.firstOrNull { it.path == selectedPath }
  val quick: List<QuickButtonUi> get() = quickButtons(this)
}

enum class QuickScope { All, Running, Outside, Uncommitted }

/** A quick scope button; [count] is null before its read lands. */
data class QuickButtonUi(val kind: QuickScope, val label: String, val count: Int?, val enabled: Boolean, val pressed: Boolean, val running: Boolean = false)

sealed interface WindowContent {
  data object Loading : WindowContent
  data class Ready(val target: ChangeScopeTarget) : WindowContent
  data object Missing : WindowContent
  data object NoBaseline : WindowContent
  data class Problem(val problem: ReadProblem) : WindowContent
}

sealed interface ScopeListUi {
  data object Loading : ScopeListUi
  data class Listed(val changes: ChangeSet) : ScopeListUi
  data class BaseUnavailable(val reason: String) : ScopeListUi
  data class NotARepository(val worktreeRoot: String) : ScopeListUi
  data class TooManyCommits(val limit: Int) : ScopeListUi
  data class Unavailable(val problem: ReadProblem) : ScopeListUi
}

sealed interface DiffPaneUi {
  data object NoSelection : DiffPaneUi
  data class Loading(val file: ChangedFile) : DiffPaneUi
  data class Shown(val file: ChangedFile, val state: FileDiffState, val chips: List<SubtaskChipUi>) : DiffPaneUi
}

data class SubtaskChipUi(val id: Int, val name: String, val inScope: Boolean, val selectable: Boolean)

sealed interface ChangesWindowAction {
  data class SetFilter(val text: String) : ChangesWindowAction
  data class SelectFile(val path: String) : ChangesWindowAction
  data class NarrowToSubtask(val id: Int) : ChangesWindowAction

  /** A strip column or picker row; [extend] (Shift) grows the range from the anchor, and only a plain picker pick closes it. */
  data class PickSubtask(val id: Int, val extend: Boolean, val fromPicker: Boolean) : ChangesWindowAction
  data class DragSubtasks(val fromId: Int, val toId: Int) : ChangesWindowAction
  data class SelectQuickScope(val kind: QuickScope) : ChangesWindowAction
  data class Step(val forward: Boolean) : ChangesWindowAction
  data object TogglePicker : ChangesWindowAction
  data object OpenPicker : ChangesWindowAction
  data object ClosePicker : ChangesWindowAction
  data class PickerQuery(val text: String) : ChangesWindowAction
  data object PickAll : ChangesWindowAction
  data class MoveFile(val delta: Int) : ChangesWindowAction
  data object OpenSelectedFile : ChangesWindowAction
}
