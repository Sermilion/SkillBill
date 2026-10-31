package dev.skillbill.desktop.feature.changes

import dev.skillbill.desktop.core.domain.model.ChangeScope
import dev.skillbill.desktop.core.domain.model.ChangeScopeTarget
import dev.skillbill.desktop.core.domain.model.ChangeSet
import dev.skillbill.desktop.core.domain.model.ChangedFile
import dev.skillbill.desktop.core.domain.model.FileDiffState
import dev.skillbill.desktop.core.domain.model.ReadProblem
import dev.skillbill.desktop.core.domain.model.ScopeSubtask
import dev.skillbill.desktop.core.domain.model.SubtaskStats
import dev.skillbill.desktop.core.domain.model.WorkItem

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
)

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
}
