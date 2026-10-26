package skillbill.engine.goalrunner.history

import skillbill.contracts.JsonCodec
import skillbill.error.core.SkillBillRuntimeException
import skillbill.error.shellcontent.WorkflowFailureCode
import skillbill.goalrunner.model.GoalChildHistoryDraft
import skillbill.goalrunner.model.GoalChildHistoryDraftDisposition
import skillbill.goalrunner.model.GoalChildHistoryDraftPayloadKeys
import skillbill.ports.taskruntime.FeatureTaskRuntimeWireArtifactValidator
import skillbill.workflow.engine.model.DurableWorkflowArtifactFamily
import skillbill.workflow.taskruntime.model.core.FeatureTaskRuntimeWireArtifactKind
import skillbill.workflow.taskruntime.model.core.FeatureTaskRuntimeWorkflowArtifactMap

private fun decodeHistoryDraft(payload: Map<String, Any?>): GoalChildHistoryDraft {
  val keys = GoalChildHistoryDraftPayloadKeys
  val draft =
    GoalChildHistoryDraft(
      contractVersion = payload[keys.CONTRACT_VERSION] as? String ?: "",
      parentWorkflowId = payload[keys.PARENT_WORKFLOW_ID] as? String ?: "",
      subtaskId = (payload[keys.SUBTASK_ID] as? Number)?.toInt() ?: 0,
      childWorkflowId = payload[keys.CHILD_WORKFLOW_ID] as? String ?: "",
      disposition =
        GoalChildHistoryDraftDisposition.entries.singleOrNull {
          it.wireValue == payload[keys.DISPOSITION]
        } ?: throw SkillBillRuntimeException(
          WorkflowFailureCode.INVALID_GOAL_CHILD_HISTORY_DRAFT,
          "Stored goal-child history draft disposition is invalid.",
        ),
      boundary = payload[keys.BOUNDARY] as? String,
      history = payload[keys.HISTORY] as? String,
      decisions = payload[keys.DECISIONS] as? String,
      skipReason = payload[keys.SKIP_REASON] as? String,
    )
  try {
    draft.validate()
  } catch (failure: IllegalArgumentException) {
    throw SkillBillRuntimeException(
      WorkflowFailureCode.INVALID_GOAL_CHILD_HISTORY_DRAFT,
      "Stored goal-child history draft is invalid.",
      failure,
    )
  }
  return draft
}

internal fun decodeHistoryDrafts(
  artifacts: Map<String, Any?>,
  parentWorkflowId: String,
  validator: FeatureTaskRuntimeWireArtifactValidator,
): List<GoalChildHistoryDraft> {
  val family = DurableWorkflowArtifactFamily.GOAL_CHILD_HISTORY_DRAFTS
  if (!family.contains(artifacts)) return emptyList()
  val raw = family.value(artifacts)
  val entries =
    raw as? List<*>
      ?: throw SkillBillRuntimeException(
        WorkflowFailureCode.INVALID_GOAL_CHILD_HISTORY_DRAFT,
        "Stored goal-child history drafts are malformed.",
      )
  val drafts = entries.map { item -> validatedDraft(item, parentWorkflowId, validator) }
  val identities = drafts.map { Triple(it.parentWorkflowId, it.subtaskId, it.childWorkflowId) }
  if (identities.size != identities.toSet().size) {
    throw SkillBillRuntimeException(
      WorkflowFailureCode.INVALID_GOAL_CHILD_HISTORY_DRAFT,
      "Stored goal-child history drafts contain duplicate identities.",
    )
  }
  return drafts
}

private fun validatedDraft(
  item: Any?,
  parentWorkflowId: String,
  validator: FeatureTaskRuntimeWireArtifactValidator,
): GoalChildHistoryDraft {
  val map =
    JsonCodec.anyToStringAnyMap(item)
      ?: throw SkillBillRuntimeException(
        WorkflowFailureCode.INVALID_GOAL_CHILD_HISTORY_DRAFT,
        "Stored goal-child history draft is malformed.",
      )
  validator.validate(
    FeatureTaskRuntimeWireArtifactKind.GOAL_CHILD_HISTORY_DRAFT,
    FeatureTaskRuntimeWorkflowArtifactMap.from(map),
    "stored goal-child history draft",
  )
  val draft = decodeHistoryDraft(map)
  if (draft.parentWorkflowId != parentWorkflowId) {
    throw SkillBillRuntimeException(
      WorkflowFailureCode.INVALID_GOAL_CHILD_HISTORY_DRAFT,
      "Stored draft parent identity does not match its ledger.",
    )
  }
  return draft
}
