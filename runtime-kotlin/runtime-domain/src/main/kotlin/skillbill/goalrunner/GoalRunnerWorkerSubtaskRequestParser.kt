package skillbill.goalrunner

import skillbill.contracts.JsonCodec
import skillbill.error.core.MalformedJsonTextError
import skillbill.goalrunner.model.GoalRunnerWorkerSubtaskRequest
import skillbill.goalrunner.model.GoalRunnerWorkerSubtaskRequestOutcome
import skillbill.goalrunner.model.GoalRunnerWorkerSubtaskRequestRejectionReason
import skillbill.goalrunner.model.recordDurableDecodeSubstitution
import skillbill.workflow.decomposition.model.DecompositionManifest
import skillbill.workflow.decomposition.model.DecompositionSubtask
import skillbill.workflow.model.persistence.artifact.asExactIntOrNull

object GoalRunnerWorkerSubtaskRequestParser {
  fun parse(
    stdout: String,
    stderr: String,
    manifest: DecompositionManifest,
  ): List<GoalRunnerWorkerSubtaskRequestOutcome> =
    listOf(
      WorkerOutput("stdout", stdout),
      WorkerOutput("stderr", stderr),
    ).flatMap { output -> parseOutput(output, manifest) }

  private fun parseOutput(
    output: WorkerOutput,
    manifest: DecompositionManifest,
  ): List<GoalRunnerWorkerSubtaskRequestOutcome> =
    requestPayloads(output.text)
      .map { payload -> parsePayload(payload, output.stream, manifest) }

  private fun parsePayload(
    payload: String,
    sourceStream: String,
    manifest: DecompositionManifest,
  ): GoalRunnerWorkerSubtaskRequestOutcome {
    val map = parsePayloadMap(payload)
    val kind = map?.requestKind()
    return when {
      map == null ->
        rejected(
          sourceStream,
          GoalRunnerWorkerSubtaskRequestRejectionReason.MALFORMED,
          "Worker subtask request payload must be a JSON object.",
        )
      kind != REQUEST_KIND ->
        rejected(
          sourceStream,
          GoalRunnerWorkerSubtaskRequestRejectionReason.MALFORMED,
          "Worker subtask request kind must be '$REQUEST_KIND'.",
        )
      else -> map.requestFromMap(sourceStream, manifest)
    }
  }

  private fun Map<String, Any?>.requestFromMap(
    sourceStream: String,
    manifest: DecompositionManifest,
  ): GoalRunnerWorkerSubtaskRequestOutcome {
    val name = (this["name"] as? String)?.trim()?.takeIf(String::isNotBlank)
    val specPath = (this["spec_path"] as? String)?.trim()?.takeIf(String::isNotBlank)
    val requiresOperatorConfirmation = this["requires_operator_confirmation"]
    return when {
      name == null || specPath == null -> malformed(sourceStream, "name and spec_path string fields")
      requiresOperatorConfirmation != null && requiresOperatorConfirmation !is Boolean ->
        malformed(sourceStream, "requires_operator_confirmation to be a boolean when present")
      dependencyIdsOrNull() == null -> malformed(sourceStream, "dependencies to be positive integer ids when present")
      else ->
        validateRequest(
          GoalRunnerWorkerSubtaskRequest(
            name = name,
            specPath = specPath,
            rationale = this["rationale"]?.toString()?.trim()?.takeIf(String::isNotBlank),
            dependsOnSubtaskIds = dependencyIdsOrNull().orEmpty(),
            requiresOperatorConfirmation = requiresOperatorConfirmation == true,
            sourceStream = sourceStream,
          ),
          manifest,
        )
    }
  }

  private fun validateRequest(
    request: GoalRunnerWorkerSubtaskRequest,
    manifest: DecompositionManifest,
  ): GoalRunnerWorkerSubtaskRequestOutcome {
    val existingSubtasks = manifest.subtasks
    return when {
      request.name.isBlank() ->
        rejected(
          request.sourceStream,
          GoalRunnerWorkerSubtaskRequestRejectionReason.EMPTY_NAME,
          "Worker subtask request name must not be blank.",
        )
      request.specPath.isUnsafeRelativePath() ->
        rejected(
          request.sourceStream,
          GoalRunnerWorkerSubtaskRequestRejectionReason.UNSAFE_PATH,
          "Worker subtask request spec_path must be a safe relative path.",
        )
      existingSubtasks.any { subtask -> subtask.sameRequestedWork(request) } ->
        rejected(
          request.sourceStream,
          GoalRunnerWorkerSubtaskRequestRejectionReason.DUPLICATE,
          "Worker subtask request duplicates existing visible subtask work.",
        )
      request.dependsOnSubtaskIds.any { dependency -> existingSubtasks.none { it.id == dependency } } ->
        rejected(
          request.sourceStream,
          GoalRunnerWorkerSubtaskRequestRejectionReason.UNKNOWN_DEPENDENCY,
          "Worker subtask request references an unknown dependency.",
        )
      request.requiresOperatorConfirmation ->
        GoalRunnerWorkerSubtaskRequestOutcome.RequiresOperatorConfirmation(
          request = request,
          reason = "Worker requested operator confirmation before scheduling additional work.",
        )
      else ->
        GoalRunnerWorkerSubtaskRequestOutcome.Queued(
          request = request,
          reason = "Worker subtask request is valid and awaiting runtime scheduling.",
        )
    }
  }

  private fun rejected(
    sourceStream: String,
    reason: GoalRunnerWorkerSubtaskRequestRejectionReason,
    message: String,
  ): GoalRunnerWorkerSubtaskRequestOutcome.Rejected =
    GoalRunnerWorkerSubtaskRequestOutcome.Rejected(
      sourceStream = sourceStream,
      reason = reason,
      message = message,
    )

  private fun malformed(
    sourceStream: String,
    fieldDescription: String,
  ): GoalRunnerWorkerSubtaskRequestOutcome.Rejected =
    rejected(
      sourceStream,
      GoalRunnerWorkerSubtaskRequestRejectionReason.MALFORMED,
      "Worker subtask request requires $fieldDescription.",
    )
}

private fun Map<String, Any?>.requestKind(): String? =
  this["kind"]?.toString()?.takeIf(String::isNotBlank)
    ?: this["type"]?.toString()?.takeIf(String::isNotBlank)

private fun Map<String, Any?>.dependencyIdsOrNull(): List<Int>? {
  val rawDependencies: Any? =
    when {
      containsKey("depends_on_subtask_ids") -> this["depends_on_subtask_ids"]
      containsKey("dependencies") -> this["dependencies"]
      else -> emptyList<Any?>()
    }

  val dependencies = rawDependencies as? List<*> ?: return null
  var valid = true
  return dependencies
    .mapNotNull { value ->
      value.asExactIntOrNull()?.takeIf { it > 0 } ?: run {
        valid = false
        null
      }
    }
    .distinct()
    .takeIf { valid }
}

private fun requestPayloads(text: String): List<String> = linePayloads(text) + blockPayloads(text)

private fun linePayloads(text: String): List<String> =
  text
    .lineSequence()
    .mapNotNull { line ->
      line.trim()
        .removePrefix(LINE_PREFIX)
        .takeIf { line.trim().startsWith(LINE_PREFIX) }
        ?.trim()
        ?.takeIf(String::isNotBlank)
    }
    .toList()

private fun blockPayloads(text: String): List<String> =
  BLOCK_REGEX
    .findAll(text)
    .map { match -> match.groupValues[1].trim() }
    .filter(String::isNotBlank)
    .toList()

private fun parsePayloadMap(payload: String): Map<String, Any?>? =
  try {
    JsonCodec.anyToStringAnyMap(JsonCodec.parseValue(payload)) ?: run {
      recordDurableDecodeSubstitution(
        seam = "GoalRunnerWorkerSubtaskRequestParser.parsePayloadMap",
        valueUsed = "null",
        expectedValue = "json_object",
        reason = "json_value_was_not_an_object",
      )
      null
    }
  } catch (_: MalformedJsonTextError) {
    recordDurableDecodeSubstitution(
      seam = "GoalRunnerWorkerSubtaskRequestParser.parsePayloadMap",
      valueUsed = "null",
      expectedValue = "json_object",
      reason = "malformed_json",
    )
    null
  }

private data class WorkerOutput(
  val stream: String,
  val text: String,
)

private const val REQUEST_KIND = "skill_bill_subtask_request"
private const val LINE_PREFIX = "SKILL_BILL_SUBTASK_REQUEST:"
private val BLOCK_REGEX =
  Regex(
    "SKILL_BILL_SUBTASK_REQUEST_BEGIN\\s*(.*?)\\s*SKILL_BILL_SUBTASK_REQUEST_END",
    setOf(RegexOption.DOT_MATCHES_ALL),
  )

private fun String.isUnsafeRelativePath(): Boolean {
  val normalized = replace('\\', '/')
  return normalized.isBlank() ||
    normalized.startsWith("/") ||
    normalized.startsWith("~") ||
    normalized.split("/").any { segment -> segment == ".." }
}

private fun DecompositionSubtask.sameRequestedWork(request: GoalRunnerWorkerSubtaskRequest): Boolean =
  name.equals(request.name, ignoreCase = true) || specPath == request.specPath
