package skillbill.contracts.decomposition

import skillbill.contracts.JsonPayloadContract
import skillbill.error.shellcontent.WorkflowFailureCode
import skillbill.error.shellcontent.invalidDecompositionManifestSchema
import java.math.BigDecimal
import java.math.BigInteger

data class DecompositionPlanningDependencyWire(
  val subtaskId: Int,
  val optional: Boolean = false,
  val skipped: Boolean = false,
)

data class DecompositionPlanningSubtaskWire(
  val id: Int,
  val name: String,
  val specPath: String,
  val linearIssueId: String? = null,
  val scope: String? = null,
  val dependencies: List<DecompositionPlanningDependencyWire> = emptyList(),
)

data class DecompositionPlanningStackBranchWire(
  val subtaskId: Int,
  val branch: String,
  val baseBranch: String,
)

data class DecompositionPlanningResult(
  val mode: String,
  val parentSpecPath: String? = null,
  val specSourceWire: String? = null,
  val executionModelWire: String? = null,
  val baseBranch: String? = null,
  val currentSubtaskId: Int? = null,
  val recommendedFirstSubtaskId: Int? = null,
  val stackBranches: List<DecompositionPlanningStackBranchWire> = emptyList(),
  val subtasks: List<DecompositionPlanningSubtaskWire>,
) : JsonPayloadContract {
  fun isDecomposeMode(): Boolean = mode == "decompose"

  override fun toPayload(): Map<String, Any?> =
    linkedMapOf<String, Any?>(
      DecompositionPlanningPayloadKeys.MODE to mode,
      DecompositionPlanningPayloadKeys.SUBTASKS to subtasks.map(DecompositionPlanningSubtaskWire::toPayload),
    ).apply {
      parentSpecPath?.let { put(DecompositionPlanningPayloadKeys.PARENT_SPEC_PATH, it) }
      specSourceWire?.let { put(DecompositionPlanningPayloadKeys.SPEC_SOURCE, it) }
      executionModelWire?.let { put(DecompositionPlanningPayloadKeys.EXECUTION_MODEL, it) }
      baseBranch?.let { put(DecompositionPlanningPayloadKeys.BASE_BRANCH, it) }
      currentSubtaskId?.let { put(DecompositionPlanningPayloadKeys.CURRENT_SUBTASK_ID, it) }
      recommendedFirstSubtaskId?.let {
        put(DecompositionPlanningPayloadKeys.RECOMMENDED_FIRST_SUBTASK_ID, it)
      }
      if (stackBranches.isNotEmpty()) {
        put(
          DecompositionPlanningPayloadKeys.STACK_BRANCHES,
          stackBranches.map(DecompositionPlanningStackBranchWire::toPayload),
        )
      }
    }

  companion object {
    fun fromWireMap(
      wireMap: Map<String, Any?>,
      sourceLabel: String = "<planning-result>",
    ): DecompositionPlanningResult {
      val mode = wireMap.stringValue(DecompositionPlanningPayloadKeys.MODE, sourceLabel)
      if (mode != "decompose") {
        return DecompositionPlanningResult(mode = mode, subtasks = emptyList())
      }
      val rawSubtasks = wireMap.listValue(DecompositionPlanningPayloadKeys.SUBTASKS, sourceLabel)
      if (rawSubtasks.isEmpty()) {
        invalidPlanning(sourceLabel, "decomposition planning result must contain at least one subtask.")
      }
      val subtasks =
        rawSubtasks.mapIndexed { index, raw ->
          raw.asMap(sourceLabel, "${DecompositionPlanningPayloadKeys.SUBTASKS}[$index]")
            .toPlanningSubtask(sourceLabel, index)
        }
      return DecompositionPlanningResult(
        mode = mode,
        parentSpecPath = wireMap.nullableStringValue(DecompositionPlanningPayloadKeys.PARENT_SPEC_PATH, sourceLabel),
        specSourceWire = wireMap.nullableStringValue(DecompositionPlanningPayloadKeys.SPEC_SOURCE, sourceLabel),
        executionModelWire = wireMap.nullableStringValue(DecompositionPlanningPayloadKeys.EXECUTION_MODEL, sourceLabel),
        baseBranch = wireMap.nullableStringValue(DecompositionPlanningPayloadKeys.BASE_BRANCH, sourceLabel),
        currentSubtaskId = wireMap.optionalIntValue(DecompositionPlanningPayloadKeys.CURRENT_SUBTASK_ID, sourceLabel),
        recommendedFirstSubtaskId =
          wireMap.optionalIntValue(DecompositionPlanningPayloadKeys.RECOMMENDED_FIRST_SUBTASK_ID, sourceLabel),
        stackBranches = wireMap.parseStackBranches(sourceLabel),
        subtasks = subtasks,
      )
    }
  }
}

private fun DecompositionPlanningSubtaskWire.toPayload(): Map<String, Any?> =
  linkedMapOf(
    DecompositionPlanningPayloadKeys.ID to id,
    DecompositionPlanningPayloadKeys.NAME to name,
    DecompositionPlanningPayloadKeys.SPEC_PATH to specPath,
    DecompositionPlanningPayloadKeys.LINEAR_ISSUE_ID to linearIssueId,
    DecompositionPlanningPayloadKeys.SCOPE to scope,
    DecompositionPlanningPayloadKeys.DEPENDENCIES to
      dependencies.map { dependency ->
        linkedMapOf(
          DecompositionPlanningPayloadKeys.SUBTASK_ID to dependency.subtaskId,
          DecompositionPlanningPayloadKeys.OPTIONAL to dependency.optional,
          DecompositionPlanningPayloadKeys.SKIPPED to dependency.skipped,
        )
      },
  )

private fun DecompositionPlanningStackBranchWire.toPayload(): Map<String, Any?> =
  linkedMapOf(
    DecompositionPlanningPayloadKeys.SUBTASK_ID to subtaskId,
    DecompositionPlanningPayloadKeys.BRANCH to branch,
    DecompositionPlanningPayloadKeys.BASE_BRANCH to baseBranch,
  )

private fun Map<String, Any?>.toPlanningSubtask(
  sourceLabel: String,
  index: Int,
): DecompositionPlanningSubtaskWire {
  val name =
    when {
      containsKey(DecompositionPlanningPayloadKeys.TITLE) ->
        stringValue(DecompositionPlanningPayloadKeys.TITLE, sourceLabel, "$index.title")
      containsKey(DecompositionPlanningPayloadKeys.NAME) ->
        stringValue(DecompositionPlanningPayloadKeys.NAME, sourceLabel, "$index.name")
      else -> invalidPlanning(sourceLabel, "subtasks[$index].name must be present.")
    }
  val dependenciesRaw =
    this[DecompositionPlanningPayloadKeys.DEPENDENCIES]
      ?: this[DecompositionPlanningPayloadKeys.DEPENDS_ON]
  return DecompositionPlanningSubtaskWire(
    id = intValue(DecompositionPlanningPayloadKeys.ID, sourceLabel, "subtasks[$index].id"),
    name = name,
    specPath = stringValue(DecompositionPlanningPayloadKeys.SPEC_PATH, sourceLabel, "$index.spec_path"),
    linearIssueId = nullableStringValue(DecompositionPlanningPayloadKeys.LINEAR_ISSUE_ID, sourceLabel, index),
    scope = nullableStringValue(DecompositionPlanningPayloadKeys.SCOPE, sourceLabel),
    dependencies = parsePlanningDependencies(dependenciesRaw, sourceLabel, index),
  )
}

private fun Map<String, Any?>.parseStackBranches(sourceLabel: String): List<DecompositionPlanningStackBranchWire> =
  listValue(DecompositionPlanningPayloadKeys.STACK_BRANCHES, sourceLabel).mapIndexed { index, raw ->
    val item = raw.asMap(sourceLabel, "${DecompositionPlanningPayloadKeys.STACK_BRANCHES}[$index]")
    DecompositionPlanningStackBranchWire(
      subtaskId =
        item.intValue(
          DecompositionPlanningPayloadKeys.SUBTASK_ID,
          sourceLabel,
          "${DecompositionPlanningPayloadKeys.STACK_BRANCHES}[$index].subtask_id",
        ),
      branch = item.stringValue(DecompositionPlanningPayloadKeys.BRANCH, sourceLabel, "$index.branch"),
      baseBranch = item.stringValue(DecompositionPlanningPayloadKeys.BASE_BRANCH, sourceLabel, "$index.base_branch"),
    )
  }

private fun parsePlanningDependencies(
  raw: Any?,
  sourceLabel: String,
  subtaskIndex: Int,
): List<DecompositionPlanningDependencyWire> {
  if (raw == null) return emptyList()
  val dependencies =
    raw as? List<*>
      ?: invalidPlanning(sourceLabel, "subtasks[$subtaskIndex].dependencies must be a list.")
  return dependencies.mapIndexed { depIndex, value ->
    when (value) {
      is Map<*, *> -> {
        val dependency = value.asMap(sourceLabel, "subtasks[$subtaskIndex].dependencies[$depIndex]")
        DecompositionPlanningDependencyWire(
          subtaskId =
            dependency.intValue(
              DecompositionPlanningPayloadKeys.SUBTASK_ID,
              sourceLabel,
              "subtasks[$subtaskIndex].dependencies[$depIndex].subtask_id",
            ),
          optional = dependency.booleanValueOrDefault(DecompositionPlanningPayloadKeys.OPTIONAL, false, sourceLabel),
          skipped = dependency.booleanValueOrDefault(DecompositionPlanningPayloadKeys.SKIPPED, false, sourceLabel),
        )
      }
      else ->
        DecompositionPlanningDependencyWire(
          subtaskId = value.asInt(sourceLabel, "subtasks[$subtaskIndex].dependencies[$depIndex]"),
        )
    }
  }
}

private fun Map<String, Any?>.stringValue(
  key: String,
  sourceLabel: String,
): String =
  (this[key] as? String)?.takeIf(String::isNotBlank)
    ?: invalidPlanning(sourceLabel, "$key must be a nonblank string.")

private fun Map<String, Any?>.stringValue(
  key: String,
  sourceLabel: String,
  fieldPath: String,
): String =
  (this[key] as? String)?.takeIf(String::isNotBlank)
    ?: invalidPlanning(sourceLabel, "$fieldPath must be a nonblank string.")

private fun Map<String, Any?>.nullableStringValue(
  key: String,
  sourceLabel: String,
): String? =
  when (val value = this[key]) {
    null -> null
    is String -> value
    else -> invalidPlanning(sourceLabel, "$key must be a nonblank string when present.")
  }

private fun Map<String, Any?>.nullableStringValue(
  key: String,
  sourceLabel: String,
  subtaskIndex: Int,
): String? =
  when (val raw = this[key]) {
    null -> null
    is String -> raw
    else -> invalidPlanning(sourceLabel, "subtasks[$subtaskIndex].$key must be a string when present.")
  }

private fun Map<String, Any?>.optionalIntValue(
  key: String,
  sourceLabel: String,
): Int? = if (containsKey(key)) intValue(key, sourceLabel, key) else null

private fun Map<String, Any?>.intValue(
  key: String,
  sourceLabel: String,
  fieldPath: String,
): Int = this[key].asInt(sourceLabel, fieldPath)

private fun Map<String, Any?>.booleanValueOrDefault(
  key: String,
  default: Boolean,
  sourceLabel: String,
): Boolean =
  when (val value = this[key]) {
    null -> default
    is Boolean -> value
    else -> invalidPlanning(sourceLabel, "$key must be a boolean.")
  }

private fun Map<String, Any?>.listValue(
  key: String,
  sourceLabel: String,
): List<Any?> {
  if (!containsKey(key) || this[key] == null) {
    return emptyList()
  }
  return (this[key] as? List<*>)
    ?: invalidPlanning(sourceLabel, "$key must be a list.")
}

private fun Any?.asMap(
  sourceLabel: String,
  fieldPath: String,
): Map<String, Any?> =
  (this as? Map<*, *>)?.entries?.associateTo(LinkedHashMap<String, Any?>()) { (key, value) ->
    val stringKey = key as? String ?: invalidPlanning(sourceLabel, "$fieldPath contains a non-string key.")
    stringKey to value
  } ?: invalidPlanning(sourceLabel, "$fieldPath must be an object.")

private fun Any?.asInt(
  sourceLabel: String,
  fieldPath: String,
): Int =
  when (this) {
    is Byte -> toInt()
    is Short -> toInt()
    is Int -> this
    is Long -> takeIf { it in Int.MIN_VALUE.toLong()..Int.MAX_VALUE.toLong() }?.toInt()
    is BigInteger -> runCatching { intValueExact() }.getOrNull()
    is BigDecimal -> runCatching { intValueExact() }.getOrNull()
    is Float, is Double -> {
      val doubleValue = (this as Number).toDouble()
      runCatching {
        require(doubleValue.isFinite())
        require(doubleValue >= Int.MIN_VALUE.toDouble())
        require(doubleValue <= Int.MAX_VALUE.toDouble())
        BigDecimal.valueOf(doubleValue).intValueExact()
      }.getOrNull()?.takeIf { this is Double || it.toFloat() == this }
    }
    else -> null
  } ?: invalidPlanning(sourceLabel, "$fieldPath must be an integer.")

private fun invalidPlanning(
  sourceLabel: String,
  reason: String,
): Nothing =
  throw invalidDecompositionManifestSchema(
    sourceLabel = sourceLabel,
    reason = reason,
    code = WorkflowFailureCode.DECOMPOSITION_MANIFEST_INVALID_SHAPE,
  )
