package dev.skillbill.desktop.feature.changes

import dev.skillbill.desktop.core.domain.model.ChangeScope
import dev.skillbill.desktop.core.domain.model.CommentState
import dev.skillbill.desktop.core.domain.model.CommentSide
import dev.skillbill.desktop.core.domain.model.DiffCommentsState
import dev.skillbill.desktop.core.domain.model.ChangeScopeTarget
import dev.skillbill.desktop.core.domain.model.ChangeSet
import dev.skillbill.desktop.core.domain.model.ChangedFile
import dev.skillbill.desktop.core.domain.model.FileDiffState
import dev.skillbill.desktop.core.domain.model.ReadProblem
import dev.skillbill.desktop.core.domain.model.ScopeChangesState
import dev.skillbill.desktop.core.domain.model.ScopeKind
import dev.skillbill.desktop.core.domain.model.ScopeSubtask
import dev.skillbill.desktop.core.domain.model.SubtaskStats
import dev.skillbill.desktop.core.domain.model.availableScopes

data class ChangesWindowUiState(
  val title: String,
  val running: Boolean = false,
  val content: WindowContent = WindowContent.Loading,
  val scope: ChangeScope,
  val view: ChangesView = ChangesView.Subtasks,
  val filter: String = "",
  val subtasks: List<ScopeSubtask> = emptyList(),
  val stats: Map<Int, SubtaskStats> = emptyMap(),
  val commitStats: Map<String, SubtaskStats> = emptyMap(),
  val commitAnchor: String? = null,
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
  val operatorRead: ScopeChangesState? = null,
  val uncommittedRead: ScopeChangesState? = null,
  /** The diffs loaded for this scope by path; each is good for the [ChangedFile] it was read for. */
  val fileDiffs: Map<String, LoadedDiff> = emptyMap(),
  /** Grows when the selection should scroll the diff stream to the selected file; scrolling the stream itself does not grow it. */
  val scrollRequest: Int = 0,
  /** Why the last open request failed; cleared when the selection changes. */
  val openProblem: String? = null,
  val comments: DiffCommentsState = DiffCommentsState.Loading,
  val threadUi: Map<String, ThreadUi> = emptyMap(),
  val composer: CommentComposerUi? = null,
  val editing: CommentEditUi? = null,
  val review: ReviewBarUi = ReviewBarUi(),
  val reviewToast: String? = null,
) {
  val target: ChangeScopeTarget? get() = (content as? WindowContent.Ready)?.target
  val commits get() = target?.timeline?.commits.orEmpty()
  val chatScopes: Boolean get() = target?.chat == true
  val subtasksAvailable: Boolean get() = target?.let { ScopeKind.Subtasks in availableScopes(it) } == true
  val visibleFiles: List<ChangedFile> get() = groups.flatMap { group -> group.rows.map { it.file } }
  val selectedFile: ChangedFile? get() = visibleFiles.firstOrNull { it.path == selectedPath }
  val quick: List<QuickButtonUi> get() = quickButtons(this)
  val pendingCount: Int get() = (comments as? DiffCommentsState.Available)?.threads?.count { it.state == CommentState.Pending } ?: 0
  val canAuthor: Boolean get() = (comments as? DiffCommentsState.Available)?.canAuthor == true
  val authorHint: String? get() = when (val value = comments) {
    is DiffCommentsState.Available -> if (value.canAuthor) null else "Check out ${value.branch} to comment"
    is DiffCommentsState.Unavailable -> value.reason
    DiffCommentsState.Loading -> null
  }
}

data class CommentComposerUi(val path: String, val side: CommentSide?, val line: Int?, val branch: String, val text: String = "", val saving: Boolean = false, val problem: String? = null)
data class CommentEditUi(val threadId: String, val commentId: String, val text: String, val saving: Boolean = false, val problem: String? = null)
data class ReviewBarUi(val inFlight: ReviewWrite? = null, val confirmingDiscard: Boolean = false, val problem: String? = null) {
  val submitting: Boolean get() = inFlight != null
}
enum class ReviewWrite { Submit, Discard }
enum class ChangesView { Subtasks, Commits }

enum class QuickScope { All, Running, Operator, Uncommitted, Commits }

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

/** A file's diff read, with the row figures the diff stream lays out from so it never walks every loaded line. */
data class LoadedDiff(val file: ChangedFile, val state: FileDiffState, val rows: Int, val longestChars: Int)

data class ThreadUi(val expanded: Boolean? = null, val draft: String? = null, val problem: String? = null, val busy: Boolean = false)

data class SubtaskChipUi(val id: Int, val name: String, val inScope: Boolean, val selectable: Boolean)

sealed interface ChangesWindowAction {
  data class SetFilter(val text: String) : ChangesWindowAction
  data class SetView(val view: ChangesView) : ChangesWindowAction
  data class SelectFile(val path: String) : ChangesWindowAction

  /** The diff stream scrolled [path] to its top: select it without scrolling back. */
  data class FollowScroll(val path: String) : ChangesWindowAction

  /** The files on screen and just below it, nearest first; only the latest request is worked through. */
  data class EnsureDiffs(val paths: List<String>) : ChangesWindowAction
  data class NarrowToSubtask(val id: Int) : ChangesWindowAction

  /** A strip column or picker row; [extend] (Shift) grows the range from the anchor, and only a plain picker pick closes it. */
  data class PickSubtask(val id: Int, val extend: Boolean, val fromPicker: Boolean) : ChangesWindowAction
  data class DragSubtasks(val fromId: Int, val toId: Int) : ChangesWindowAction
  data class PickCommit(val sha: String, val extend: Boolean, val fromPicker: Boolean) : ChangesWindowAction
  data class DragCommits(val fromSha: String, val toSha: String) : ChangesWindowAction
  data class SelectQuickScope(val kind: QuickScope) : ChangesWindowAction
  data class Step(val forward: Boolean) : ChangesWindowAction
  data object TogglePicker : ChangesWindowAction
  data object OpenPicker : ChangesWindowAction
  data object ClosePicker : ChangesWindowAction
  data class PickerQuery(val text: String) : ChangesWindowAction
  data object PickAll : ChangesWindowAction
  data class MoveFile(val delta: Int) : ChangesWindowAction
  data object OpenSelectedFile : ChangesWindowAction
  data class ToggleThread(val threadId: String) : ChangesWindowAction
  data class EditReply(val threadId: String, val text: String?) : ChangesWindowAction
  data class SendReply(val threadId: String) : ChangesWindowAction
  data class SetThreadState(val threadId: String, val state: CommentState) : ChangesWindowAction
  data class DeleteComment(val threadId: String, val commentId: String) : ChangesWindowAction
  data class OpenComposer(val path: String, val side: CommentSide?, val line: Int?) : ChangesWindowAction
  data class ComposerText(val text: String) : ChangesWindowAction
  data object CancelComposer : ChangesWindowAction
  data object SavePending : ChangesWindowAction
  data class StartEdit(val threadId: String, val commentId: String, val body: String) : ChangesWindowAction
  data class EditText(val text: String) : ChangesWindowAction
  data object SaveEdit : ChangesWindowAction
  data object CancelEdit : ChangesWindowAction
  data object SubmitReview : ChangesWindowAction
  data object RequestDiscard : ChangesWindowAction
  data object ConfirmDiscard : ChangesWindowAction
  data object KeepPending : ChangesWindowAction
}
