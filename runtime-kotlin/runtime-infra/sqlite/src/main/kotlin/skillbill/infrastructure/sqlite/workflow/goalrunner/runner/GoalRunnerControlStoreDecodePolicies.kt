package skillbill.infrastructure.sqlite.workflow.goalrunner.runner

import kotlinx.serialization.SerializationException
import kotlinx.serialization.json.JsonElement
import skillbill.agentaddon.model.AgentAddonSelection
import skillbill.agentaddon.model.PersistedAgentAddonSelectionEntry
import skillbill.contracts.JsonCodec
import skillbill.contracts.SharedPayloadKeys
import skillbill.contracts.decomposition.DecompositionManifestPayloadKeys
import skillbill.contracts.workflow.identity.task.FeatureTaskRuntimeGoalContinuationArtifactPayloadKeys
import skillbill.error.shellcontent.InvalidWorkflowStateSchemaError
import skillbill.ports.goalrunner.runner.model.GoalRunnerOutOfBandAcceptance
import skillbill.ports.goalrunner.runner.model.GoalRunnerReviewPolicy
import skillbill.review.context.model.execution.CodeReviewExecutionMode
import kotlin.coroutines.cancellation.CancellationException

internal fun decodeReviewPolicy(raw: String): GoalRunnerReviewPolicy {
  val policy =
    JsonCodec.parseObjectOrNull(raw)
      ?.let(JsonCodec::jsonElementToValue)
      ?.let(JsonCodec::anyToStringAnyMap)
      ?: goalRunnerControlSchemaError("review policy durable record must be an object.")
  val mode =
    policy[FeatureTaskRuntimeGoalContinuationArtifactPayloadKeys.CODE_REVIEW_MODE] as? String
      ?: goalRunnerControlSchemaError("review policy durable record is missing code_review_mode.")
  val codeReviewMode =
    try {
      CodeReviewExecutionMode.fromWire(mode)
    } catch (error: IllegalArgumentException) {
      goalRunnerControlSchemaError("review policy durable record has invalid code_review_mode: ${error.message}")
    }
  val selection =
    try {
      AgentAddonSelection(decodeReviewPolicyAddons(policy))
    } catch (error: IllegalArgumentException) {
      goalRunnerControlSchemaError("review policy durable record has invalid add-on selection: ${error.message}")
    }
  return GoalRunnerReviewPolicy(codeReviewMode, selection)
}

internal fun decodeAcceptances(raw: String): Map<Int, GoalRunnerOutOfBandAcceptance> =
  parseAcceptanceList(raw).associate(::decodeAcceptanceEntry)

private fun decodeReviewPolicyAddons(policy: Map<String, Any?>): List<PersistedAgentAddonSelectionEntry> {
  val raw =
    policy[FeatureTaskRuntimeGoalContinuationArtifactPayloadKeys.AGENT_ADDON_SELECTION] ?: return emptyList()
  val values =
    raw as? List<*>
      ?: goalRunnerControlSchemaError("review policy durable record agent_addon_selection must be a list.")
  return values.mapIndexed { index, value -> decodeReviewPolicyAddonEntry(index, value) }
}

private fun decodeReviewPolicyAddonEntry(
  index: Int,
  value: Any?,
): PersistedAgentAddonSelectionEntry {
  val entry =
    JsonCodec.anyToStringAnyMap(value)
      ?: goalRunnerControlSchemaError("review policy durable add-on entry $index must be a map.")
  val slug =
    requireReviewPolicyAddonField(entry, index, FeatureTaskRuntimeGoalContinuationArtifactPayloadKeys.ADDON_SLUG)
  val sourceIdentity =
    requireReviewPolicyAddonField(
      entry,
      index,
      FeatureTaskRuntimeGoalContinuationArtifactPayloadKeys.ADDON_SOURCE_IDENTITY,
    )
  val contentSha256 =
    requireReviewPolicyAddonField(
      entry,
      index,
      FeatureTaskRuntimeGoalContinuationArtifactPayloadKeys.ADDON_CONTENT_SHA256,
    )
  return try {
    PersistedAgentAddonSelectionEntry(slug = slug, sourceIdentity = sourceIdentity, contentSha256 = contentSha256)
  } catch (error: IllegalArgumentException) {
    goalRunnerControlSchemaError("review policy durable add-on entry $index is invalid: ${error.message}")
  }
}

private fun requireReviewPolicyAddonField(
  entry: Map<String, Any?>,
  index: Int,
  key: String,
): String =
  entry[key] as? String
    ?: goalRunnerControlSchemaError("review policy durable add-on entry $index is missing $key.")

private fun parseAcceptanceList(raw: String): List<*> {
  val values = JsonCodec.jsonElementToValue(parseAcceptanceJsonElement(raw)) as? List<*>
  return values ?: goalRunnerControlSchemaError("acceptance durable record must be a list.")
}

private fun parseAcceptanceJsonElement(raw: String): JsonElement =
  try {
    JsonCodec.json.parseToJsonElement(raw)
  } catch (error: CancellationException) {
    throw error
  } catch (error: SerializationException) {
    invalidAcceptanceJson(error)
  } catch (error: IllegalArgumentException) {
    invalidAcceptanceJson(error)
  }

private fun invalidAcceptanceJson(cause: Throwable): Nothing =
  throw InvalidWorkflowStateSchemaError(
    "Goal runner control state: acceptance durable record is not valid JSON.",
    cause,
  )

private fun decodeAcceptanceEntry(value: Any?): Pair<Int, GoalRunnerOutOfBandAcceptance> {
  val entry =
    JsonCodec.anyToStringAnyMap(value)
      ?: goalRunnerControlSchemaError("acceptance durable record entries must be maps.")
  val acceptance =
    GoalRunnerOutOfBandAcceptance(
      subtaskId = requireAcceptanceInt(entry, SharedPayloadKeys.SUBTASK_ID),
      commitSha = requireAcceptanceString(entry, DecompositionManifestPayloadKeys.COMMIT_SHA),
      reason =
        requireAcceptanceString(entry, FeatureTaskRuntimeGoalContinuationArtifactPayloadKeys.ACCEPTANCE_REASON),
      acceptedAt =
        requireAcceptanceString(entry, FeatureTaskRuntimeGoalContinuationArtifactPayloadKeys.ACCEPTED_AT),
    )
  return acceptance.subtaskId to acceptance
}

private fun requireAcceptanceInt(
  entry: Map<String, Any?>,
  key: String,
): Int =
  entry[key].exactPositiveSubtaskIdOrNull()
    ?: goalRunnerControlSchemaError("acceptance durable record entry $key must be a positive integer.")

private fun requireAcceptanceString(
  entry: Map<String, Any?>,
  key: String,
): String =
  (entry[key] as? String)?.takeIf(String::isNotBlank)
    ?: goalRunnerControlSchemaError("acceptance durable record entry is missing a nonblank $key.")
