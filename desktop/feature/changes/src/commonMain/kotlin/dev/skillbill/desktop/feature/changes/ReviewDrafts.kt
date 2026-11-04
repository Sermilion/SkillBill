package dev.skillbill.desktop.feature.changes

import dev.skillbill.desktop.core.common.Outcome
import dev.skillbill.desktop.core.domain.model.CommentAuthor
import dev.skillbill.desktop.core.domain.model.CommentState
import dev.skillbill.desktop.core.domain.model.DiffCommentsState
import dev.skillbill.desktop.core.domain.repository.DiffCommentRepository
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

internal class DiffCommentAuthoringActions(
  private val scope: CoroutineScope,
  private val comments: DiffCommentRepository,
  private val state: () -> ChangesWindowUiState,
  private val update: ((ChangesWindowUiState) -> ChangesWindowUiState) -> Unit,
) {
  private var toastJob: Job? = null

  fun handle(action: ChangesWindowAction) {
    when (action) {
      is ChangesWindowAction.OpenComposer -> if (state().canAuthor) update { it.copy(composer = CommentComposerUi(action.path, action.side, action.line), editing = null) }
      is ChangesWindowAction.ComposerText -> update { old ->
        if (old.composer?.saving == true) old else old.copy(composer = old.composer?.copy(text = action.text, problem = null))
      }
      ChangesWindowAction.CancelComposer -> update { it.copy(composer = null) }
      ChangesWindowAction.SavePending -> savePending()
      is ChangesWindowAction.StartEdit -> startEdit(action)
      is ChangesWindowAction.EditText -> update { old ->
        if (old.editing?.saving == true) old else old.copy(editing = old.editing?.copy(text = action.text, problem = null))
      }
      ChangesWindowAction.SaveEdit -> saveEdit()
      ChangesWindowAction.CancelEdit -> update { it.copy(editing = null) }
      ChangesWindowAction.SubmitReview -> submitReview()
      ChangesWindowAction.RequestDiscard -> update {
        if (it.review.submitting) it else it.copy(review = it.review.copy(confirmingDiscard = true, problem = null))
      }
      ChangesWindowAction.ConfirmDiscard -> discardPending()
      ChangesWindowAction.KeepPending -> update {
        if (it.review.submitting) it else it.copy(review = it.review.copy(confirmingDiscard = false))
      }
      else -> Unit
    }
  }

  fun cancelToast() {
    toastJob?.cancel()
    toastJob = null
  }

  private fun savePending() {
    val current = state()
    val composer = current.composer ?: return
    val body = composer.text.trim()
    val baseline = current.target?.allBaseline ?: return
    if (body.isBlank() || composer.saving || !current.canAuthor) return
    val saving = composer.copy(saving = true, problem = null)
    update { it.copy(composer = saving) }
    scope.launch {
      when (val result = comments.addPending(baseline, composer.path, composer.side, composer.line, body)) {
        is Outcome.Success -> update { if (it.composer === saving) it.copy(composer = null) else it }
        is Outcome.Failure -> update {
          if (it.composer === saving) it.copy(composer = saving.copy(saving = false, problem = result.error.reason)) else it
        }
      }
    }
  }

  private fun startEdit(action: ChangesWindowAction.StartEdit) {
    val comment = (state().comments as? DiffCommentsState.Available)
      ?.threads?.firstOrNull { it.id == action.threadId && it.state == CommentState.Pending }
      ?.comments?.firstOrNull { it.id == action.commentId && it.author == CommentAuthor.User } ?: return
    update { it.copy(editing = CommentEditUi(action.threadId, action.commentId, comment.body), composer = null) }
  }

  private fun saveEdit() {
    val edit = state().editing ?: return
    val body = edit.text.trim()
    if (body.isBlank() || edit.saving) return
    val saving = edit.copy(saving = true, problem = null)
    update { it.copy(editing = saving) }
    scope.launch {
      when (val result = comments.editComment(edit.commentId, body)) {
        is Outcome.Success -> update { if (it.editing === saving) it.copy(editing = null) else it }
        is Outcome.Failure -> update {
          if (it.editing === saving) it.copy(editing = saving.copy(saving = false, problem = result.error.reason)) else it
        }
      }
    }
  }

  private fun submitReview() {
    val current = state()
    val baseline = current.target?.allBaseline ?: return
    if (current.pendingCount == 0 || current.review.submitting) return
    val submitting = ReviewBarUi(submitting = true)
    update { it.copy(review = submitting) }
    scope.launch {
      val result = comments.submitReview(baseline)
      if (state().review !== submitting) return@launch
      when (result) {
        is Outcome.Success -> {
          val toast = "Review added · ${result.value} ${if (result.value == 1) "comment" else "comments"}"
          cancelToast()
          update { it.copy(review = ReviewBarUi(), reviewToast = toast) }
          toastJob = launch { delay(REVIEW_TOAST_MS); update { it.copy(reviewToast = null) } }
        }
        is Outcome.Failure -> update { it.copy(review = ReviewBarUi(problem = result.error.reason)) }
      }
    }
  }

  private fun discardPending() {
    val current = state()
    val baseline = current.target?.allBaseline ?: return
    if (current.review.submitting || !current.review.confirmingDiscard) return
    val discarding = ReviewBarUi(submitting = true)
    val editing = current.editing
    update { it.copy(review = discarding) }
    scope.launch {
      val result = comments.discardPending(baseline)
      if (state().review !== discarding) return@launch
      when (result) {
        is Outcome.Success -> update { it.copy(review = ReviewBarUi(), editing = it.editing?.takeUnless { edit -> edit === editing }) }
        is Outcome.Failure -> update { it.copy(review = ReviewBarUi(problem = result.error.reason)) }
      }
    }
  }
}

private const val REVIEW_TOAST_MS = 2_600L
