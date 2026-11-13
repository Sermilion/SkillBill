package dev.skillbill.desktop.feature.chat

import dev.skillbill.desktop.core.domain.model.NewTaskActionKind
import dev.skillbill.desktop.core.domain.model.NewTaskKind
import dev.skillbill.desktop.core.domain.model.NewTaskStart
import dev.skillbill.desktop.core.domain.model.NewTaskStartOutcome
import dev.skillbill.desktop.core.domain.model.OperationCardConfirmation
import dev.skillbill.desktop.core.domain.model.OperationCardDismissOutcome
import dev.skillbill.desktop.core.domain.model.OperationCardLookup
import dev.skillbill.desktop.core.domain.model.OperationCardState
import dev.skillbill.desktop.core.domain.model.VerifyStartChoices
import dev.skillbill.desktop.core.domain.repository.WorkControlRepository
import dev.skillbill.desktop.core.ui.feedback.readProblemText
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.util.concurrent.ConcurrentHashMap

/**
 * Owns the operator's clicks on the approval cards of parked operations for one chat surface. A card is read from the
 * runtime store, never built from agent output, and only [confirm] sends the typed continuation. [base] supplies a start
 * request for an operation kind with the surface's repository, agent and database session, or null when it has none.
 */
internal class OperationCardCoordinator(
  private val controls: WorkControlRepository,
  private val scope: CoroutineScope,
  private val base: (NewTaskKind) -> NewTaskStart?,
  private val historyChanged: suspend (ChatThread) -> Boolean = { true },
  private val pollMillis: Long = RESTORED_CONFIRMING_POLL_MILLIS,
  private val runs: RunOpener = UnavailableRunOpener,
  private val admissionProblem: (ChatThread, NewTaskStart?, Any?) -> String? = { _, _, _ -> null },
  private val decisionAdmissionProblem: (ChatThread, Any?) -> String? = { _, _ -> null },
  private val requests: OperationCardRequests = OperationCardRequests(),
) {
  internal enum class AdjustmentSubmission { Accepted, Rejected, NotApplicable }

  private data class RememberedRequest(val request: NewTaskStart, val authority: Any?)
  private val rememberedRequests = ConcurrentHashMap<String, RememberedRequest>()
  private val observations = ConcurrentHashMap<String, Job>()
  private val pendingAdjustments = ConcurrentHashMap<ChatThread, String>()
  private val outstanding = ConcurrentHashMap<ChatThread, Int>()
  private val decisionClaims = ConcurrentHashMap<ChatThread, Any>()
  private val revokedThreads = ConcurrentHashMap.newKeySet<ChatThread>()
  private val reservedThreads = ConcurrentHashMap.newKeySet<ChatThread>()

  fun hasOutstanding(thread: ChatThread): Boolean = outstanding.containsKey(thread)

  fun hasDecisionOutstanding(thread: ChatThread, except: Any? = null): Boolean =
    decisionClaims[thread]?.let { it !== except } == true

  fun hasOtherOutstanding(thread: ChatThread, decisionClaim: Any? = null): Boolean {
    val count = outstanding[thread] ?: return false
    val ownsDecision = decisionClaim != null && decisionClaims[thread] === decisionClaim
    return count > (if (ownsDecision) 1 else 0)
  }

  private fun admissionProblemForDecision(thread: ChatThread, request: NewTaskStart?, authority: Any?, claim: Any?): String? =
    admissionProblem(thread, request, authority) ?: decisionAdmissionProblem(thread, claim)

  fun reserveContextTransition(thread: ChatThread): Boolean {
    if (thread in revokedThreads || hasOutstanding(thread)) return false
    reservedThreads += thread
    if (hasOutstanding(thread)) {
      reservedThreads.remove(thread)
      return false
    }
    return true
  }

  fun releaseContextTransition(thread: ChatThread) {
    reservedThreads.remove(thread)
  }

  fun revoke(thread: ChatThread) {
    revokedThreads += thread
    reservedThreads.remove(thread)
    val references = thread.transcript.value.flatMap { message ->
      when (message) {
        is ChatMessage.OperationCard -> listOf(message.card.token to message.card.launchId)
        is ChatMessage.Notice -> message.operationCard?.let { listOf(it.token to it.launchId) }.orEmpty()
        else -> emptyList()
      }
    }
    references.forEach { (token, launchId) ->
      observations.remove(token)?.cancel()
      rememberedRequests.remove(launchId)
      requests.remove(launchId)
    }
    pendingAdjustments.remove(thread)
  }

  /** Keeps the request that parked [launchId], so Adjust can ask for a replacement with the same target and branch. */
  fun remember(launchId: String, request: NewTaskStart, authority: Any? = null) {
    val prior = rememberedRequests[launchId]
    val safeRequest = request.copy(confirmation = null)
    requests.remember(launchId, safeRequest)
    rememberedRequests[launchId] = RememberedRequest(safeRequest, authority ?: prior?.authority)
  }

  private fun remembered(launchId: String): RememberedRequest? = rememberedRequests[launchId]
    ?: requests[launchId]?.let { RememberedRequest(it, null) }

  /** Reads and shows the card [launchId] parked; false when the store holds none. */
  suspend fun show(thread: ChatThread, launchId: String): Boolean {
    val card = controls.operationCardForLaunch(launchId) ?: return false
    return refresh(thread, card.token)
  }

  /**
   * Reads again every restored card whose identity [tokens] kept, so a card shows only what the store holds now. A card
   * the runtime still reports as confirming is read again every [pollMillis] until it settles, the database session
   * changes or this surface's [scope] ends; the observation never submits a continuation.
   */
  fun revalidate(thread: ChatThread, tokens: List<String>) {
    if (thread in revokedThreads) return
    tokens.forEach { token ->
      observations.remove(token)?.cancel()
      val job = scope.launch {
        val finish = track(thread)
        try {
          observe(thread, token)
        } finally {
          finish()
        }
      }
      observations[token] = job
      job.invokeOnCompletion { observations.remove(token, job) }
    }
  }

  private suspend fun observe(thread: ChatThread, token: String) {
    while (true) {
      val result = controls.revalidateOperationCard(token)
      apply(thread, token, result)
      val confirming = (result as? OperationCardLookup.Present)?.card?.state == OperationCardState.Confirming
      if (!confirming) return
      delay(pollMillis)
    }
  }

  private suspend fun refresh(thread: ChatThread, token: String): Boolean =
    apply(thread, token, controls.revalidateOperationCard(token))

  private suspend fun apply(thread: ChatThread, token: String, lookup: OperationCardLookup): Boolean =
    if (thread in revokedThreads) false else when (val result = lookup) {
      OperationCardLookup.Missing -> {
        thread.append(ChatMessage.Notice("The saved operation card is missing; start a new proposal from chat."))
        historyChanged(thread)
        false
      }
      is OperationCardLookup.Unavailable -> {
        controls.operationCard(token)?.let { card ->
          thread.showOperationCard(card.copy(canConfirm = false, disabledReason = readProblemText(result.problem)), restoredCopy = true)
        }
        historyChanged(thread)
        false
      }
      is OperationCardLookup.Present -> {
        thread.showOperationCard(result.card)
        historyChanged(thread)
      }
    }

  fun actions(thread: ChatThread, actionLock: Any = thread): ChatOperationCardActions =
    ChatOperationCardActions(
      onChooseOption = { token, ordinal, optionId ->
        synchronized(actionLock) {
          if (thread !in revokedThreads && thread !in reservedThreads) {
            thread.chooseOperationOption(token, ordinal, optionId)
            scope.launch { persistOrWarn(thread) }
          }
        }
      },
      onChooseRecommended = { token ->
        synchronized(actionLock) {
          if (thread !in revokedThreads && thread !in reservedThreads) {
            thread.chooseRecommendedOptions(token)
            scope.launch { persistOrWarn(thread) }
          }
        }
      },
      onConfirm = { token -> synchronized(actionLock) { if (thread !in revokedThreads && thread !in reservedThreads) confirm(thread, token) } },
      onDismiss = { token -> synchronized(actionLock) { if (thread !in revokedThreads && thread !in reservedThreads) dismiss(thread, token) } },
      onAdjust = { token -> synchronized(actionLock) { if (thread !in revokedThreads && thread !in reservedThreads) adjust(thread, token) } },
      onChangeVerify = { token, choices -> synchronized(actionLock) { if (thread !in revokedThreads && thread !in reservedThreads) changeVerify(thread, token, choices) } },
      canAdjust = { token -> synchronized(actionLock) {
        val message = thread.operationCard(token)
        val remembered = message?.card?.launchId?.let(::remembered)
        thread !in revokedThreads && thread !in reservedThreads && !thread.busy.value && remembered != null &&
          admissionProblemForDecision(thread, remembered.request, remembered.authority, null) == null
      } },
      busy = thread.busy,
      onOpenRun = { token -> synchronized(actionLock) { if (thread !in revokedThreads && thread !in reservedThreads) openRun(thread, token) } },
    )

  private fun openRun(thread: ChatThread, token: String) {
    val message = thread.operationCard(token)?.takeIf { it.opensVerifyRun() } ?: return
    scope.launch {
      runs.open(message.card.token)?.let { problem ->
        thread.append(ChatMessage.Notice(problem))
        persistOrWarn(thread)
      }
    }
  }

  private fun confirm(thread: ChatThread, token: String) {
    val message = thread.operationCard(token) ?: return
    val card = message.card
    val kind = card.operationId.toKind() ?: return
    if (hasOutstanding(thread)) return thread.operationCardSubmitting(token, false, BUSY_OPERATION_TEXT).also {
      scope.launch { persistOrWarn(thread) }
    }
    val remembered = remembered(card.launchId)
    admissionProblemForDecision(thread, remembered?.request, remembered?.authority, null)?.let { problem ->
      thread.operationCardSubmitting(token, false, problem)
      scope.launch { persistOrWarn(thread) }
      return
    }
    message.missingChoiceText()?.let {
      thread.operationCardSubmitting(token, false, it)
      scope.launch { persistOrWarn(thread) }
      return
    }
    val start = base(kind) ?: return thread.operationCardSubmitting(token, false, NO_SESSION_TEXT).also {
      scope.launch { persistOrWarn(thread) }
    }
    admissionProblemForDecision(thread, start, remembered?.authority, null)?.let { problem ->
      thread.operationCardSubmitting(token, false, problem)
      scope.launch { persistOrWarn(thread) }
      return
    }
    val selection = message.selectionWire()
    thread.beginOperationDecision(token, OperationCardDecision.Confirm) ?: return
    pendingAdjustments.remove(thread)
    val decisionClaim = Any()
    decisionClaims[thread] = decisionClaim
    val finish = track(thread, decisionClaim)
    scope.launch {
      var refusal: String? = null
      try {
        admissionProblemForDecision(thread, start, remembered?.authority, decisionClaim)?.let { refusal = it; return@launch }
        if (!historyChanged(thread)) {
          refusal = HISTORY_SAVE_FAILED_TEXT
          return@launch
        }
        admissionProblemForDecision(thread, start, remembered?.authority, decisionClaim)?.let { refusal = it; return@launch }
        val outcome = controls.startNewTask(start.copy(confirmation = OperationCardConfirmation(token, selection)))
        refusal = follow(thread, outcome, token, null)
      } finally {
        withContext(NonCancellable) {
          try {
            refresh(thread, token)
          } finally {
            thread.operationCardSubmitting(token, false, refusal)
            persistOrWarn(thread)
            finish()
          }
        }
      }
    }
  }

  private suspend fun persistOrWarn(thread: ChatThread) {
    if (!historyChanged(thread)) thread.append(ChatMessage.Notice(HISTORY_SAVE_FAILED_TEXT))
  }

  private fun dismiss(thread: ChatThread, token: String) {
    thread.beginOperationDecision(token, OperationCardDecision.Dismiss) ?: return
    pendingAdjustments.remove(thread)
    val finish = track(thread)
    scope.launch {
      var refusal: String? = null
      try {
        refusal = (controls.dismissOperationCard(token) as? OperationCardDismissOutcome.Stale)?.reason
      } finally {
        withContext(NonCancellable) {
          try {
            refresh(thread, token)
          } finally {
            thread.operationCardSubmitting(token, false, refusal)
            persistOrWarn(thread)
            finish()
          }
        }
      }
    }
  }

  private fun adjust(thread: ChatThread, token: String) {
    val message = thread.operationCard(token) ?: return
    val remembered = remembered(message.card.launchId)
    if (hasOutstanding(thread)) return thread.operationCardSubmitting(token, false, BUSY_OPERATION_TEXT).also {
      scope.launch { persistOrWarn(thread) }
    }
    val admissionFailure = remembered?.let { admissionProblemForDecision(thread, it.request, it.authority, null) }
    if (remembered == null || admissionFailure != null) {
      thread.operationCardSubmitting(token, false, admissionFailure ?: ADJUST_UNAVAILABLE_TEXT)
      scope.launch { persistOrWarn(thread) }
      return
    }
    thread.beginOperationDecision(token, OperationCardDecision.Adjust) ?: return
    thread.operationCardSubmitting(token, false)
    if (pendingAdjustments.put(thread, token) == token) return
    thread.append(ChatMessage.Agent(
      "How would you like to change this ${message.card.operationId} proposal? Reply in chat; I will prepare a revised proposal for you to confirm.",
      streaming = false,
      tools = emptyList(),
    ))
    scope.launch { persistOrWarn(thread) }
  }

  fun submitAdjustment(thread: ChatThread, text: String, actionLock: Any = thread): AdjustmentSubmission = synchronized(actionLock) {
    submitAdjustmentLocked(thread, text)
  }

  private fun submitAdjustmentLocked(thread: ChatThread, text: String): AdjustmentSubmission {
    if (text.isBlank()) return AdjustmentSubmission.NotApplicable
    val token = pendingAdjustments[thread] ?: return AdjustmentSubmission.NotApplicable
    val message = thread.operationCard(token) ?: return AdjustmentSubmission.Rejected
    val remembered = remembered(message.card.launchId) ?: return AdjustmentSubmission.Rejected
    if (hasOutstanding(thread)) {
      thread.operationCardSubmitting(token, false, BUSY_OPERATION_TEXT)
      scope.launch { persistOrWarn(thread) }
      return AdjustmentSubmission.Rejected
    }
    val request = remembered.request
    admissionProblemForDecision(thread, request, remembered.authority, null)?.let { problem ->
      thread.operationCardSubmitting(token, false, problem)
      scope.launch { persistOrWarn(thread) }
      return AdjustmentSubmission.Rejected
    }
    if (thread.beginOperationDecision(token, OperationCardDecision.Adjust) == null) return AdjustmentSubmission.Rejected
    if (!pendingAdjustments.remove(thread, token)) {
      thread.operationCardSubmitting(token, false)
      return AdjustmentSubmission.Rejected
    }
    val decisionClaim = Any()
    decisionClaims[thread] = decisionClaim
    val finish = track(thread, decisionClaim)
    thread.append(ChatMessage.Operator(text))
    val replacement = request.copy(
      confirmation = null,
      proposalChanges = listOfNotNull(request.proposalChanges, text.trim()).joinToString("\n\n"),
    )
    thread.append(ChatMessage.Notice("Updating the ${message.card.operationId} proposal…"))
    scope.launch {
      var failure: String? = null
      try {
        admissionProblemForDecision(thread, request, remembered.authority, decisionClaim)?.let { failure = it; return@launch }
        if (!historyChanged(thread)) {
          failure = HISTORY_SAVE_FAILED_TEXT
          return@launch
        }
        admissionProblemForDecision(thread, request, remembered.authority, decisionClaim)?.let { failure = it; return@launch }
        failure = follow(thread, controls.startNewTask(replacement), null, replacement, remembered.authority)
      } finally {
        withContext(NonCancellable) {
          try {
            refresh(thread, token)
          } finally {
            thread.operationCardSubmitting(token, false, failure)
            persistOrWarn(thread)
            finish()
          }
        }
      }
    }
    return AdjustmentSubmission.Accepted
  }

  /**
   * Replaces the verify proposal [token] with one using [choices], keeping its intake, branch, checkout and agent. Confirm
   * cannot change QA or review mode, so the old proposal is dismissed once the replacement parks.
   */
  private fun changeVerify(thread: ChatThread, token: String, choices: VerifyStartChoices) {
    val message = thread.operationCard(token) ?: return
    val remembered = remembered(message.card.launchId)
    val request = remembered?.request
    if (hasOutstanding(thread)) return thread.operationCardSubmitting(token, false, BUSY_OPERATION_TEXT).also {
      scope.launch { persistOrWarn(thread) }
    }
    val admissionFailure = if (request == null || remembered == null) null else admissionProblemForDecision(thread, request, remembered.authority, null)
    if (request == null || request.kind != NewTaskKind.Verify || admissionFailure != null) {
      thread.operationCardSubmitting(token, false, admissionFailure ?: ADJUST_UNAVAILABLE_TEXT)
      scope.launch { persistOrWarn(thread) }
      return
    }
    thread.beginOperationDecision(token, OperationCardDecision.Adjust) ?: return
    pendingAdjustments.remove(thread, token)
    val replacement = request.copy(confirmation = null, verify = choices)
    thread.append(ChatMessage.Notice("Updating the verify proposal…"))
    val decisionClaim = Any()
    decisionClaims[thread] = decisionClaim
    val finish = track(thread, decisionClaim)
    scope.launch {
      var failure: String? = null
      try {
        admissionProblemForDecision(thread, request, remembered.authority, decisionClaim)?.let { failure = it; return@launch }
        if (!historyChanged(thread)) {
          failure = HISTORY_SAVE_FAILED_TEXT
          return@launch
        }
        admissionProblemForDecision(thread, request, remembered.authority, decisionClaim)?.let { failure = it; return@launch }
        failure = follow(thread, controls.startNewTask(replacement), null, replacement, remembered.authority)
        if (failure == null) {
          failure = (controls.dismissOperationCard(token) as? OperationCardDismissOutcome.Stale)?.reason
        }
      } finally {
        withContext(NonCancellable) {
          try {
            refresh(thread, token)
          } finally {
            thread.operationCardSubmitting(token, false, failure)
            persistOrWarn(thread)
            finish()
          }
        }
      }
    }
  }

  /**
   * Follows a continuation of the card [token], or a [replacement] start, to its end and returns why it failed. A
   * continuation refreshes its own card while it runs; a replacement shows the card it parks.
   */
  private suspend fun follow(thread: ChatThread, outcome: NewTaskStartOutcome, token: String?, replacement: NewTaskStart?, authority: Any? = null): String? =
    when (outcome) {
      is NewTaskStartOutcome.Started -> {
        token?.let { id -> controls.operationCard(id)?.let { thread.showOperationCard(it) } }
        val failure = outcome.awaitFailure?.invoke()
        val launchId = outcome.launchId
        if (replacement != null && launchId != null) {
          remember(launchId, replacement, authority)
          if (failure == null && !show(thread, launchId)) {
            thread.append(ChatMessage.Notice("The operation finished without a replacement proposal."))
          }
        }
        failure
      }
      is NewTaskStartOutcome.AwaitingConfirmation -> {
        replacement?.let { remember(outcome.launchId, it, authority) }
        if (show(thread, outcome.launchId)) null else CARD_UNREADABLE_TEXT
      }
      is NewTaskStartOutcome.Refused -> outcome.message
      is NewTaskStartOutcome.Failed -> readProblemText(outcome.problem)
      is NewTaskStartOutcome.NeedsKey -> "This operation does not take an issue key."
    }

  private fun track(thread: ChatThread, decisionClaim: Any? = null): () -> Unit {
    outstanding.compute(thread) { _, count -> (count ?: 0) + 1 }
    return {
      if (decisionClaim != null) decisionClaims.remove(thread, decisionClaim)
      outstanding.computeIfPresent(thread) { _, count -> if (count == 1) null else count - 1 }
    }
  }

  private fun String.toKind(): NewTaskKind? =
    when (this) {
      NewTaskActionKind.Verify.wire -> NewTaskKind.Verify
      NewTaskActionKind.PrReviewFix.wire -> NewTaskKind.PrReviewFix
      else -> null
    }
}

private const val RESTORED_CONFIRMING_POLL_MILLIS = 3_000L
private const val NO_SESSION_TEXT = "The database session is not open, so nothing can be sent."
private const val ADJUST_UNAVAILABLE_TEXT = "Adjust is unavailable for this card; start the operation again from the chat."
private const val CARD_UNREADABLE_TEXT = "The operation is waiting for confirmation, but its card could not be read."
private const val HISTORY_SAVE_FAILED_TEXT = "The card choice could not be saved, so confirmation was not sent."
private const val BUSY_OPERATION_TEXT = "Another chat operation is still being saved or submitted. Wait for it to finish, then try again."
