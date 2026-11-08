package dev.skillbill.desktop.feature.detail

import dev.skillbill.desktop.core.domain.model.ActivityResult
import dev.skillbill.desktop.core.domain.model.ChangeBaseline
import dev.skillbill.desktop.core.domain.model.ChangeScope
import dev.skillbill.desktop.core.domain.model.ChangesState
import dev.skillbill.desktop.core.domain.model.Detail
import dev.skillbill.desktop.core.domain.model.DetailList
import dev.skillbill.desktop.core.domain.model.QaPreviewRequest
import dev.skillbill.desktop.core.domain.model.QaPreviewState
import dev.skillbill.desktop.core.domain.model.ReadProblem
import dev.skillbill.desktop.core.domain.model.ReviewFindingsAction
import dev.skillbill.desktop.core.domain.model.RunOptions
import dev.skillbill.desktop.core.domain.model.SubtaskScopesState
import dev.skillbill.desktop.core.domain.model.WorkItem
import dev.skillbill.desktop.core.domain.settings.CriterionMark
import dev.skillbill.desktop.core.ui.feedback.ToastMessage
import dev.skillbill.desktop.core.ui.navigation.AppRoute
import kotlin.time.Instant

/**
 * One open detail. [header] is the route's row until the first read replaces it, so it paints in the first frame. [stale] means the
 * last refresh failed and [sections] are the previous results; [renderedAt] is when the shown read arrived, for relative ages.
 */
data class DetailUiState(
  val header: WorkItem,
  val sections: SectionsUi,
  val stale: Boolean,
  val renderedAt: Instant,
  val itemResolved: Boolean = true,
  val favorite: Boolean = false,
  val favoriteAvailable: Boolean = false,
  val favoriteInFlight: Boolean = false,
  val listLoads: Map<DetailList, ListLoad> = emptyMap(),
  val boardPosition: BoardPosition? = null,
  val controls: ControlsUiState = ControlsUiState(),
  val subtaskChoice: SubtaskChoice = SubtaskChoice(),
  val phaseChoice: Map<PhaseChoiceKey, String> = emptyMap(),
  /** The previous detail was left by J or K while its Subtasks list had focus, so this one's list takes focus once it shows. */
  val focusSubtaskList: Boolean = false,
  val changes: ChangesUi = ChangesUi(),
  val qaCaseOverrides: Map<QaCaseKey, Boolean> = emptyMap(),
  val validationCheckOverrides: Map<ValidationCheckKey, Boolean> = emptyMap(),
  val selectedQaRun: String? = null,
  val qaPreviewVisible: Boolean = false,
  val qaPreview: QaPreviewState = QaPreviewState.Ended,
  internal val qaPreviewRequest: QaPreviewRequest? = null,
  internal val qaPreviewGeneration: Long = 0,
  val activity: ActivityResult = ActivityResult.Loading,
  val verifyCriteriaFilter: VerifyCriteriaFilter = VerifyCriteriaFilter.All,
  val fixSelection: Map<String, FixRoute> = emptyMap(),
  val criterionMarks: Map<Int, CriterionMark> = emptyMap(),
  val verifyAgainInFlight: Boolean = false,
)

enum class VerifyCriteriaFilter { All, Gaps, Met }

/**
 * The open item's changes; the section only shows once the detail has loaded. A goal carries its [subtasks] for the strip; any other
 * item carries its change summary in [state] against [baseline].
 */
data class ChangesUi(
  val baseline: ChangeBaseline? = null,
  val state: ChangesState = ChangesState.Loading,
  val subtasks: SubtaskScopesState? = null,
)

/** A row of the Subtasks index: one subtask by its runtime id, or the final Goal finish row. */
sealed interface SubtaskKey {
  data class Subtask(val id: Int) : SubtaskKey

  data object GoalFinish : SubtaskKey
}

/**
 * The reader's subtask selection for one item, UI state only. A null [key] means the default selection; [expanded] folds the
 * inline inspector that the selected row shows while the details pane is hidden.
 */
data class SubtaskChoice(val key: SubtaskKey? = null, val expanded: Boolean = true)

data class PhaseChoiceKey(val itemId: String, val subtask: SubtaskKey)

sealed interface SectionsUi {
  data object Pending : SectionsUi

  data class Loaded(
    val detail: Detail,
    val runOptions: RunOptions?,
    val listAnchors: Map<DetailList, String>,
    val moreLists: Set<DetailList>,
  ) : SectionsUi

  data object Gone : SectionsUi

  data class Failed(val problem: ReadProblem) : SectionsUi
}

/** One list's page request for [anchor]; a new anchor means a new page. */
sealed interface ListLoad {
  val anchor: String?

  data class Loading(override val anchor: String?) : ListLoad

  data class Done(override val anchor: String?) : ListLoad

  data class Failed(override val anchor: String?, val problem: ReadProblem) : ListLoad
}

/** Where the open item sits in the board's order, for J, K and the pager. */
data class BoardPosition(val index: Int, val total: Int, val previous: WorkItem?, val next: WorkItem?)

data class QaCaseKey(val invocationId: String, val caseId: String)
data class ValidationCheckKey(val invocationId: String, val checkId: String)

sealed interface DetailAction {
  data class OpenNewerVerifyRun(val workflowId: String) : DetailAction
  data class SetVerifyCriteriaFilter(val filter: VerifyCriteriaFilter) : DetailAction
  data class ToggleFix(val key: String) : DetailAction
  data class SetFixRoute(val key: String, val route: FixRoute) : DetailAction
  data class ToggleSelectAllFixes(val kind: FixKind) : DetailAction
  data object ClearFixSelection : DetailAction
  data object FixInChat : DetailAction
  data object StartGoalWithFixes : DetailAction
  data class GoalIntakeResult(val keys: List<String>, val accepted: Boolean) : DetailAction
  data class MarkCriterion(val number: Int, val mark: CriterionMark?) : DetailAction
  data object VerifyAgain : DetailAction
  data object ToggleFavorite : DetailAction
  data class ToggleQaCase(val key: QaCaseKey) : DetailAction
  data class ToggleValidationCheck(val key: ValidationCheckKey) : DetailAction
  data class SetAllQaCases(val keys: List<QaCaseKey>, val open: Boolean) : DetailAction
  data class SelectQaRun(val invocationId: String?) : DetailAction
  data class SetQaPreviewVisible(val visible: Boolean) : DetailAction
  data class OpenQaReport(val path: String) : DetailAction
  data class OpenQaEvidence(val path: String, val invocationDirectory: String) : DetailAction
  /** Opens .skill-bill/phases/qa.md without writing it. */
  data object OpenQaPhaseInstructions : DetailAction
  data class ReviewFindings(val action: ReviewFindingsAction) : DetailAction
  data class LoadMore(val list: DetailList) : DetailAction
  data object Next : DetailAction
  data object Previous : DetailAction
  data object Retry : DetailAction
  data class Copy(val text: String) : DetailAction
  data object RevealFolder : DetailAction
  data object OpenBranch : DetailAction
  data class OpenSpecFile(val path: String) : DetailAction
  data class Navigate(val route: AppRoute) : DetailAction

  /** Selects [key]; with [inline] (the details pane hidden) selecting the selected row again folds or unfolds it. */
  data class SelectSubtask(val key: SubtaskKey, val inline: Boolean = false) : DetailAction

  /** Selects a phase for one subtask of the named item. */
  data class SelectPhase(val itemId: String, val key: SubtaskKey, val phaseId: String) : DetailAction

  /** ↑ or ↓ in the focused Subtasks index. */
  data class MoveSubtaskSelection(val delta: Int) : DetailAction

  /** Enter in the focused Subtasks index: opens the selected subtask's spec file; Goal finish has none. */
  data object OpenSelectedSubtaskSpec : DetailAction

  /** The Subtasks index took focus, which spends [DetailUiState.focusSubtaskList]. */
  data object SubtaskListFocused : DetailAction

  /** Opens the Changes window on [scope]; a subtask scope opens only for a subtask that can be selected. */
  data class OpenChangesWindow(val scope: ChangeScope = ChangeScope.All) : DetailAction
}

sealed interface DetailEffect {
  data class ReviewFindings(val item: WorkItem, val action: ReviewFindingsAction) : DetailEffect
  data class FixInChat(val item: WorkItem, val brief: String) : DetailEffect
  data class PrefillGoalIntake(val repoRoot: String, val branch: String, val text: String, val keys: List<String>) : DetailEffect
  data class OpenItem(val item: WorkItem) : DetailEffect
  data class OpenRoute(val route: AppRoute) : DetailEffect
  data class CopyToClipboard(val text: String) : DetailEffect
  data class ShowToast(val message: ToastMessage) : DetailEffect
  data class OpenChangesWindow(val item: WorkItem, val scope: ChangeScope = ChangeScope.All) : DetailEffect
}
