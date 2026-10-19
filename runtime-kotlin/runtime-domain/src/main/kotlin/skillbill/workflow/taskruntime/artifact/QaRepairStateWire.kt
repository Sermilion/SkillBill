package skillbill.workflow.taskruntime.artifact

import skillbill.contracts.JsonCodec
import skillbill.contracts.qa.QA_REPAIR_STATE_CONTRACT_VERSION
import skillbill.contracts.qa.QA_REPAIR_STATE_PREVIOUS_CONTRACT_VERSION
import skillbill.error.featuretask.qa.invalidQaRepairStateSchema
import skillbill.workflow.taskruntime.model.core.FeatureTaskRuntimeWorkflowArtifactMap
import skillbill.workflow.taskruntime.model.qa.QaOpenFinding
import skillbill.workflow.taskruntime.model.qa.QaRepairAttempt
import skillbill.workflow.taskruntime.model.qa.QaRepairAttemptOutcome
import skillbill.workflow.taskruntime.model.qa.QaRepairState
import skillbill.workflow.taskruntime.phase.task.FeatureTaskRuntimePhaseWorkflowDefinition
import java.math.BigDecimal
import skillbill.contracts.qa.QaRepairStatePayloadKeys as Keys

fun QaRepairState.asQaRepairStateWireMap(): FeatureTaskRuntimeWorkflowArtifactMap =
  FeatureTaskRuntimeWorkflowArtifactMap.from(
    mapOf(
      Keys.CONTRACT_VERSION to QA_REPAIR_STATE_CONTRACT_VERSION,
      Keys.SUBTASK_ID to subtaskId,
      Keys.QA_FIX_STEP_ID to FeatureTaskRuntimePhaseWorkflowDefinition.PHASE_QA_FIX,
      Keys.MAX_ATTEMPTS to QaRepairState.MAX_ATTEMPTS,
      Keys.CONSUMED_ATTEMPTS to consumedAttempts,
      Keys.ATTEMPTS to
        attempts.map { attempt ->
          mapOf(
            Keys.ATTEMPT_NUMBER to attempt.attemptNumber,
            Keys.RECEIPT_TREE_IDENTITY to attempt.receiptTreeIdentity,
            Keys.CONSUMED_FINDING_IDS to attempt.consumedFindingIds,
            Keys.REPAIR_OUTPUT_REF to attempt.repairOutputRef,
            Keys.OUTCOME to attempt.outcome.wireValue,
          )
        },
      Keys.OPEN_FINDINGS to
        openFindings.map { finding ->
          mapOf(
            Keys.ID to finding.id,
            Keys.AFFECTED_CRITERIA to finding.affectedCriteria,
            Keys.RECEIPT_REFS to finding.receiptRefs,
          )
        },
      Keys.LAST_RECEIPT_TREE_IDENTITY to lastReceiptTreeIdentity,
    ),
  )

fun FeatureTaskRuntimeWorkflowArtifactMap.toQaRepairState(): QaRepairState {
  val version = this[Keys.CONTRACT_VERSION]
  if (!isObject || version !in setOf(QA_REPAIR_STATE_CONTRACT_VERSION, QA_REPAIR_STATE_PREVIOUS_CONTRACT_VERSION)) {
    invalid("QA repair state has no supported contract version.")
  }
  if (this[Keys.QA_FIX_STEP_ID] != FeatureTaskRuntimePhaseWorkflowDefinition.PHASE_QA_FIX ||
    number(Keys.MAX_ATTEMPTS) != QaRepairState.MAX_ATTEMPTS
  ) {
    invalid("QA repair state identity or attempt limit is invalid.")
  }
  val attempts =
    objects(Keys.ATTEMPTS).map { value ->
      QaRepairAttempt(
        attemptNumber = value.int(Keys.ATTEMPT_NUMBER),
        receiptTreeIdentity = value.string(Keys.RECEIPT_TREE_IDENTITY),
        consumedFindingIds = value.strings(Keys.CONSUMED_FINDING_IDS),
        repairOutputRef = value.string(Keys.REPAIR_OUTPUT_REF),
        outcome =
          QaRepairAttemptOutcome.entries.firstOrNull { it.wireValue == value[Keys.OUTCOME] }
            ?: invalid("${Keys.OUTCOME} is unsupported."),
      )
    }
  val consumedAttempts = number(Keys.CONSUMED_ATTEMPTS)
  if (consumedAttempts != attempts.size) {
    invalid("${Keys.CONSUMED_ATTEMPTS} must equal attempts length.")
  }
  return QaRepairState(
    subtaskId = number(Keys.SUBTASK_ID),
    attempts = attempts,
    openFindings =
      decodeQaOpenFindings(
        values = this[Keys.OPEN_FINDINGS] as? List<*> ?: invalid("${Keys.OPEN_FINDINGS} must be an array."),
        acceptLegacyReceiptRef = version == QA_REPAIR_STATE_PREVIOUS_CONTRACT_VERSION,
        invalid = ::invalid,
      ),
    lastReceiptTreeIdentity =
      this[Keys.LAST_RECEIPT_TREE_IDENTITY]?.let {
        it as? String ?: invalid("${Keys.LAST_RECEIPT_TREE_IDENTITY} must be a string or null.")
      },
  )
}

fun decodeQaOpenFindings(
  values: List<*>,
  acceptLegacyReceiptRef: Boolean,
  invalid: (String) -> Nothing,
): List<QaOpenFinding> {
  fun Map<String, Any?>.nonblank(key: String): String =
    (this[key] as? String)?.takeIf(String::isNotBlank) ?: invalid("$key must be a nonblank string.")

  fun Map<String, Any?>.nonblankList(key: String): List<String> =
    (this[key] as? List<*>)?.map {
      (it as? String)?.takeIf(String::isNotBlank) ?: invalid("$key must contain nonblank strings.")
    } ?: invalid("$key must be an array.")

  val findings =
    values.map { value ->
      val finding = JsonCodec.anyToStringAnyMap(value) ?: invalid("${Keys.OPEN_FINDINGS} must contain objects.")
      QaOpenFinding(
        id = finding.nonblank(Keys.ID),
        affectedCriteria = finding.nonblankList(Keys.AFFECTED_CRITERIA),
        receiptRefs =
          if (acceptLegacyReceiptRef && Keys.RECEIPT_REFS !in finding) {
            listOf(finding.nonblank(Keys.RECEIPT_REF))
          } else {
            finding.nonblankList(Keys.RECEIPT_REFS).also { references ->
              if (references.isEmpty() || references.distinct() != references) {
                invalid("${Keys.RECEIPT_REFS} must contain unique nonblank receipt references.")
              }
            }
          },
      )
    }
  if (findings.map(QaOpenFinding::id).distinct().size != findings.size) {
    invalid("${Keys.OPEN_FINDINGS} has duplicate finding ids.")
  }
  return findings
}

private fun FeatureTaskRuntimeWorkflowArtifactMap.number(key: String): Int =
  (this[key] as? Number)?.toIntExact(key) ?: invalid("$key must be an integer.")

private fun Map<String, Any?>.int(key: String): Int =
  (this[key] as? Number)?.toIntExact(key) ?: invalid("$key must be an integer.")

private fun Number.toIntExact(key: String): Int =
  try {
    BigDecimal(toString()).intValueExact()
  } catch (_: NumberFormatException) {
    invalid("$key must be an integer.")
  } catch (_: ArithmeticException) {
    invalid("$key must be an integer.")
  }

private fun Map<String, Any?>.string(key: String): String =
  (this[key] as? String)?.takeIf(String::isNotBlank) ?: invalid("$key must be a nonblank string.")

private fun Map<String, Any?>.strings(key: String): List<String> =
  (this[key] as? List<*>)?.map { it as? String ?: invalid("$key must contain strings.") }
    ?: invalid("$key must be an array.")

private fun FeatureTaskRuntimeWorkflowArtifactMap.objects(key: String): List<Map<String, Any?>> =
  (this[key] as? List<*>)?.map { value ->
    JsonCodec.anyToStringAnyMap(value) ?: invalid("$key must contain objects.")
  } ?: invalid("$key must be an array.")

private fun invalid(reason: String): Nothing = throw invalidQaRepairStateSchema(reason)
