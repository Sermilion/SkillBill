package skillbill.infrastructure.sqlite.workflow.artifact

import skillbill.agentaddon.model.AgentAddonSelection
import skillbill.agentaddon.model.PersistedAgentAddonSelectionEntry
import skillbill.contracts.JsonCodec
import skillbill.contracts.SharedPayloadKeys
import skillbill.error.shellcontent.invalidWorkflowStateSchemaError
import skillbill.review.context.model.execution.CodeReviewExecutionMode
import skillbill.workflow.model.ValidationDepth
import skillbill.workflow.taskruntime.model.persistence.FeatureTaskRuntimeGoalContinuationArtifact
import skillbill.workflow.taskruntime.model.persistence.LegacyGoalContinuationMarker
import skillbill.workflow.taskruntime.model.skeleton.FeatureTaskRuntimeQaSelection
import skillbill.workflow.taskruntime.model.skeleton.FeatureTaskRuntimeQualityGateSelection
import skillbill.contracts.workflow.identity.task.FeatureTaskRuntimeGoalContinuationArtifactPayloadKeys as Keys

private const val LEGACY_ENABLED_KEY = "enabled"
private val addonContentSha256 = Regex("[0-9a-f]{64}")
private val addonSlug = Regex("[a-z0-9]+(?:-[a-z0-9]+)*")

private val goalContinuationKeys: Set<String> =
  setOf(
    SharedPayloadKeys.ISSUE_KEY,
    SharedPayloadKeys.SUBTASK_ID,
    Keys.SUPPRESS_PR,
    Keys.GOAL_BRANCH,
    Keys.PARENT_WORKFLOW_ID,
    Keys.CODE_REVIEW_MODE,
    Keys.VALIDATION_DEPTH,
    Keys.QUALITY_GATE_SELECTION,
    Keys.QA_SELECTION,
    Keys.PARALLEL_REVIEW_AGENT,
    Keys.SUBTASK_NAME,
    Keys.AGENT_ADDON_SELECTION,
  )

internal data class GoalContinuationIdentity(
  val parentWorkflowId: String?,
  val subtaskId: Long?,
  val issueKey: String?,
)

internal object GoalContinuationArtifactCodec {
  fun encode(artifact: FeatureTaskRuntimeGoalContinuationArtifact): Map<String, Any?> =
    linkedMapOf<String, Any?>(
      SharedPayloadKeys.ISSUE_KEY to artifact.issueKey,
      SharedPayloadKeys.SUBTASK_ID to artifact.subtaskId,
      Keys.SUPPRESS_PR to artifact.suppressPr,
      Keys.GOAL_BRANCH to artifact.goalBranch,
      Keys.CODE_REVIEW_MODE to artifact.codeReviewMode.wireValue,
    ).apply {
      artifact.parentWorkflowId?.let { put(Keys.PARENT_WORKFLOW_ID, it) }
      artifact.validationDepth?.let { put(Keys.VALIDATION_DEPTH, it.wireValue) }
      artifact.qualityGateSelection?.let { put(Keys.QUALITY_GATE_SELECTION, it.wireValue) }
      artifact.qaSelection?.let { put(Keys.QA_SELECTION, it.wireValue) }
      artifact.subtaskName?.let { put(Keys.SUBTASK_NAME, it) }
      if (artifact.agentAddonSelection.entries.isNotEmpty()) {
        put(
          Keys.AGENT_ADDON_SELECTION,
          artifact.agentAddonSelection.entries.map { entry ->
            linkedMapOf(
              Keys.ADDON_SLUG to entry.slug,
              Keys.ADDON_SOURCE_IDENTITY to entry.sourceIdentity,
              Keys.ADDON_CONTENT_SHA256 to entry.contentSha256,
            )
          },
        )
      }
    }

  fun decode(raw: Map<String, Any?>): FeatureTaskRuntimeGoalContinuationArtifact {
    val reader = ArtifactFieldReader(raw)
    reader.rejectUnknownKeys(goalContinuationKeys, "Goal-continuation artifact")
    val subtaskId = reader.requiredInt(SharedPayloadKeys.SUBTASK_ID)
    if (subtaskId < 1) {
      goalContinuationSchemaError(
        "Goal-continuation artifact field '${SharedPayloadKeys.SUBTASK_ID}' must be positive.",
      )
    }
    return FeatureTaskRuntimeGoalContinuationArtifact(
      issueKey = reader.requiredString(SharedPayloadKeys.ISSUE_KEY),
      subtaskId = subtaskId,
      suppressPr = reader.requiredBoolean(Keys.SUPPRESS_PR),
      goalBranch = reader.requiredString(Keys.GOAL_BRANCH),
      parentWorkflowId = reader.optionalString(Keys.PARENT_WORKFLOW_ID),
      codeReviewMode =
        reader.requiredString(Keys.CODE_REVIEW_MODE).let { value ->
          CodeReviewExecutionMode.entries.firstOrNull { it.wireValue == value }
            ?: goalContinuationSchemaError(
              "Goal-continuation artifact code_review_mode has unsupported value '$value'.",
            )
        },
      validationDepth =
        reader.optionalString(Keys.VALIDATION_DEPTH)?.let { value ->
          ValidationDepth.fromWireOrNull(value)
            ?: goalContinuationSchemaError("Goal-continuation artifact validation_depth is invalid.")
        },
      qualityGateSelection =
        reader.optionalString(Keys.QUALITY_GATE_SELECTION)?.let { value ->
          FeatureTaskRuntimeQualityGateSelection.entries.firstOrNull { it.wireValue == value }
            ?: goalContinuationSchemaError("Goal-continuation artifact quality_gate_selection is invalid.")
        },
      qaSelection =
        reader.optionalString(Keys.QA_SELECTION)?.let { value ->
          FeatureTaskRuntimeQaSelection.entries.firstOrNull { it.wireValue == value }
            ?: goalContinuationSchemaError("Goal-continuation artifact qa_selection is invalid.")
        },
      parallelReviewAgent = reader.optionalString(Keys.PARALLEL_REVIEW_AGENT),
      subtaskName = reader.optionalString(Keys.SUBTASK_NAME),
      agentAddonSelection = decodeAddonSelection(raw[Keys.AGENT_ADDON_SELECTION]),
    )
  }

  fun legacyMarker(raw: Map<String, Any?>): LegacyGoalContinuationMarker? {
    if (raw[LEGACY_ENABLED_KEY] != true) return null
    val issueKey = (raw[SharedPayloadKeys.ISSUE_KEY] as? String)?.takeIf(String::isNotBlank) ?: return null
    val subtaskId = (raw[SharedPayloadKeys.SUBTASK_ID] as? Number)?.toInt()?.takeIf { it > 0 } ?: return null
    return LegacyGoalContinuationMarker(issueKey, subtaskId)
  }

  fun lenientIdentity(jsonText: String): GoalContinuationIdentity? {
    val raw = JsonCodec.anyToStringAnyMap(JsonCodec.parseValue(jsonText)) ?: return null
    return GoalContinuationIdentity(
      parentWorkflowId = raw[Keys.PARENT_WORKFLOW_ID] as? String,
      subtaskId = (raw[SharedPayloadKeys.SUBTASK_ID] as? Number)?.takeIf { it is Int || it is Long }?.toLong(),
      issueKey = raw[SharedPayloadKeys.ISSUE_KEY] as? String,
    )
  }

  private fun decodeAddonSelection(raw: Any?): AgentAddonSelection {
    if (raw == null) return AgentAddonSelection()
    val entries =
      raw as? List<*>
        ?: goalContinuationSchemaError("Goal-continuation agent_addon_selection must be a list.")
    val parsed = entries.mapIndexed(::decodeAddonEntry)
    if (parsed.map { it.slug }.distinct().size != parsed.size) {
      goalContinuationSchemaError(
        "Goal-continuation agent_addon_selection must not contain duplicate slugs.",
      )
    }
    return AgentAddonSelection(parsed)
  }

  private fun decodeAddonEntry(
    index: Int,
    value: Any?,
  ): PersistedAgentAddonSelectionEntry {
    val entry =
      value as? Map<*, *>
        ?: goalContinuationSchemaError("Goal-continuation agent_addon_selection entry $index is invalid.")
    if (entry.keys != setOf(Keys.ADDON_SLUG, Keys.ADDON_SOURCE_IDENTITY, Keys.ADDON_CONTENT_SHA256)) {
      goalContinuationSchemaError(
        "Goal-continuation agent_addon_selection entry $index has invalid fields.",
      )
    }
    val slug =
      (entry[Keys.ADDON_SLUG] as? String)?.takeIf(String::isNotBlank)
        ?: goalContinuationSchemaError("Goal-continuation add-on entry $index is missing slug.")
    val sourceIdentity =
      (entry[Keys.ADDON_SOURCE_IDENTITY] as? String)?.takeIf(String::isNotBlank)
        ?: goalContinuationSchemaError("Goal-continuation add-on entry $index is missing source_identity.")
    val contentSha256 =
      (entry[Keys.ADDON_CONTENT_SHA256] as? String)?.takeIf { it.matches(addonContentSha256) }
        ?: goalContinuationSchemaError(
          "Goal-continuation add-on entry $index has an invalid content_sha256.",
        )
    if (!slug.matches(addonSlug)) {
      goalContinuationSchemaError("Goal-continuation add-on entry $index has an invalid slug.")
    }
    return PersistedAgentAddonSelectionEntry(slug, sourceIdentity, contentSha256)
  }
}

private fun goalContinuationSchemaError(detail: String): Nothing = throw invalidWorkflowStateSchemaError(detail)
