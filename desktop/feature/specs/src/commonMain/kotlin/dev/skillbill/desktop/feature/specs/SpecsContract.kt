package dev.skillbill.desktop.feature.specs

import dev.skillbill.desktop.core.domain.model.SpecControlState
import dev.skillbill.desktop.core.domain.model.SpecNode
import dev.skillbill.desktop.core.domain.model.SpecProject
import dev.skillbill.desktop.core.domain.model.SpecTreeState

enum class SpecStatusFilter { ALL, ACTIVE, PENDING, DONE }

data class SpecRow(val node: SpecNode, val depth: Int, val hasChildren: Boolean, val expanded: Boolean)

sealed interface ToolbarSelection {
  data object None : ToolbarSelection
  data class FileInBundle(val key: String) : ToolbarSelection
  data class Bundle(val path: String) : ToolbarSelection
}

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
  val filtersActive: Boolean = false,
)

data class SpecToolbarState(
  val selection: ToolbarSelection = ToolbarSelection.None,
  val control: SpecControlState? = null,
  val bundleKey: String? = null,
  val bundleTitle: String? = null,
  val chosenAgent: String? = null,
  val agentMenuOpen: Boolean = false,
  val confirmingCancel: Boolean = false,
  val purgeDialogOpen: Boolean = false,
  val inFlight: Boolean = false,
  val reserved: Boolean = false,
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
  data object CollapseAll : SpecsAction
  data object Retry : SpecsAction
  data object Reveal : SpecsAction
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

internal const val HINT_SELECT_FOLDER = "Select a spec folder to start, pause or cancel it."
internal const val PAUSE_NOTICE = "Pause recorded. The goal stops after its current subtask."
internal const val PURGE_DISABLED_TOOLTIP = "Pause or cancel the goal before purging"
internal const val NO_CHECKOUT = "This project has no checkout on this machine."
internal const val NO_WORK_ITEM = "This run has no work item."
internal const val NO_SPECS = "No specs yet"
internal const val NO_SPECS_BODY = "Plan a feature with /skill-bill phase:plan and its bundle shows up here."
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
internal const val PURGE_INTRO = "Purge deletes everything Skill Bill recorded while running this spec, so it can start again from scratch."
internal val PURGE_DELETED = listOf("Goal and subtask run history", "Run evidence and task tracking", "Checkpoint refs", "Subtask progress, back to not started")
internal val PURGE_KEPT = listOf("Every spec file in the folder", "Your branch and its commits", "Open pull requests")
internal const val PURGE_WARNING = "This can’t be undone. Afterwards the spec shows as not started and Start runs it from the first subtask."

internal fun fileHint(key: String) = "Actions run a whole spec folder, not one subtask. Select $key to control it."
internal fun cancelPrompt(key: String) = "Cancel $key? The run stops and the goal is marked canceled. Its branch and commits stay."
internal fun purgeLabel(key: String) = "Purge $key run history"
internal fun startFailure(summary: String) = "Couldn't start: $summary"
internal fun purgeFailure(summary: String) = "Couldn't purge: $summary"
