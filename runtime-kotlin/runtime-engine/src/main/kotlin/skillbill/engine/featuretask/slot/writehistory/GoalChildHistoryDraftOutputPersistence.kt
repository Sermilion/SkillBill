package skillbill.engine.featuretask.slot.writehistory

import skillbill.contracts.JsonCodec
import skillbill.engine.featuretask.runloop.core.PhaseRun
import skillbill.engine.featuretask.slot.state.PhaseRunGoal
import skillbill.error.core.SkillBillRuntimeException
import skillbill.error.shellcontent.WorkflowFailureCode
import skillbill.goalrunner.model.GoalChildHistoryDraft
import skillbill.goalrunner.model.GoalChildHistoryDraftDisposition
import skillbill.goalrunner.model.GoalChildHistoryDraftPayloadKeys
import skillbill.goalrunner.model.GoalChildHistoryDraftSaveResult
import skillbill.workflow.taskruntime.model.handoff.task.NormalizedFeatureTaskRuntimePhaseOutput

internal object GoalChildHistoryDraftOutputPersistence {
  fun save(
    recorder: PhaseRunGoal,
    run: PhaseRun,
    output: NormalizedFeatureTaskRuntimePhaseOutput,
  ): String? {
    return try {
      val draft = draft(run, output)
      draft.validate()
      when (recorder.saveGoalChildHistoryDraft(draft)) {
        is GoalChildHistoryDraftSaveResult.Saved -> null
        GoalChildHistoryDraftSaveResult.Conflict ->
          "Goal-child history draft conflicts with the accepted record for this identity."
      }
    } catch (failure: SkillBillRuntimeException) {
      "Required goal-child history draft could not be saved: ${failure.message}."
    } catch (failure: IllegalArgumentException) {
      "Required goal-child history draft is invalid: ${failure.message}."
    }
  }

  private fun draft(
    run: PhaseRun,
    output: NormalizedFeatureTaskRuntimePhaseOutput,
  ): GoalChildHistoryDraft {
    val continuation =
      run.request.goalContinuation
        ?: throw invalid("Goal-child history draft is required, but continuation identity is missing.")
    val parentId =
      continuation.parentWorkflowId?.takeIf(String::isNotBlank)
        ?: throw invalid("Goal-child history draft is required, but parent workflow identity is missing.")
    val payload = outputPayload(output)
    return decodeDraft(payload, parentId, continuation.subtaskId, run.request.workflowId)
  }

  private fun outputPayload(output: NormalizedFeatureTaskRuntimePhaseOutput): Map<String, Any?> {
    return JsonCodec.parseObjectOrNull(output.output.value)
      ?.let(JsonCodec::jsonElementToValue)?.let(JsonCodec::anyToStringAnyMap)
      ?: throw invalid("Goal-child history draft output must be one JSON object.")
  }

  private fun decodeDraft(
    payload: Map<String, Any?>,
    parentId: String,
    subtaskId: Int,
    childId: String,
  ): GoalChildHistoryDraft {
    val keys = GoalChildHistoryDraftPayloadKeys
    val disposition =
      GoalChildHistoryDraftDisposition.entries.singleOrNull {
        it.wireValue == payload[keys.DISPOSITION]
      } ?: throw invalid("Goal-child history draft disposition must be 'draft' or 'skip'.")
    val fields =
      when (disposition) {
        GoalChildHistoryDraftDisposition.DRAFT ->
          setOf(
            keys.DISPOSITION,
            keys.BOUNDARY,
            keys.HISTORY,
            keys.DECISIONS,
          )
        GoalChildHistoryDraftDisposition.SKIP -> setOf(keys.DISPOSITION, keys.OUTPUT_REASON)
      }
    if (payload.keys != fields) throw invalid("Goal-child history draft output has missing or unknown fields.")
    return GoalChildHistoryDraft(
      parentWorkflowId = parentId,
      subtaskId = subtaskId,
      childWorkflowId = childId,
      disposition = disposition,
      boundary = payload[keys.BOUNDARY] as? String,
      history = payload[keys.HISTORY] as? String,
      decisions = payload[keys.DECISIONS] as? String,
      skipReason = payload[keys.OUTPUT_REASON] as? String,
    )
  }

  private fun invalid(message: String) =
    SkillBillRuntimeException(WorkflowFailureCode.INVALID_GOAL_CHILD_HISTORY_DRAFT, message)
}
