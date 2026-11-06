package skillbill.workflow.taskruntime.model.core

import skillbill.contracts.workflow.featuretask.PLAN_FAN_OUT_HISTORY_CONTRACT_VERSION
import skillbill.error.core.SkillBillRuntimeException
import skillbill.error.shellcontent.FeatureTaskRuntimeFailureCode
import skillbill.error.shellcontent.invalidWorkflowStateSchemaError
import skillbill.workflow.model.persistence.artifact.DurableArtifactMapReader
import skillbill.workflow.model.persistence.artifact.durableArtifactMapReader

object FeatureTaskRuntimePlanFanOutHistoryKeys {
  const val ATTEMPT = "attempt"
  const val CAPACITY = "capacity"
  const val CONTRACT_VERSION = "contract_version"
  const val HISTORY_COMPLETE = "history_complete"
  const val ORDINAL = "ordinal"
  const val UNIT_IDS = "unit_ids"
  const val WAVES = "waves"
}

private fun invalidPlanFanOutHistory(message: String): Nothing =
  throw SkillBillRuntimeException(FeatureTaskRuntimeFailureCode.INVALID_PLAN_FAN_OUT_HISTORY_SCHEMA, message)

data class FeatureTaskRuntimePlanFanOutWave(
  val attempt: Int,
  val ordinal: Int?,
  val capacity: Int,
  val unitIds: List<Int>,
) {
  init {
    if (attempt <= 0 || capacity <= 0 || ordinal?.let { it <= 0 } == true) {
      invalidPlanFanOutHistory("Plan fan-out wave numbers must be positive.")
    }
    if (unitIds.isEmpty() || unitIds.size > capacity || unitIds.any { it <= 0 }) {
      invalidPlanFanOutHistory("Plan fan-out wave unit ids must be positive and fit its capacity.")
    }
    if (unitIds.distinct().size != unitIds.size) {
      invalidPlanFanOutHistory("Plan fan-out wave unit ids must be unique.")
    }
  }

  internal fun toArtifactMap(): Map<String, Any?> =
    linkedMapOf(
      FeatureTaskRuntimePlanFanOutHistoryKeys.ATTEMPT to attempt,
      FeatureTaskRuntimePlanFanOutHistoryKeys.ORDINAL to ordinal,
      FeatureTaskRuntimePlanFanOutHistoryKeys.CAPACITY to capacity,
      FeatureTaskRuntimePlanFanOutHistoryKeys.UNIT_IDS to unitIds,
    )

  companion object {
    internal fun fromArtifactMap(raw: Map<String, Any?>): FeatureTaskRuntimePlanFanOutWave {
      val reader = DurableArtifactMapReader(raw, ::invalidPlanFanOutHistory)
      return FeatureTaskRuntimePlanFanOutWave(
        reader.requiredInt(FeatureTaskRuntimePlanFanOutHistoryKeys.ATTEMPT),
        reader.optionalInt(FeatureTaskRuntimePlanFanOutHistoryKeys.ORDINAL),
        reader.requiredInt(FeatureTaskRuntimePlanFanOutHistoryKeys.CAPACITY),
        reader.requiredList(FeatureTaskRuntimePlanFanOutHistoryKeys.UNIT_IDS).mapIndexed { index, value ->
          (value as? Number)?.toInt()?.takeIf { value.toDouble() == it.toDouble() }
            ?: invalidPlanFanOutHistory("Invalid plan fan-out wave unit id at index $index.")
        },
      )
    }
  }
}

data class FeatureTaskRuntimePlanFanOutHistory(
  val waves: List<FeatureTaskRuntimePlanFanOutWave>,
  val complete: Boolean = true,
) {
  init {
    val validOrdinals =
      if (complete) {
        waves.map { it.ordinal } == waves.indices.map { it + 1 }
      } else {
        waves.all { it.ordinal == null }
      }
    if (!validOrdinals) {
      invalidPlanFanOutHistory("Plan fan-out wave ordinals must be contiguous and ordered.")
    }
  }

  fun record(
    attempt: Int,
    capacity: Int,
    unitIds: List<Int>,
  ): FeatureTaskRuntimePlanFanOutHistory {
    val repeated = waves.firstOrNull { it.attempt == attempt && it.unitIds == unitIds }
    if (repeated != null) {
      if (repeated.capacity != capacity) invalidPlanFanOutHistory("A repeated plan fan-out wave changed capacity.")
      return this
    }
    val ordinal = if (complete) waves.size + 1 else null
    return copy(waves = waves + FeatureTaskRuntimePlanFanOutWave(attempt, ordinal, capacity, unitIds))
  }

  internal fun toArtifactMap(): Map<String, Any?> =
    linkedMapOf(
      FeatureTaskRuntimePlanFanOutHistoryKeys.CONTRACT_VERSION to PLAN_FAN_OUT_HISTORY_CONTRACT_VERSION,
      FeatureTaskRuntimePlanFanOutHistoryKeys.HISTORY_COMPLETE to complete,
      FeatureTaskRuntimePlanFanOutHistoryKeys.WAVES to waves.map { it.toArtifactMap() },
    )

  companion object {
    internal fun fromArtifactMap(raw: Map<String, Any?>): FeatureTaskRuntimePlanFanOutHistory {
      val reader = DurableArtifactMapReader(raw, ::invalidPlanFanOutHistory)
      if (
        reader.requiredString(FeatureTaskRuntimePlanFanOutHistoryKeys.CONTRACT_VERSION) !=
        PLAN_FAN_OUT_HISTORY_CONTRACT_VERSION
      ) {
        invalidPlanFanOutHistory("Plan fan-out history has an unsupported contract version.")
      }
      return FeatureTaskRuntimePlanFanOutHistory(
        reader.requiredList(FeatureTaskRuntimePlanFanOutHistoryKeys.WAVES).mapIndexed { index, value ->
          FeatureTaskRuntimePlanFanOutWave.fromArtifactMap(
            reader.nestedObjectFromValue(value, "${FeatureTaskRuntimePlanFanOutHistoryKeys.WAVES}[$index]"),
          )
        },
        reader.requiredBoolean(FeatureTaskRuntimePlanFanOutHistoryKeys.HISTORY_COMPLETE),
      )
    }
  }
}

enum class FeatureTaskRuntimePlanBundleOrigin(val wireValue: String) {
  AUTHORED("authored"),
  EXISTING("existing"),
}

data class FeatureTaskRuntimePlanBundleSubtask(
  val id: Int,
  val specPath: String,
  val scopeSha256: String,
  val detailsSha256: String? = null,
  val planText: String? = null,
) {
  init {
    require(id > 0) { "FeatureTaskRuntimePlanBundleSubtask.id must be positive." }
    require(specPath.isNotBlank()) { "FeatureTaskRuntimePlanBundleSubtask.specPath must be non-blank." }
    require(scopeSha256.isNotBlank()) { "FeatureTaskRuntimePlanBundleSubtask.scopeSha256 must be non-blank." }
    require(detailsSha256 == null || detailsSha256.isNotBlank()) {
      "FeatureTaskRuntimePlanBundleSubtask.detailsSha256 must be non-blank when present."
    }
  }

  val planned: Boolean get() = detailsSha256 != null

  internal fun toArtifactMap(): Map<String, Any?> =
    linkedMapOf(
      ID to id,
      SPEC_PATH to specPath,
      SCOPE_SHA256 to scopeSha256,
      DETAILS_SHA256 to detailsSha256,
      PLAN_TEXT to planText,
    )

  companion object {
    private const val ID = "id"
    private const val SPEC_PATH = "spec_path"
    private const val SCOPE_SHA256 = "scope_sha256"
    private const val DETAILS_SHA256 = "details_sha256"
    private const val PLAN_TEXT = "plan_text"

    internal fun fromArtifactMap(raw: Map<String, Any?>): FeatureTaskRuntimePlanBundleSubtask {
      val reader = durableArtifactMapReader(raw)
      return FeatureTaskRuntimePlanBundleSubtask(
        id = reader.requiredInt(ID),
        specPath = reader.requiredString(SPEC_PATH),
        scopeSha256 = reader.requiredString(SCOPE_SHA256),
        detailsSha256 = reader.optionalString(DETAILS_SHA256),
        planText = reader.optionalString(PLAN_TEXT),
      )
    }
  }
}

data class FeatureTaskRuntimePlanBundle(
  val origin: FeatureTaskRuntimePlanBundleOrigin,
  val parentSpecSha256: String,
  val manifestSha256: String,
  val subtasks: List<FeatureTaskRuntimePlanBundleSubtask>,
) {
  init {
    require(parentSpecSha256.isNotBlank()) { "FeatureTaskRuntimePlanBundle.parentSpecSha256 must be non-blank." }
    require(manifestSha256.isNotBlank()) { "FeatureTaskRuntimePlanBundle.manifestSha256 must be non-blank." }
    require(subtasks.map(FeatureTaskRuntimePlanBundleSubtask::id).distinct().size == subtasks.size) {
      "FeatureTaskRuntimePlanBundle subtask ids must be unique."
    }
  }

  fun subtask(id: Int): FeatureTaskRuntimePlanBundleSubtask? = subtasks.firstOrNull { it.id == id }

  fun withSubtask(updated: FeatureTaskRuntimePlanBundleSubtask): FeatureTaskRuntimePlanBundle =
    copy(subtasks = subtasks.map { if (it.id == updated.id) updated else it })

  fun withoutPlannedUnits(ids: Set<Int>): FeatureTaskRuntimePlanBundle =
    copy(
      subtasks =
        subtasks.map { if (it.id in ids) it.copy(detailsSha256 = null, planText = null) else it },
    )

  internal fun toArtifactMap(): Map<String, Any?> =
    linkedMapOf(
      ORIGIN to origin.wireValue,
      PARENT_SPEC_SHA256 to parentSpecSha256,
      MANIFEST_SHA256 to manifestSha256,
      SUBTASKS to subtasks.map(FeatureTaskRuntimePlanBundleSubtask::toArtifactMap),
    )

  companion object {
    private const val ORIGIN = "origin"
    private const val PARENT_SPEC_SHA256 = "parent_spec_sha256"
    private const val MANIFEST_SHA256 = "manifest_sha256"
    private const val SUBTASKS = "subtasks"

    internal fun fromArtifactMap(raw: Map<String, Any?>): FeatureTaskRuntimePlanBundle {
      val reader = durableArtifactMapReader(raw)
      val origin =
        FeatureTaskRuntimePlanBundleOrigin.entries.firstOrNull { it.wireValue == reader.requiredString(ORIGIN) }
          ?: throw invalidWorkflowStateSchemaError("Feature-task-runtime plan bundle has an unknown origin.")
      val subtasks =
        reader.requiredList(SUBTASKS).mapIndexed { index, entry ->
          FeatureTaskRuntimePlanBundleSubtask.fromArtifactMap(reader.nestedObjectFromValue(entry, "$SUBTASKS[$index]"))
        }
      return FeatureTaskRuntimePlanBundle(
        origin = origin,
        parentSpecSha256 = reader.requiredString(PARENT_SPEC_SHA256),
        manifestSha256 = reader.requiredString(MANIFEST_SHA256),
        subtasks = subtasks,
      )
    }
  }
}
