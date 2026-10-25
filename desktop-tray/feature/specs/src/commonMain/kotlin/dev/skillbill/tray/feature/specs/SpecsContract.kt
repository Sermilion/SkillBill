package dev.skillbill.tray.feature.specs

import dev.skillbill.tray.core.domain.model.SpecBundleControl
import dev.skillbill.tray.core.domain.model.SpecNode
import dev.skillbill.tray.core.domain.model.SpecProject
import dev.skillbill.tray.core.domain.model.SpecSnapshot
import dev.skillbill.tray.core.domain.model.WorkIdentity
import dev.skillbill.tray.core.ui.Feedback

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
  val pendingFeedback: List<Feedback> = emptyList(),
  val paneOpen: Boolean = false,
  val anyInProgress: Boolean = false,
  val toolbar: SpecToolbarState = SpecToolbarState(),
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

  /** Enter: a folder or bundle is selected and toggled, a file is selected and opened. */
  data class Activate(val path: String) : SpecsAction

  /** A row click: a folder or bundle is selected, a file is selected and opened. */
  data class Click(val path: String) : SpecsAction

  /** The row's arrow: a folder or bundle expands or collapses, and the selection stays where it is. */
  data class Toggle(val path: String) : SpecsAction

  data object CollapseAll : SpecsAction

  data class FileOpenFailed(val absolutePath: String, val message: String) : SpecsAction

  data class AcknowledgeFeedback(val id: Long) : SpecsAction

  data object Retry : SpecsAction

  data object Reveal : SpecsAction

  data object TogglePane : SpecsAction

  data object ClosePane : SpecsAction

  data object OpenAgentMenu : SpecsAction

  data object DismissAgentMenu : SpecsAction

  data class ChooseAgent(val agentId: String) : SpecsAction

  data object Start : SpecsAction

  data object Pause : SpecsAction

  data object RequestCancel : SpecsAction

  data object KeepRunning : SpecsAction

  data object ConfirmCancel : SpecsAction

  data object RequestPurge : SpecsAction

  data object KeepRunHistory : SpecsAction

  data object ConfirmPurge : SpecsAction
}

/** What the toolbar acts on, derived from the selected tree row. [None] covers nothing, a plain folder and a file outside any bundle. */
sealed interface ToolbarSelection {
  data object None : ToolbarSelection

  /** [key] is the containing bundle's `issueKey`, or its name when it has none. */
  data class FileInBundle(val key: String) : ToolbarSelection

  data class Bundle(val path: String) : ToolbarSelection
}

sealed interface ControlLoad {
  data object Idle : ControlLoad

  data object Loading : ControlLoad

  data class Ready(val control: SpecBundleControl) : ControlLoad

  data class Failed(val message: String) : ControlLoad
}

/** The run-actions toolbar: every enable flag comes from [control], the runtime's verdict; nothing here judges a lifecycle. */
data class SpecToolbarState(
  val selection: ToolbarSelection = ToolbarSelection.None,
  val control: ControlLoad = ControlLoad.Idle,
  val chosenAgent: String? = null,
  val agentMenuOpen: Boolean = false,
  val confirmingCancel: Boolean = false,
  val purgeDialogOpen: Boolean = false,
  val inFlight: Boolean = false,
)

internal const val HINT_SELECT_FOLDER = "Select a spec folder to start, pause or cancel it."
internal const val PAUSE_NOTICE = "Pause recorded. The goal stops after its current subtask."
internal const val PURGE_DISABLED_TOOLTIP = "Pause or cancel the goal before purging"
internal const val NO_CHECKOUT = "This project has no checkout on this machine."
internal const val NO_WORK_ITEM = "This run has no work item."

internal fun fileHint(key: String) = "Actions run a whole spec folder, not one subtask. Select $key to control it."

internal fun cancelPrompt(key: String) = "Cancel $key? The run stops and the goal is marked canceled. Its branch and commits stay."

internal fun purgeLabel(key: String) = "Purge $key run history"

internal fun startFailure(summary: String) = "Couldn't start: $summary"

internal fun purgeFailure(summary: String) = "Couldn't purge: $summary"

/** Nothing here opens a detail or names a navigator: the pane never changes what the main panel shows. */
sealed interface SpecsEffect {
  data class OpenFile(val absolutePath: String) : SpecsEffect

  data class RevealDirectory(val path: String) : SpecsEffect
}
