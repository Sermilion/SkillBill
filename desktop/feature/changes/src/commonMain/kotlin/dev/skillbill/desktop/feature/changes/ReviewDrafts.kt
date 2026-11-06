package dev.skillbill.desktop.feature.changes

import dev.skillbill.desktop.core.common.Outcome
import dev.skillbill.desktop.core.domain.model.CommentAuthor
import dev.skillbill.desktop.core.domain.model.CommentState
import dev.skillbill.desktop.core.domain.model.DiffCommentsState
import dev.skillbill.desktop.core.domain.model.CommentRejection
import dev.skillbill.desktop.core.domain.repository.DiffCommentRepository
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.async
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull

internal class DiffCommentAuthoringActions(
  private val scope: CoroutineScope,
  private val comments: DiffCommentRepository,
  private val state: () -> ChangesWindowUiState,
  private val update: ((ChangesWindowUiState) -> ChangesWindowUiState) -> Unit,
) {
  private var toastJob: Job? = null

  fun handle(action: ChangesWindowAction) {
    when (action) {
      is ChangesWindowAction.OpenComposer -> (state().comments as? DiffCommentsState.Available)?.takeIf { it.canAuthor }?.let { comments ->
        update { it.copy(composer = CommentComposerUi(action.path, action.side, action.line, comments.branch), editing = null) }
      }
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
    val branch = composer.branch
    if (body.isBlank() || composer.saving || !current.canAuthor) return
    val saving = composer.copy(saving = true, problem = null)
    update { it.copy(composer = saving) }
    scope.launch {
      when (val result = comments.addPending(baseline, branch, composer.path, composer.side, composer.line, body)) {
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
    val snapshot = current.comments as? DiffCommentsState.Available ?: return
    val branch = snapshot.branch
    if (snapshot.threads.none { it.state == CommentState.Pending } || current.review.submitting) return
    val submitting = ReviewBarUi(inFlight = ReviewWrite.Submit)
    update { it.copy(review = submitting) }
    scope.launch {
      val result = boundedWrite("Adding the review") { comments.submitReview(baseline, branch) }
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
    val snapshot = current.comments as? DiffCommentsState.Available ?: return
    val branch = snapshot.branch
    if (current.review.submitting || !current.review.confirmingDiscard) return
    val discarding = ReviewBarUi(inFlight = ReviewWrite.Discard)
    val editing = current.editing
    update { it.copy(review = discarding) }
    scope.launch {
      val result = boundedWrite("Discarding the pending comments") { comments.discardPending(baseline, branch) }
      if (state().review !== discarding) return@launch
      when (result) {
        is Outcome.Success -> update { it.copy(review = ReviewBarUi(), editing = it.editing?.takeUnless { edit -> edit === editing }) }
        is Outcome.Failure -> update { it.copy(review = ReviewBarUi(problem = result.error.reason)) }
      }
    }
  }

  private suspend fun <T> boundedWrite(
    what: String,
    write: suspend () -> Outcome<T, CommentRejection>,
  ): Outcome<T, CommentRejection> {
    val call = scope.async { write() }
    return try {
      val result = withTimeoutOrNull(REVIEW_WRITE_TIMEOUT_MS) { call.await() }
      if (result != null) result else {
        call.cancel()
        Outcome.Failure(CommentRejection("$what timed out. Try again."))
      }
    } catch (failure: CancellationException) {
      call.cancel()
      throw failure
    } catch (failure: Throwable) {
      Outcome.Failure(CommentRejection("$what failed: ${failure.message ?: failure::class.simpleName}"))
    }
  }
}

private const val REVIEW_TOAST_MS = 2_600L
private const val REVIEW_WRITE_TIMEOUT_MS = 20_000L
