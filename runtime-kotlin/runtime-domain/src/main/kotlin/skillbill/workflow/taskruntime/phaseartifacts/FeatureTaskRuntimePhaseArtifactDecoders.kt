package skillbill.workflow.taskruntime.phaseartifacts

import skillbill.contracts.JsonCodec
import skillbill.contracts.SharedPayloadKeys
import skillbill.error.shellcontent.InvalidWorkflowStateSchemaError
import skillbill.workflow.engine.model.FEATURE_TASK_RUNTIME_GOAL_CONTINUATION_FIELD_ADOPTION_ARTIFACT_KEY
import skillbill.workflow.engine.model.FEATURE_TASK_RUNTIME_OPERATOR_BLOCK_RETRY_ARTIFACT_KEY
import skillbill.workflow.engine.model.FEATURE_TASK_RUNTIME_PHASE_RECORDS_ARTIFACT_KEY
import skillbill.workflow.engine.model.FEATURE_TASK_RUNTIME_RESOLVED_BRANCH_ARTIFACT_KEY
import skillbill.workflow.engine.model.FEATURE_TASK_RUNTIME_REVIEW_GENERATION_ARTIFACT_KEY
import skillbill.workflow.taskruntime.model.core.FeatureTaskRuntimeResolvedBranch
import skillbill.workflow.taskruntime.model.core.FeatureTaskRuntimeWorkflowArtifactMap
import skillbill.workflow.taskruntime.model.persistence.FeatureTaskRuntimeGoalContinuationFieldAdoption
import skillbill.workflow.taskruntime.model.phase.FeatureTaskRuntimePhaseRecord
import skillbill.workflow.taskruntime.model.repair.FEATURE_TASK_RUNTIME_OPERATOR_BLOCK_RETRY_REASON_KEY
import skillbill.workflow.taskruntime.model.repair.FEATURE_TASK_RUNTIME_OPERATOR_BLOCK_RETRY_RETRIED_AT_KEY
import skillbill.workflow.taskruntime.model.repair.FeatureTaskRuntimeOperatorBlockRetry

fun schemaError(detail: String): Nothing = throw InvalidWorkflowStateSchemaError(detail)

fun <T> decodeStrictKeyedArtifactMap(
  artifacts: FeatureTaskRuntimeWorkflowArtifactMap,
  artifactKey: String,
  ignoreEntry: (String) -> Boolean = { false },
  decodeEntry: (String, FeatureTaskRuntimeWorkflowArtifactMap) -> T,
): Map<String, T> {
  if (artifactKey !in artifacts) return emptyMap()
  val raw = artifacts[artifactKey]
  val rawMap =
    raw as? Map<*, *>
      ?: schemaError("Feature-task-runtime artifact '$artifactKey' must decode to a map.")
  return buildMap {
    rawMap.forEach { (key, value) ->
      val phaseId =
        key as? String
          ?: schemaError("Feature-task-runtime artifact '$artifactKey' must have string keys; found '$key'.")
      if (ignoreEntry(phaseId)) return@forEach
      val entryMap = FeatureTaskRuntimeWorkflowArtifactMap.from(value)
      if (!entryMap.isObject) {
        schemaError("Feature-task-runtime artifact '$artifactKey' entry for '$phaseId' must decode to a map.")
      }
      put(phaseId, decodeEntry(phaseId, entryMap))
    }
  }
}

internal fun phaseRecordsFrom(artifacts: Map<String, Any?>): Map<String, FeatureTaskRuntimePhaseRecord> =
  decodeStrictKeyedArtifactMap(
    FeatureTaskRuntimeWorkflowArtifactMap.from(artifacts),
    FEATURE_TASK_RUNTIME_PHASE_RECORDS_ARTIFACT_KEY,
  ) { _, recordMap ->
    FeatureTaskRuntimePhaseRecord.fromArtifactMap(recordMap)
  }

internal fun resolvedBranchFrom(artifacts: Map<String, Any?>): FeatureTaskRuntimeResolvedBranch? {
  if (FEATURE_TASK_RUNTIME_RESOLVED_BRANCH_ARTIFACT_KEY !in artifacts) return null
  val raw = artifacts[FEATURE_TASK_RUNTIME_RESOLVED_BRANCH_ARTIFACT_KEY]
  val entryMap =
    JsonCodec.anyToStringAnyMap(raw)
      ?: schemaError(
        "Feature-task-runtime artifact '$FEATURE_TASK_RUNTIME_RESOLVED_BRANCH_ARTIFACT_KEY' must decode to a map.",
      )
  return FeatureTaskRuntimeResolvedBranch.fromArtifactMap(entryMap)
}

internal fun reviewGenerationFrom(artifacts: Map<String, Any?>): Int {
  if (FEATURE_TASK_RUNTIME_REVIEW_GENERATION_ARTIFACT_KEY !in artifacts) return 0
  val raw = artifacts[FEATURE_TASK_RUNTIME_REVIEW_GENERATION_ARTIFACT_KEY]
  val ordinal =
    when (raw) {
      is Int -> raw
      is Long -> raw.toInt()
      is Number -> if (raw.toDouble() == raw.toLong().toDouble()) raw.toInt() else null
      else -> null
    }
  return ordinal?.takeIf { it >= 0 }
    ?: schemaError(
      "Feature-task-runtime artifact '$FEATURE_TASK_RUNTIME_REVIEW_GENERATION_ARTIFACT_KEY' must decode to a " +
        "non-negative integer; found '$raw'.",
    )
}

internal fun operatorBlockRetryFrom(artifacts: Map<String, Any?>): FeatureTaskRuntimeOperatorBlockRetry? {
  if (FEATURE_TASK_RUNTIME_OPERATOR_BLOCK_RETRY_ARTIFACT_KEY !in artifacts) return null
  val raw = artifacts[FEATURE_TASK_RUNTIME_OPERATOR_BLOCK_RETRY_ARTIFACT_KEY]
  val entryMap =
    JsonCodec.anyToStringAnyMap(raw)
      ?: schemaError(
        "Feature-task-runtime artifact '$FEATURE_TASK_RUNTIME_OPERATOR_BLOCK_RETRY_ARTIFACT_KEY' must decode to a map.",
      )
  return FeatureTaskRuntimeOperatorBlockRetry(
    phaseId = entryMap.requiredOperatorRetryString(SharedPayloadKeys.PHASE_ID),
    reason = entryMap.requiredOperatorRetryString(FEATURE_TASK_RUNTIME_OPERATOR_BLOCK_RETRY_REASON_KEY),
    retriedAt = entryMap.requiredOperatorRetryString(FEATURE_TASK_RUNTIME_OPERATOR_BLOCK_RETRY_RETRIED_AT_KEY),
  )
}

internal fun goalContinuationFieldAdoptionFrom(
  artifacts: Map<String, Any?>,
): FeatureTaskRuntimeGoalContinuationFieldAdoption? {
  if (FEATURE_TASK_RUNTIME_GOAL_CONTINUATION_FIELD_ADOPTION_ARTIFACT_KEY !in artifacts) return null
  val raw = artifacts[FEATURE_TASK_RUNTIME_GOAL_CONTINUATION_FIELD_ADOPTION_ARTIFACT_KEY]
  val entryMap =
    JsonCodec.anyToStringAnyMap(raw)
      ?: schemaError(
        "Feature-task-runtime artifact " +
          "'$FEATURE_TASK_RUNTIME_GOAL_CONTINUATION_FIELD_ADOPTION_ARTIFACT_KEY' must decode to a map.",
      )
  return FeatureTaskRuntimeGoalContinuationFieldAdoption.fromArtifactMap(entryMap)
}

private fun Map<String, Any?>.requiredOperatorRetryString(field: String): String =
  (this[field] as? String)?.takeIf(String::isNotBlank)
    ?: schemaError(
      "Feature-task-runtime artifact '$FEATURE_TASK_RUNTIME_OPERATOR_BLOCK_RETRY_ARTIFACT_KEY' field " +
        "'$field' must decode to a non-blank string.",
    )
