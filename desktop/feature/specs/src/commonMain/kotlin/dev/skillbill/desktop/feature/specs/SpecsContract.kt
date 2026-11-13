package dev.skillbill.desktop.feature.specs

import dev.skillbill.desktop.core.domain.model.SpecControlState
import dev.skillbill.desktop.core.domain.model.SpecNode
import dev.skillbill.desktop.core.domain.model.SpecProject
import dev.skillbill.desktop.core.domain.model.SpecTreeState

enum class SpecStatusFilter { ALL, ACTIVE, PENDING, DONE }

data class SpecRow(val node: SpecNode, val depth: Int, val hasChildren: Boolean, val expanded: Boolean)

sealed interface ToolbarSelection {
  data object None : ToolbarSelection
  data class Bundle(val path: String) : ToolbarSelection
}

/** The bundle a Delete spec files confirmation names: its key and title, and the folder that will be removed. */
data class SpecDeleteTarget(val path: String, val issueKey: String?, val title: String, val folder: String)

data class SpecsUiState(
  val projects: List<SpecProject> = emptyList(),
  val project: SpecProject? = null,
  val projectsLoaded: Boolean = false,
  val tree: SpecTreeState = SpecTreeState.Loading,
  val storePath: String? = null,
  val filterText: String = "",
  val statusFilter: SpecStatusFilter = SpecStatusFilter.ALL,
  val rows: List<SpecRow> = emptyList(),
  val selectedPath: String? = null,
  val projectMenuOpen: Boolean = false,
  val toolbar: SpecToolbarState = SpecToolbarState(),
  val reservations: Set<String> = emptySet(),
  val filtersActive: Boolean = false,
  val rowMenuPath: String? = null,
  val rowMenuAnchorPath: String? = null,
  val deleteTarget: SpecDeleteTarget? = null,
  val deleting: Boolean = false,
  val importDialog: ImportConfirmation? = null,
  val panelMenuOpen: Boolean = false,
  val barMenuOpen: Boolean = false,
  val searchOpen: Boolean = false,
)

data class ImportConfirmation(
  val archivePath: String,
  val fingerprint: String,
  val issueKey: String,
  val featureBranch: String,
  val commitSha: String,
)

data class SpecToolbarState(
  val selection: ToolbarSelection = ToolbarSelection.None,
  val control: SpecControlState? = null,
  val bundleKey: String? = null,
  val bundleTitle: String? = null,
  val subtasksDone: Int? = null,
  val subtasksTotal: Int? = null,
  val chosenAgent: String? = null,
  val agentMenuOpen: Boolean = false,
  val confirmingCancel: Boolean = false,
  val purgeDialogOpen: Boolean = false,
  val exportLiveDialogOpen: Boolean = false,
  val inFlight: Boolean = false,
  val reserved: Boolean = false,
  val favorite: Boolean = false,
  val favoriteAvailable: Boolean = false,
  val favoriteInFlight: Boolean = false,
)

sealed interface SpecsAction {
  data class PickProject(val projectId: String) : SpecsAction
  data object OpenProjectMenu : SpecsAction
  data object DismissProjectMenu : SpecsAction
  data class FilterChanged(val text: String) : SpecsAction
  data class ChooseStatus(val filter: SpecStatusFilter) : SpecsAction
  data object ClearFilters : SpecsAction
  data class Expand(val path: String) : SpecsAction
  data class Collapse(val path: String) : SpecsAction
  data class Toggle(val path: String) : SpecsAction
  data class Select(val path: String) : SpecsAction
  data class Activate(val path: String) : SpecsAction
  data class Click(val path: String) : SpecsAction
  data object Deselect : SpecsAction
  data object CollapseAll : SpecsAction
  data object Retry : SpecsAction
  data object Reveal : SpecsAction
  data object RevealBundle : SpecsAction
  data object OpenPanelMenu : SpecsAction
  data object DismissPanelMenu : SpecsAction
  data object OpenBarMenu : SpecsAction
  data object DismissBarMenu : SpecsAction
  data object OpenSearch : SpecsAction
  data object CloseSearch : SpecsAction
  data object OpenSelectedSpec : SpecsAction
  data object CopyBundleKey : SpecsAction
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
  data object RequestExport : SpecsAction
  data object DismissExportLive : SpecsAction
  data object RequestImport : SpecsAction
  data object DismissImport : SpecsAction
  data object ConfirmImport : SpecsAction
  data class OpenRowMenu(val path: String) : SpecsAction
  data object DismissRowMenu : SpecsAction
  data class RequestDelete(val path: String) : SpecsAction
  data object KeepSpecFiles : SpecsAction
  data object ConfirmDelete : SpecsAction
  data object ToggleFavorite : SpecsAction
}

internal const val PAUSE_NOTICE = "Pause recorded. The goal stops after its current subtask."
internal const val PURGE_DISABLED_TOOLTIP = "Pause or cancel the goal before purging"
internal const val NO_CHECKOUT = "This project has no checkout on this machine."
internal const val NO_WORK_ITEM = "This run has no work item."
internal const val LOADING_SPECS = "Loading specs"
internal const val NO_SPECS = "No specs yet"
internal const val NO_SPECS_BODY = "Plan a feature with /sona phase:plan and its bundle shows up here."
internal const val NO_MATCH = "No specs match"
internal const val NO_MATCH_BODY = "Nothing in this project matches the filter and status."
internal const val SPECS_UNAVAILABLE = "Specs unavailable"
internal const val CHOOSE_PROJECT = "Choose project"
internal const val COLLAPSE_ALL = "Collapse all"
internal const val CLOSE_SPECS = "Close specs pane"
internal const val FILTER_HINT = "Filter by key, title or file"
internal const val KEEP_RUNNING = "Keep running"
internal const val CANCEL_GOAL = "Cancel goal"
internal const val KEEP_RUN_HISTORY = "Keep run history"
internal const val RUN_ON = "Run on"
internal const val PURGE_INTRO = "Purge deletes everything Sona recorded while running this spec, so it can start again from scratch."
internal val PURGE_DELETED = listOf("Goal and subtask run history", "Run evidence and task tracking", "Checkpoint refs", "Subtask progress, back to not started")
internal val PURGE_KEPT = listOf("Every spec file in the folder", "Your branch and its commits", "Open pull requests")
internal const val PURGE_WARNING = "This can’t be undone. Afterwards the spec shows as not started and Start runs it from the first subtask."

internal const val DELETE_SPEC_FILES = "Delete spec files…"
internal const val KEEP_SPEC_FILES = "Keep spec files"
internal const val DELETE_INTRO = "This removes the spec folder and every file in it from disk."
internal const val DELETE_KEEPS = "Run history stays in the database. Use Purge to remove it."
internal const val DELETE_WARNING = "This can’t be undone."
internal const val DELETE_LAUNCHING = "A launch for this spec is in flight. Wait for it before deleting the files."
internal const val DELETE_LIVE = "Pause or cancel the goal first"

internal fun cancelPrompt(key: String) = "Cancel $key?"
internal fun purgeLabel(key: String) = "Purge $key run history"
internal fun startFailure(summary: String) = "Couldn't start: $summary"
internal fun purgeFailure(summary: String) = "Couldn't purge: $summary"
internal fun deleteFailure(summary: String) = "Couldn't delete spec files: $summary"
internal fun deleteTitle(target: SpecDeleteTarget) = "Delete ${target.issueKey ?: target.title} spec files?"
