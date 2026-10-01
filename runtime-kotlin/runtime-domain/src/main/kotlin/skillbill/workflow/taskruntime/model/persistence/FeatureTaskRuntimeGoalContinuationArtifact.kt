package skillbill.workflow.taskruntime.model.persistence

import skillbill.agentaddon.model.AgentAddonSelection
import skillbill.agentaddon.model.PersistedAgentAddonSelectionEntry
import skillbill.contracts.JsonCodec
import skillbill.contracts.SharedPayloadKeys
import skillbill.contracts.scaffold.wire.optionalString
import skillbill.contracts.workflow.identity.task.FeatureTaskRuntimeGoalContinuationArtifactPayloadKeys
import skillbill.error.shellcontent.InvalidWorkflowStateSchemaError
import skillbill.goalrunner.model.GoalContinuation
import skillbill.review.context.model.execution.CodeReviewExecutionMode
import skillbill.workflow.engine.model.DurableWorkflowArtifacts
import skillbill.workflow.engine.model.FEATURE_TASK_RUNTIME_GOAL_CONTINUATION_ARTIFACT_KEY
import skillbill.workflow.model.ValidationDepth
import skillbill.workflow.model.persistence.artifact.durableArtifactMapReader
import skillbill.workflow.taskruntime.model.core.FeatureTaskRuntimeWorkflowArtifactMap
import skillbill.workflow.taskruntime.model.skeleton.FeatureTaskRuntimeQualityGateSelection

fun DurableWorkflowArtifacts.goalContinuationArtifact(): FeatureTaskRuntimeGoalContinuationArtifact? {
  if (!containsKey(FEATURE_TASK_RUNTIME_GOAL_CONTINUATION_ARTIFACT_KEY)) return null
  val raw =
    JsonCodec.anyToStringAnyMap(this[FEATURE_TASK_RUNTIME_GOAL_CONTINUATION_ARTIFACT_KEY])
      ?: throw InvalidWorkflowStateSchemaError(
        "Goal-continuation artifact must decode to an object.",
      )
  return FeatureTaskRuntimeGoalContinuationArtifact.fromArtifactMap(raw)
}

fun DurableWorkflowArtifacts.goalContinuation(): GoalContinuation? =
  goalContinuationArtifact()?.let { artifact ->
    GoalContinuation(
      issueKey = artifact.issueKey,
      subtaskId = artifact.subtaskId,
      suppressPr = artifact.suppressPr,
      goalBranch = artifact.goalBranch,
    )
  }

internal fun DurableWorkflowArtifacts.hasGoalContinuationMarker(): Boolean {
  if (!containsKey(FEATURE_TASK_RUNTIME_GOAL_CONTINUATION_ARTIFACT_KEY)) return false
  val raw = JsonCodec.anyToStringAnyMap(this[FEATURE_TASK_RUNTIME_GOAL_CONTINUATION_ARTIFACT_KEY])
  if (raw?.get("enabled") == true && SharedPayloadKeys.ISSUE_KEY in raw && SharedPayloadKeys.SUBTASK_ID in raw) {
    return true
  }
  return goalContinuationArtifact() != null
}

data class FeatureTaskRuntimeGoalContinuationArtifact(
  val issueKey: String,
  val subtaskId: Int,
  val suppressPr: Boolean,
  val goalBranch: String,
  val parentWorkflowId: String? = null,
  val codeReviewMode: CodeReviewExecutionMode,
  val validationDepth: ValidationDepth? = null,
  val qualityGateSelection: FeatureTaskRuntimeQualityGateSelection? = null,
  val parallelReviewAgent: String? = null,
  val subtaskName: String? = null,
  val agentAddonSelection: AgentAddonSelection = AgentAddonSelection(),
) {
  init {
    require(issueKey.isNotBlank()) { "FeatureTaskRuntimeGoalContinuationArtifact.issueKey must be non-blank." }
    require(subtaskId > 0) { "FeatureTaskRuntimeGoalContinuationArtifact.subtaskId must be positive." }
    require(goalBranch.isNotBlank()) { "FeatureTaskRuntimeGoalContinuationArtifact.goalBranch must be non-blank." }
    parallelReviewAgent?.let {
      require(it.isNotBlank()) { "FeatureTaskRuntimeGoalContinuationArtifact.parallelReviewAgent must be non-blank." }
    }
    subtaskName?.let {
      require(it.isNotBlank()) { "FeatureTaskRuntimeGoalContinuationArtifact.subtaskName must be non-blank." }
    }
  }

  internal fun toArtifactMap(): Map<String, Any?> =
    linkedMapOf<String, Any?>(
      SharedPayloadKeys.ISSUE_KEY to issueKey,
      SharedPayloadKeys.SUBTASK_ID to subtaskId,
      FeatureTaskRuntimeGoalContinuationArtifactPayloadKeys.SUPPRESS_PR to suppressPr,
      FeatureTaskRuntimeGoalContinuationArtifactPayloadKeys.GOAL_BRANCH to goalBranch,
      FeatureTaskRuntimeGoalContinuationArtifactPayloadKeys.CODE_REVIEW_MODE to codeReviewMode.wireValue,
    ).apply {
      parentWorkflowId?.let { put(FeatureTaskRuntimeGoalContinuationArtifactPayloadKeys.PARENT_WORKFLOW_ID, it) }
      validationDepth?.let { put(FeatureTaskRuntimeGoalContinuationArtifactPayloadKeys.VALIDATION_DEPTH, it.wireValue) }
      qualityGateSelection?.let {
        put(FeatureTaskRuntimeGoalContinuationArtifactPayloadKeys.QUALITY_GATE_SELECTION, it.wireValue)
      }
      subtaskName?.let { put(FeatureTaskRuntimeGoalContinuationArtifactPayloadKeys.SUBTASK_NAME, it) }
      if (agentAddonSelection.entries.isNotEmpty()) {
        put(
          FeatureTaskRuntimeGoalContinuationArtifactPayloadKeys.AGENT_ADDON_SELECTION,
          agentAddonSelection.entries.map { entry ->
            linkedMapOf(
              FeatureTaskRuntimeGoalContinuationArtifactPayloadKeys.ADDON_SLUG to entry.slug,
              FeatureTaskRuntimeGoalContinuationArtifactPayloadKeys.ADDON_SOURCE_IDENTITY to entry.sourceIdentity,
              FeatureTaskRuntimeGoalContinuationArtifactPayloadKeys.ADDON_CONTENT_SHA256 to entry.contentSha256,
            )
          },
        )
      }
    }

  fun toWorkflowArtifactPatch(): FeatureTaskRuntimeWorkflowArtifactMap =
    FeatureTaskRuntimeWorkflowArtifactMap.from(
      mapOf(FEATURE_TASK_RUNTIME_GOAL_CONTINUATION_ARTIFACT_KEY to toArtifactMap()),
    )

  companion object {
    internal fun fromArtifactMap(raw: Map<String, Any?>): FeatureTaskRuntimeGoalContinuationArtifact {
      rejectUnknownGoalContinuationKeys(raw)
      val reader = durableArtifactMapReader(raw)
      val subtaskId = reader.requiredInt(SharedPayloadKeys.SUBTASK_ID)
      if (subtaskId < 1) {
        throw InvalidWorkflowStateSchemaError(
          "Goal-continuation artifact field '${SharedPayloadKeys.SUBTASK_ID}' must be positive.",
        )
      }
      return FeatureTaskRuntimeGoalContinuationArtifact(
        issueKey = reader.requiredString(SharedPayloadKeys.ISSUE_KEY),
        subtaskId = subtaskId,
        suppressPr =
          reader.optionalBoolean(FeatureTaskRuntimeGoalContinuationArtifactPayloadKeys.SUPPRESS_PR)
            ?: throw InvalidWorkflowStateSchemaError(
              "Goal-continuation artifact field " +
                "'${FeatureTaskRuntimeGoalContinuationArtifactPayloadKeys.SUPPRESS_PR}' must be a boolean.",
            ),
        goalBranch = reader.requiredString(FeatureTaskRuntimeGoalContinuationArtifactPayloadKeys.GOAL_BRANCH),
        parentWorkflowId =
          reader.optionalString(
            FeatureTaskRuntimeGoalContinuationArtifactPayloadKeys.PARENT_WORKFLOW_ID,
          ),
        codeReviewMode =
          reader.requiredString(
            FeatureTaskRuntimeGoalContinuationArtifactPayloadKeys.CODE_REVIEW_MODE,
          ).let { rawValue ->
            CodeReviewExecutionMode.entries.firstOrNull { it.wireValue == rawValue }
              ?: goalContinuationSchemaError(
                "Goal-continuation artifact code_review_mode has unsupported value '$rawValue'.",
              )
          },
        validationDepth =
          reader.optionalString(
            FeatureTaskRuntimeGoalContinuationArtifactPayloadKeys.VALIDATION_DEPTH,
          )?.let { rawValue ->
            try {
              ValidationDepth.fromWire(rawValue)
            } catch (error: IllegalArgumentException) {
              goalContinuationSchemaError("Goal-continuation artifact validation_depth is invalid.", error)
            }
          },
        qualityGateSelection =
          reader.optionalString(
            FeatureTaskRuntimeGoalContinuationArtifactPayloadKeys.QUALITY_GATE_SELECTION,
          )?.let { rawValue ->
            FeatureTaskRuntimeQualityGateSelection.entries.firstOrNull { it.wireValue == rawValue }
              ?: goalContinuationSchemaError("Goal-continuation artifact quality_gate_selection is invalid.")
          },
        parallelReviewAgent =
          reader.optionalString(
            FeatureTaskRuntimeGoalContinuationArtifactPayloadKeys.PARALLEL_REVIEW_AGENT,
          ),
        subtaskName = reader.optionalString(FeatureTaskRuntimeGoalContinuationArtifactPayloadKeys.SUBTASK_NAME),
        agentAddonSelection = raw.optionalGoalAgentAddonSelection(),
      )
    }
  }
}

private val goalContinuationKeys: Set<String> =
  setOf(
    SharedPayloadKeys.ISSUE_KEY,
    SharedPayloadKeys.SUBTASK_ID,
    FeatureTaskRuntimeGoalContinuationArtifactPayloadKeys.SUPPRESS_PR,
    FeatureTaskRuntimeGoalContinuationArtifactPayloadKeys.GOAL_BRANCH,
    FeatureTaskRuntimeGoalContinuationArtifactPayloadKeys.PARENT_WORKFLOW_ID,
    FeatureTaskRuntimeGoalContinuationArtifactPayloadKeys.CODE_REVIEW_MODE,
    FeatureTaskRuntimeGoalContinuationArtifactPayloadKeys.VALIDATION_DEPTH,
    FeatureTaskRuntimeGoalContinuationArtifactPayloadKeys.QUALITY_GATE_SELECTION,
    FeatureTaskRuntimeGoalContinuationArtifactPayloadKeys.PARALLEL_REVIEW_AGENT,
    FeatureTaskRuntimeGoalContinuationArtifactPayloadKeys.SUBTASK_NAME,
    FeatureTaskRuntimeGoalContinuationArtifactPayloadKeys.AGENT_ADDON_SELECTION,
  )

private fun Map<String, Any?>.optionalGoalAgentAddonSelection(): AgentAddonSelection {
  val rawEntries =
    this[FeatureTaskRuntimeGoalContinuationArtifactPayloadKeys.AGENT_ADDON_SELECTION]
      ?: return AgentAddonSelection()
  val entries =
    rawEntries as? List<*>
      ?: goalContinuationSchemaError("Goal-continuation agent_addon_selection must be a list.")
  val parsed = entries.mapIndexed(::parseGoalAgentAddonEntry)
  if (parsed.map { it.slug }.distinct().size != parsed.size) {
    goalContinuationSchemaError("Goal-continuation agent_addon_selection must not contain duplicate slugs.")
  }
  return AgentAddonSelection(parsed)
}

private fun parseGoalAgentAddonEntry(
  index: Int,
  value: Any?,
): PersistedAgentAddonSelectionEntry {
  val entry =
    value as? Map<*, *>
      ?: goalContinuationSchemaError("Goal-continuation agent_addon_selection entry $index is invalid.")
  if (entry.keys !=
    setOf(
      FeatureTaskRuntimeGoalContinuationArtifactPayloadKeys.ADDON_SLUG,
      FeatureTaskRuntimeGoalContinuationArtifactPayloadKeys.ADDON_SOURCE_IDENTITY,
      FeatureTaskRuntimeGoalContinuationArtifactPayloadKeys.ADDON_CONTENT_SHA256,
    )
  ) {
    goalContinuationSchemaError("Goal-continuation agent_addon_selection entry $index has invalid fields.")
  }
  val slug =
    (entry[FeatureTaskRuntimeGoalContinuationArtifactPayloadKeys.ADDON_SLUG] as? String)
      ?.takeIf(String::isNotBlank)
      ?: goalContinuationSchemaError("Goal-continuation add-on entry $index is missing slug.")
  val sourceIdentity =
    (entry[FeatureTaskRuntimeGoalContinuationArtifactPayloadKeys.ADDON_SOURCE_IDENTITY] as? String)
      ?.takeIf(String::isNotBlank)
      ?: goalContinuationSchemaError("Goal-continuation add-on entry $index is missing source_identity.")
  val contentSha256 =
    (entry[FeatureTaskRuntimeGoalContinuationArtifactPayloadKeys.ADDON_CONTENT_SHA256] as? String)
      ?.takeIf { it.matches(Regex("[0-9a-f]{64}")) }
      ?: goalContinuationSchemaError("Goal-continuation add-on entry $index has an invalid content_sha256.")
  if (!slug.matches(Regex("[a-z0-9]+(?:-[a-z0-9]+)*"))) {
    goalContinuationSchemaError("Goal-continuation add-on entry $index has an invalid slug.")
  }
  return PersistedAgentAddonSelectionEntry(slug, sourceIdentity, contentSha256)
}

private fun goalContinuationSchemaError(
  detail: String,
  cause: Throwable? = null,
): Nothing {
  throw InvalidWorkflowStateSchemaError(detail, cause)
}

private fun rejectUnknownGoalContinuationKeys(raw: Map<String, Any?>) {
  raw.keys.firstOrNull { it !in goalContinuationKeys }?.let { key ->
    throw InvalidWorkflowStateSchemaError("Goal-continuation artifact field '$key' is not supported.")
  }
}
