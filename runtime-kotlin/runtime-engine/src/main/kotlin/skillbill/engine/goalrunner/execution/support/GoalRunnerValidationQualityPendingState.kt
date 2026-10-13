package skillbill.engine.goalrunner.execution.support

import skillbill.engine.goalrunner.manifest.GoalRunnerManifestStore
import skillbill.goalrunner.model.GoalRunnerControlState

class GoalRunnerValidationQualityPendingState(
  private val manifestStore: GoalRunnerManifestStore,
) {
  private var parentWorkflowId: String = ""

  fun bind(parentWorkflowId: String) {
    this.parentWorkflowId = parentWorkflowId
  }

  private fun control(): GoalRunnerControlState = manifestStore.controlState(parentWorkflowId)

  private fun update(transform: (GoalRunnerControlState) -> GoalRunnerControlState) {
    manifestStore.updateControlState(parentWorkflowId, transform)
  }

  fun validationQualityRetryCount(subtaskId: Int): Int = control().validationQualityRetriesBySubtask[subtaskId] ?: 0

  fun incrementValidationQualityRetry(subtaskId: Int): Int {
    var next = 0
    update { state ->
      next = (state.validationQualityRetriesBySubtask[subtaskId] ?: 0) + 1
      state.copy(validationQualityRetriesBySubtask = state.validationQualityRetriesBySubtask + (subtaskId to next))
    }
    return next
  }

  fun storePendingReAttemptCause(
    subtaskId: Int,
    cause: String,
  ) {
    update { state ->
      state.copy(pendingReAttemptCauseBySubtask = state.pendingReAttemptCauseBySubtask + (subtaskId to cause))
    }
  }

  fun takePendingReAttemptCause(subtaskId: Int): String? {
    var cause: String? = null
    update { state ->
      cause = state.pendingReAttemptCauseBySubtask[subtaskId]
      state.copy(pendingReAttemptCauseBySubtask = state.pendingReAttemptCauseBySubtask - subtaskId)
    }
    return cause
  }

  fun storePendingCausingLoopEntry(
    subtaskId: Int,
    entry: String,
  ) {
    update { state ->
      state.copy(pendingCausingLoopEntryBySubtask = state.pendingCausingLoopEntryBySubtask + (subtaskId to entry))
    }
  }

  fun takePendingCausingLoopEntry(subtaskId: Int): String? {
    var entry: String? = null
    update { state ->
      entry = state.pendingCausingLoopEntryBySubtask[subtaskId]
      state.copy(pendingCausingLoopEntryBySubtask = state.pendingCausingLoopEntryBySubtask - subtaskId)
    }
    return entry
  }
}
