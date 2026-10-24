package dev.skillbill.tray.feature.specs

import dev.skillbill.tray.core.domain.model.SpecNode
import dev.skillbill.tray.core.domain.model.SpecProject
import dev.skillbill.tray.core.domain.model.SpecSnapshot
import dev.skillbill.tray.core.domain.model.WorkIdentity

enum class SpecStatusFilter { ALL, ACTIVE, PENDING, DONE }

sealed interface SpecSnapshotState {
  data object Loading : SpecSnapshotState

  data class Loaded(val snapshot: SpecSnapshot) : SpecSnapshotState

  data class Failed(val message: String) : SpecSnapshotState
}

/**
 * One visible tree row: [expanded] is what is shown. While a filter is active that can differ from the saved expansion, both
 * because the filter opens ancestors of matches and because the user can collapse or expand such rows; neither is saved.
 */
data class SpecRow(val node: SpecNode, val depth: Int, val hasChildren: Boolean, val expanded: Boolean)

data class SpecsUiState(
  val projects: List<SpecProject> = emptyList(),
  val project: SpecProject? = null,
  /** True once the project list was read; with an empty list and no project the store is empty, while a non-empty list means a project is still being resolved. */
  val projectsLoaded: Boolean = false,
  val snapshot: SpecSnapshotState = SpecSnapshotState.Loading,
  val filterText: String = "",
  val statusFilter: SpecStatusFilter = SpecStatusFilter.ALL,
  val expandedPaths: Set<String> = emptySet(),
  val rows: List<SpecRow> = emptyList(),
  val selectedPath: String? = null,
  /** Keyed by the file's `absolutePath`, the value [SpecsEffect.OpenFile] carries. */
  val openErrors: Map<String, String> = emptyMap(),
  val paneOpen: Boolean = false,
  val anyInProgress: Boolean = false,
) {
  val filtersActive: Boolean get() = filtersActive(filterText, statusFilter)
}

internal fun filtersActive(text: String, status: SpecStatusFilter): Boolean = text.isNotBlank() || status != SpecStatusFilter.ALL

sealed interface SpecsAction {
  /** The sidebar's selection, read-only: the pane follows it until the user picks a project. */
  data class SelectedWorkChanged(val identity: WorkIdentity?) : SpecsAction

  data class PickProject(val projectId: String) : SpecsAction

  data class FilterChanged(val text: String) : SpecsAction

  data class ChooseStatus(val filter: SpecStatusFilter) : SpecsAction

  data object ClearFilters : SpecsAction

  data class Expand(val path: String) : SpecsAction

  data class Collapse(val path: String) : SpecsAction

  data class Select(val path: String) : SpecsAction

  /** Click or Enter: a folder or bundle is selected and toggled, a file is selected and opened. */
  data class Activate(val path: String) : SpecsAction

  data object CollapseAll : SpecsAction

  data class FileOpenFailed(val absolutePath: String, val message: String) : SpecsAction

  data object Retry : SpecsAction

  data object Reveal : SpecsAction

  data object TogglePane : SpecsAction

  data object ClosePane : SpecsAction
}

/** Nothing here opens a detail or names a navigator: the pane never changes what the main panel shows. */
sealed interface SpecsEffect {
  data class OpenFile(val absolutePath: String) : SpecsEffect

  data class RevealDirectory(val path: String) : SpecsEffect
}
