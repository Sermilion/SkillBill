package skillbill.infrastructure.sqlite.workflow.artifact

import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import skillbill.contracts.JsonCodec
import skillbill.error.core.MalformedJsonTextError
import skillbill.error.core.SkillBillRuntimeException
import skillbill.error.shellcontent.invalidWorkflowStateSchemaError
import skillbill.ports.workflow.model.WorkflowStateRecord
import skillbill.workflow.engine.model.DurableWorkflowArtifactFamily
import skillbill.workflow.engine.model.DurableWorkflowArtifacts
import skillbill.workflow.taskruntime.model.persistence.FeatureTaskRuntimeGoalContinuationArtifact
import skillbill.workflow.taskruntime.model.persistence.FeatureTaskRuntimeGoalContinuationFieldAdoption
import skillbill.workflow.taskruntime.model.persistence.FeatureTaskRuntimeGoalPlanningImport
import skillbill.workflow.taskruntime.model.persistence.UndecodedTypedArtifact

internal data class SplitArtifacts(
  val remainderJson: String,
  val typed: DurableWorkflowArtifacts,
)

internal object FeatureTaskWorkflowArtifactsCodec {
  private val continuationKey = DurableWorkflowArtifactFamily.FEATURE_TASK_RUNTIME_GOAL_CONTINUATION.label()
  private val fieldAdoptionKey =
    DurableWorkflowArtifactFamily.FEATURE_TASK_RUNTIME_GOAL_CONTINUATION_FIELD_ADOPTION.label()
  private val planningImportKey = DurableWorkflowArtifactFamily.FEATURE_TASK_RUNTIME_GOAL_PLANNING_IMPORT.label()
  private val familyKeys: Set<String> =
    DurableWorkflowArtifactFamily.TYPED_RECORD_FAMILIES.mapTo(linkedSetOf()) { it.label() }

  fun normalizeRecord(row: WorkflowStateRecord): WorkflowStateRecord {
    val decoded = split(row.artifactsJson)
    if (decoded.typed.isEmpty()) return row
    decoded.typed.keys.firstOrNull { it in row.typedArtifacts }?.let { key ->
      throw invalidWorkflowStateSchemaError("Workflow state artifacts repeat typed artifact '$key' in the JSON object.")
    }
    return row.copy(
      artifactsJson = decoded.remainderJson,
      typedArtifacts = DurableWorkflowArtifacts.fromMap(row.typedArtifacts + decoded.typed),
    )
  }

  fun split(artifactsJson: String): SplitArtifacts {
    val unchanged = SplitArtifacts(artifactsJson, DurableWorkflowArtifacts.EMPTY)
    if (familyKeys.none { "\"$it\"" in artifactsJson }) return unchanged
    val parsed =
      try {
        JsonCodec.anyToStringAnyMap(JsonCodec.parseValue(artifactsJson))
      } catch (_: MalformedJsonTextError) {
        null
      } ?: return unchanged
    val typed = linkedMapOf<String, Any?>()
    val remainder = linkedMapOf<String, Any?>()
    parsed.forEach { (key, value) ->
      if (key in familyKeys) typed[key] = decodeFamilyValue(key, value) else remainder[key] = value
    }
    if (typed.isEmpty()) return unchanged
    return SplitArtifacts(JsonCodec.valueToJsonString(remainder), DurableWorkflowArtifacts.fromMap(typed))
  }

  fun merge(
    remainderJson: String,
    typed: DurableWorkflowArtifacts,
  ): String {
    if (typed.isEmpty()) return remainderJson
    val remainder =
      JsonCodec.anyToStringAnyMap(JsonCodec.parseValue(remainderJson))
        ?: throw invalidWorkflowStateSchemaError("Workflow state artifacts must decode to a JSON object.")
    typed.keys.firstOrNull { it in remainder }?.let { key ->
      throw invalidWorkflowStateSchemaError("Workflow state artifacts repeat typed artifact '$key' in the JSON object.")
    }
    val merged = LinkedHashMap(JsonCodec.mapToJsonObject(remainder))
    typed.forEach { (key, value) -> merged[key] = encodeFamilyValue(key, value) }
    return JsonCodec.valueToJsonString(JsonObject(merged))
  }

  fun encode(artifacts: DurableWorkflowArtifacts): JsonObject =
    JsonObject(
      artifacts.mapValues { (key, value) ->
        if (key in familyKeys) encodeFamilyValue(key, value) else JsonCodec.valueToJsonElement(value)
      },
    )

  fun decode(wire: JsonObject): DurableWorkflowArtifacts =
    DurableWorkflowArtifacts.fromMap(
      wire.mapValues { (key, element) ->
        val value = JsonCodec.jsonElementToValue(element)
        if (key in familyKeys) decodeFamilyValue(key, value) else value
      },
    )

  private fun encodeFamilyValue(
    key: String,
    value: Any?,
  ): JsonElement =
    when {
      value is UndecodedTypedArtifact -> JsonCodec.valueToJsonElement(JsonCodec.parseValue(value.jsonText))
      key == continuationKey && value is FeatureTaskRuntimeGoalContinuationArtifact ->
        JsonCodec.valueToJsonElement(GoalContinuationArtifactCodec.encode(value))
      key == fieldAdoptionKey && value is FeatureTaskRuntimeGoalContinuationFieldAdoption ->
        JsonCodec.valueToJsonElement(GoalContinuationFieldAdoptionCodec.encode(value))
      key == planningImportKey && value is FeatureTaskRuntimeGoalPlanningImport ->
        JsonCodec.valueToJsonElement(GoalPlanningImportCodec.encode(value))
      else ->
        throw invalidWorkflowStateSchemaError(
          "Workflow artifact '$key' must be a typed record, found ${value?.let { it::class.simpleName } ?: "null"}.",
        )
    }

  private fun decodeFamilyValue(
    key: String,
    raw: Any?,
  ): Any {
    val stored = JsonCodec.anyToStringAnyMap(raw)
    val jsonText = JsonCodec.valueToJsonString(raw)
    if (stored == null) {
      val failure = invalidWorkflowStateSchemaError("${artifactLabel(key)} must decode to an object.")
      return UndecodedTypedArtifact(jsonText, failure)
    }
    return try {
      when (key) {
        continuationKey -> GoalContinuationArtifactCodec.decode(stored)
        fieldAdoptionKey -> GoalContinuationFieldAdoptionCodec.decode(stored)
        else -> GoalPlanningImportCodec.decode(stored)
      }
    } catch (failure: SkillBillRuntimeException) {
      undecoded(key, stored, jsonText, failure)
    } catch (failure: IllegalArgumentException) {
      undecoded(key, stored, jsonText, failure)
    }
  }

  private fun undecoded(
    key: String,
    stored: Map<String, Any?>,
    jsonText: String,
    failure: RuntimeException,
  ): UndecodedTypedArtifact =
    UndecodedTypedArtifact(
      jsonText = jsonText,
      failure = failure,
      legacyMarker = if (key == continuationKey) GoalContinuationArtifactCodec.legacyMarker(stored) else null,
    )

  private fun artifactLabel(key: String): String =
    when (key) {
      continuationKey -> "Goal-continuation artifact"
      fieldAdoptionKey -> "Goal-continuation field adoption"
      else -> "Goal planning import"
    }
}
