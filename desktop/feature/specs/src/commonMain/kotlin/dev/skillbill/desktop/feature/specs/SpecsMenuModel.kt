package dev.skillbill.desktop.feature.specs

import androidx.compose.ui.graphics.vector.ImageVector
import dev.skillbill.desktop.core.designsystem.icon.LucideIcons
import dev.skillbill.desktop.core.domain.model.SpecControlState
import dev.skillbill.desktop.core.domain.model.SpecNode

internal sealed interface SpecMenuAction {
  data object Separator : SpecMenuAction
  data class Item(
    val label: String,
    val action: SpecsAction,
    val icon: ImageVector,
    val shortcut: String? = null,
    val enabled: Boolean = true,
    val reason: String? = null,
    val danger: Boolean = false,
  ) : SpecMenuAction
}

internal fun specMenuActions(bundle: SpecNode.Bundle, state: SpecsUiState): List<SpecMenuAction> {
  val selected = (state.toolbar.selection as? ToolbarSelection.Bundle)?.path == bundle.path
  val controlState = state.toolbar.control
  val loaded = (controlState as? SpecControlState.Loaded)?.takeIf { selected }
  val control = loaded?.control
  val liveGoal = loaded?.liveGoal != null
  val key = bundle.issueKey
  fun item(
    label: String,
    action: SpecsAction,
    icon: ImageVector,
    shortcut: String? = null,
    enabled: Boolean = true,
    reason: String? = null,
    danger: Boolean = false,
  ) = SpecMenuAction.Item(label, action, icon, shortcut, enabled, reason, danger)

  val actions = mutableListOf<SpecMenuAction>()
  val runActions = mutableListOf<SpecMenuAction.Item>()
  val destructiveActions = mutableListOf<SpecMenuAction.Item>()
  if (bundle.children.any { it is SpecNode.File && it.kind == dev.skillbill.desktop.core.domain.model.SpecFileKind.ParentSpec }) {
    actions += item("Open spec", SpecsAction.OpenSelectedSpec, LucideIcons.fileText, shortcut = "↵")
  }
  actions += item("Reveal folder", SpecsAction.RevealBundle, LucideIcons.folder)
  if (key != null) actions += item("Copy key", SpecsAction.CopyBundleKey, LucideIcons.copy)
  actions += item(
    if (state.toolbar.favorite) "Remove favorite" else "Add favorite",
    SpecsAction.ToggleFavorite,
    LucideIcons.star,
    shortcut = "F",
    enabled = state.toolbar.favoriteAvailable && !state.toolbar.favoriteInFlight,
  )
  if (key != null && !state.project?.repoRoot.isNullOrBlank()) {
    runActions += item("Export work", SpecsAction.RequestExport, LucideIcons.file)
  }
  if (control != null) {
    if (control.canStart) {
      runActions += item(control.startLabel, SpecsAction.Start, LucideIcons.play, shortcut = "R", enabled = !state.toolbar.inFlight && !state.toolbar.reserved)
    }
    if (control.pauseRequested) {
      runActions += item("Pause requested", SpecsAction.Pause, LucideIcons.pause, enabled = false, reason = PAUSE_NOTICE)
    } else if (control.canPause && liveGoal) {
      runActions += item("Pause after subtask", SpecsAction.Pause, LucideIcons.pause, shortcut = "P", enabled = !state.toolbar.inFlight)
    }
    if (control.canStart) {
      val chosenAgent = state.toolbar.chosenAgent
      val agentLabel = chosenAgent?.let { "Change agent, now $it" } ?: "Choose agent"
      runActions += item(agentLabel, SpecsAction.OpenAgentMenu, LucideIcons.bot, shortcut = "A", enabled = !state.toolbar.inFlight && !state.toolbar.reserved)
    }
  }
  if (control?.canCancel == true) {
    destructiveActions += item("Cancel goal…", SpecsAction.RequestCancel, LucideIcons.x, enabled = !state.toolbar.inFlight, danger = true)
  }
  if (control?.purgeVisible == true) {
    destructiveActions += item(
      "Purge run history…",
      SpecsAction.RequestPurge,
      LucideIcons.reset,
      enabled = control.canPurge && !state.toolbar.inFlight,
      reason = if (control.canPurge) null else control.unavailableReason ?: PURGE_DISABLED_TOOLTIP,
      danger = true,
    )
  }
  val reserved = key != null && key in state.reservations
  val deleteReason = when {
    reserved -> DELETE_LAUNCHING
    control?.canDelete == false -> control.unavailableReason ?: DELETE_LIVE
    controlState is SpecControlState.Unavailable -> dev.skillbill.desktop.core.ui.feedback.readProblemText(controlState.problem)
    control == null -> "Run status is loading"
    else -> null
  }
  destructiveActions += item(
    DELETE_SPEC_FILES,
    SpecsAction.RequestDelete(bundle.path),
    LucideIcons.trash,
    enabled = !state.deleting && !reserved && control?.canDelete != false && controlState !is SpecControlState.Unavailable && control != null,
    reason = deleteReason,
    danger = true,
  )
  if (runActions.isNotEmpty()) {
    actions += SpecMenuAction.Separator
    actions += runActions
  }
  actions += SpecMenuAction.Separator
  actions += destructiveActions
  return actions
}
