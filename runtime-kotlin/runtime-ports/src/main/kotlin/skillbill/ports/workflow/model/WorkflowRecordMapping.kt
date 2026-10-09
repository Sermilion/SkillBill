package skillbill.ports.workflow.model

import skillbill.contracts.JsonCodec
import skillbill.contracts.SharedPayloadKeys
import skillbill.contracts.workflow.payload.WorkflowWirePayloadKeys
import skillbill.error.core.MalformedJsonTextError
import skillbill.error.shellcontent.invalidWorkflowStateSchemaError
import skillbill.workflow.engine.model.DurableWorkflowArtifacts
import skillbill.workflow.engine.model.WorkflowStateSnapshot
import skillbill.workflow.engine.model.WorkflowStepState
import skillbill.workflow.model.WorkflowStepStatus
import java.math.BigDecimal
import java.math.BigInteger
import java.time.Instant
import java.time.LocalDateTime
import java.time.OffsetDateTime
import java.time.ZoneOffset
import java.time.format.DateTimeFormatter
import java.time.format.DateTimeFormatterBuilder
import java.time.format.DateTimeParseException
import java.time.temporal.ChronoField

fun WorkflowStateRecord.toSnapshot(): WorkflowStateSnapshot =
  WorkflowStateSnapshot(
    workflowId = workflowId,
    sessionId = sessionId,
    workflowName = workflowName,
    mode = mode,
    contractVersion = contractVersion,
    workflowStatus = WorkflowStateRecord.requiredWorkflowStatus(workflowStatus),
    currentStepId = currentStepId,
    steps = decodeSteps(stepsJson),
    artifacts = DurableWorkflowArtifacts.fromMap(decodeObject(artifactsJson)),
    startedAt = decodeInstant(startedAt, "started_at"),
    updatedAt = decodeInstant(updatedAt, "updated_at"),
    finishedAt = decodeInstant(finishedAt, "finished_at"),
  )

fun WorkflowStateSnapshot.mapToRecord(source: WorkflowStateRecord? = null): WorkflowStateRecord {
  val sourceSnapshot = source?.toSnapshot()
  val stepsJson = encodeSteps(steps)
  val persistedArtifacts = preserveArtifactTimestampText(artifacts, sourceSnapshot?.artifacts)
  val artifactsJson = JsonCodec.valueToJsonString(persistedArtifacts)
  return WorkflowStateRecord(
    workflowId = workflowId,
    sessionId = sessionId,
    workflowName = workflowName,
    contractVersion = contractVersion,
    workflowStatus = workflowStatus.wireValue,
    currentStepId = currentStepId,
    stepsJson = source?.takeIf { sourceSnapshot?.steps == steps }?.stepsJson ?: stepsJson,
    artifactsJson =
      source?.takeIf { sourceSnapshot?.artifacts?.toMap() == persistedArtifacts }?.artifactsJson ?: artifactsJson,
    startedAt = encodeInstant(startedAt, source?.startedAt),
    updatedAt = encodeInstant(updatedAt, source?.updatedAt),
    finishedAt = encodeInstant(finishedAt, source?.finishedAt),
    mode = mode,
    implementationSkill = source?.implementationSkill,
    issueKey = source?.issueKey,
    stateEnteredAt = source?.stateEnteredAt,
    stateEnteredAtEstimated = source?.stateEnteredAtEstimated ?: false,
  )
}

private fun decodeSteps(raw: String): List<WorkflowStepState> {
  val root =
    parseJson(raw, "steps") as? List<*>
      ?: throw invalidWorkflowStateSchemaError("Workflow state steps must decode to a JSON array.")
  return root.mapIndexed(::decodeStep)
}

private fun decodeStep(
  index: Int,
  entry: Any?,
): WorkflowStepState {
  val item = JsonCodec.anyToStringAnyMap(entry) ?: invalidStep(index, "must decode to a JSON object.")
  val allowedKeys =
    setOf(
      SharedPayloadKeys.STEP_ID,
      SharedPayloadKeys.STATUS,
      WorkflowWirePayloadKeys.ATTEMPT_COUNT,
      WorkflowWirePayloadKeys.PLAN_WORKFLOW_ID,
    )
  if (item.keys.any { it !in allowedKeys }) {
    invalidStep(index, "contains an unknown field.")
  }
  val stepId =
    item[SharedPayloadKeys.STEP_ID] as? String
      ?: invalidStep(index, "step_id must decode to a string.")
  val statusValue =
    item[SharedPayloadKeys.STATUS] as? String
      ?: invalidStep(index, "status must decode to a string.")
  val status =
    WorkflowStepStatus.fromWire(statusValue)
      ?: invalidStep(index, "status has unsupported value '$statusValue'.")
  val attempts =
    if (WorkflowWirePayloadKeys.ATTEMPT_COUNT in item) {
      item[WorkflowWirePayloadKeys.ATTEMPT_COUNT].toExactIntOrNull()
        ?: invalidStep(index, "attempt_count must decode to an integer.")
    } else {
      0
    }
  val planWorkflowId =
    if (WorkflowWirePayloadKeys.PLAN_WORKFLOW_ID in item) {
      (item[WorkflowWirePayloadKeys.PLAN_WORKFLOW_ID] as? String)?.takeIf(String::isNotBlank)
        ?: invalidStep(index, "plan_workflow_id must decode to a non-blank string.")
    } else {
      null
    }
  return WorkflowStepState(stepId, status, attempts, planWorkflowId)
}

private fun invalidStep(
  index: Int,
  reason: String,
): Nothing = throw invalidWorkflowStateSchemaError("Workflow state steps[$index] $reason")

private fun decodeObject(raw: String): Map<String, Any?> =
  JsonCodec.anyToStringAnyMap(parseJson(raw, "artifacts"))
    ?: throw invalidWorkflowStateSchemaError("Workflow state artifacts must decode to a JSON object.")

private fun parseJson(
  raw: String,
  field: String,
): Any? =
  try {
    JsonCodec.parseValue(raw)
  } catch (error: MalformedJsonTextError) {
    throw invalidWorkflowStateSchemaError("Workflow state $field contains malformed JSON.", error)
  }

private fun encodeSteps(steps: List<WorkflowStepState>): String =
  JsonCodec.valueToJsonString(
    steps.map { step ->
      linkedMapOf<String, Any>(
        SharedPayloadKeys.STEP_ID to step.stepId,
        SharedPayloadKeys.STATUS to step.status.wireValue,
        WorkflowWirePayloadKeys.ATTEMPT_COUNT to step.attemptCount,
      ).also { encoded ->
        step.planWorkflowId?.let { encoded[WorkflowWirePayloadKeys.PLAN_WORKFLOW_ID] = it }
      }
    },
  )

private fun decodeInstant(
  raw: String?,
  field: String,
): Instant? =
  raw?.takeIf(String::isNotBlank)?.let {
    try {
      Instant.parse(it)
    } catch (_: DateTimeParseException) {
      try {
        OffsetDateTime.parse(it).toInstant()
      } catch (_: DateTimeParseException) {
        try {
          LocalDateTime.parse(it, DateTimeFormatter.ofPattern("uuuu-MM-dd HH:mm:ss")).toInstant(ZoneOffset.UTC)
        } catch (error: DateTimeParseException) {
          throw invalidWorkflowStateSchemaError("Workflow state $field contains an invalid timestamp.", error)
        }
      }
    }
  }

private fun encodeInstant(
  value: Instant?,
  source: String?,
): String? =
  if (value == null) {
    source?.takeIf(String::isBlank)
  } else {
    source?.takeIf { runCatching { decodeInstant(it, "timestamp") == value }.getOrDefault(false) }
      ?: formatChangedInstant(value, source)
  }

private fun formatChangedInstant(
  value: Instant,
  source: String?,
): String {
  val sourceText = source ?: return DateTimeFormatter.ISO_INSTANT.format(value)
  if (sourceText.matches(Regex("\\d{4}-\\d{2}-\\d{2} \\d{2}:\\d{2}:\\d{2}"))) {
    return DateTimeFormatter.ofPattern("uuuu-MM-dd HH:mm:ss").withZone(ZoneOffset.UTC).format(value)
  }
  val offsetSource = runCatching { OffsetDateTime.parse(sourceText) }.getOrNull()
  if (offsetSource != null) {
    val fractionDigits = Regex("\\.(\\d+)(?=Z|[+-]\\d{2}:\\d{2}$)").find(sourceText)?.groupValues?.get(1)?.length ?: 0
    val formatter =
      DateTimeFormatterBuilder()
        .appendPattern("uuuu-MM-dd'T'HH:mm:ss")
        .apply {
          if (fractionDigits > 0) appendFraction(ChronoField.NANO_OF_SECOND, fractionDigits, fractionDigits, true)
        }
        .appendOffset("+HH:MM", if (sourceText.endsWith('Z')) "Z" else "+00:00")
        .toFormatter()
    return formatter.format(value.atOffset(offsetSource.offset))
  }
  return DateTimeFormatter.ISO_INSTANT.format(value)
}

private fun Any?.toExactIntOrNull(): Int? =
  when (this) {
    is Byte -> toInt()
    is Short -> toInt()
    is Int -> this
    is Long -> takeIf { it in Int.MIN_VALUE..Int.MAX_VALUE }?.toInt()
    is BigInteger -> runCatching { intValueExact() }.getOrNull()
    is BigDecimal -> runCatching { intValueExact() }.getOrNull()
    is String -> toIntOrNull()
    else -> null
  }
