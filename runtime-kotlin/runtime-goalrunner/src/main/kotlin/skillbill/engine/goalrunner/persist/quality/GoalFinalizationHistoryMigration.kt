package skillbill.engine.goalrunner.persist.quality

import skillbill.contracts.workflow.goal.GOAL_FINALIZATION_HISTORY_CONTRACT_VERSION
import skillbill.engine.goalrunner.model.history.GoalFinalizationHistoryPayloadKeys
import skillbill.error.core.SkillBillRuntimeException
import skillbill.error.shellcontent.WorkflowFailureCode

internal object GoalFinalizationHistoryMigration {
  private const val LEGACY_CONTRACT_VERSION = "0.1"

  fun upgrade(payload: Map<String, Any?>): Map<String, Any?> {
    when (payload[GoalFinalizationHistoryPayloadKeys.CONTRACT_VERSION]) {
      GOAL_FINALIZATION_HISTORY_CONTRACT_VERSION -> return payload
      LEGACY_CONTRACT_VERSION -> validateLegacy(payload)
      else -> invalid("Stored finalization history has an unsupported contract version.")
    }
    if (payload[GoalFinalizationHistoryPayloadKeys.COMPLETION] != null) {
      invalid(
        "Stored 0.1 writer completion has ambiguous fingerprint semantics. At write_history, provide " +
          "authoritative original fingerprint provenance and sufficient original context, or an explicit " +
          "operator decision authorizing fresh writer work for this historical record.",
      )
    }
    return payload + (GoalFinalizationHistoryPayloadKeys.CONTRACT_VERSION to GOAL_FINALIZATION_HISTORY_CONTRACT_VERSION)
  }

  private fun validateLegacy(payload: Map<String, Any?>) {
    requireKeys(
      payload,
      setOf(
        GoalFinalizationHistoryPayloadKeys.CONTRACT_VERSION,
        GoalFinalizationHistoryPayloadKeys.REPAIR_CONTEXT,
        GoalFinalizationHistoryPayloadKeys.COMPLETION,
      ),
    )
    when (val context = payload[GoalFinalizationHistoryPayloadKeys.REPAIR_CONTEXT]) {
      null -> Unit
      is Map<*, *> -> validateRepairContext(context)
      else -> invalid("Stored 0.1 repair context is invalid.")
    }
    val completion = payload[GoalFinalizationHistoryPayloadKeys.COMPLETION]
    if (completion != null) {
      val map = completion as? Map<*, *> ?: invalid("Stored 0.1 writer completion is invalid.")
      requireKeys(
        map,
        setOf(
          GoalFinalizationHistoryPayloadKeys.INPUT_FINGERPRINT,
          GoalFinalizationHistoryPayloadKeys.LEGACY_PRE_WRITER_TREE_IDENTITY,
          GoalFinalizationHistoryPayloadKeys.LEGACY_POST_WRITER_TREE_IDENTITY,
          GoalFinalizationHistoryPayloadKeys.ACCEPTED_OUTPUT,
        ),
      )
      listOf(
        GoalFinalizationHistoryPayloadKeys.INPUT_FINGERPRINT,
        GoalFinalizationHistoryPayloadKeys.LEGACY_PRE_WRITER_TREE_IDENTITY,
        GoalFinalizationHistoryPayloadKeys.LEGACY_POST_WRITER_TREE_IDENTITY,
        GoalFinalizationHistoryPayloadKeys.ACCEPTED_OUTPUT,
      ).forEach { requireText(map[it]) }
    }
  }

  private fun validateRepairContext(context: Map<*, *>) {
    requireKeys(
      context,
      setOf(GoalFinalizationHistoryPayloadKeys.OUTPUTS, GoalFinalizationHistoryPayloadKeys.WARNING),
    )
    val outputs =
      context[GoalFinalizationHistoryPayloadKeys.OUTPUTS] as? List<*>
        ?: invalid("Stored 0.1 repair outputs are invalid.")
    outputs.forEach { value ->
      val output = value as? Map<*, *> ?: invalid("Stored 0.1 repair output is invalid.")
      requireKeys(
        output,
        setOf(
          GoalFinalizationHistoryPayloadKeys.PHASE_ID,
          GoalFinalizationHistoryPayloadKeys.INVOCATION_ID,
          GoalFinalizationHistoryPayloadKeys.OCCURRENCE,
          GoalFinalizationHistoryPayloadKeys.OUTPUT,
        ),
      )
      requireText(output[GoalFinalizationHistoryPayloadKeys.PHASE_ID])
      requireText(output[GoalFinalizationHistoryPayloadKeys.INVOCATION_ID])
      requireText(output[GoalFinalizationHistoryPayloadKeys.OUTPUT])
      val occurrence = output[GoalFinalizationHistoryPayloadKeys.OCCURRENCE] as? Number
      if (occurrence == null || occurrence.toLong() < 0 || occurrence.toDouble() != occurrence.toLong().toDouble()) {
        invalid("Stored 0.1 repair occurrence is invalid.")
      }
    }
    val warning = context[GoalFinalizationHistoryPayloadKeys.WARNING]
    if (warning != null && warning !is String) invalid("Stored 0.1 repair warning is invalid.")
  }

  private fun requireKeys(
    map: Map<*, *>,
    expected: Set<String>,
  ) {
    if (map.keys != expected) invalid("Stored 0.1 finalization history has an invalid shape.")
  }

  private fun requireText(value: Any?) {
    if (value !is String || value.isEmpty()) invalid("Stored 0.1 finalization history has invalid text.")
  }

  private fun invalid(reason: String): Nothing =
    throw SkillBillRuntimeException(WorkflowFailureCode.INVALID_GOAL_FINALIZATION_HISTORY, reason)
}
