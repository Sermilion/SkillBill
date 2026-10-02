package skillbill.workflow.taskruntime.model.persistence

import skillbill.agentaddon.model.AgentAddonSelection
import skillbill.agentaddon.model.PersistedAgentAddonSelectionEntry
import skillbill.contracts.SharedPayloadKeys
import skillbill.contracts.workflow.featuretask.FEATURE_TASK_RUNTIME_RUN_INVARIANTS_CONTRACT_VERSION
import skillbill.error.shellcontent.InvalidFeatureTaskRuntimePhaseHandoffSchemaError
import skillbill.error.shellcontent.InvalidWorkflowStateSchemaError
import skillbill.review.context.model.execution.CodeReviewExecutionMode
import skillbill.workflow.taskruntime.model.handoff.task.FeatureTaskRuntimeFeatureSize
import skillbill.workflow.taskruntime.model.handoff.task.FeatureTaskRuntimeRunInvariants

internal fun FeatureTaskRuntimeRunInvariants.toArtifactMap(): Map<String, Any?> =
  linkedMapOf(
    SharedPayloadKeys.CONTRACT_VERSION to FEATURE_TASK_RUNTIME_RUN_INVARIANTS_CONTRACT_VERSION,
    "spec_reference" to specReference,
    "feature_size" to featureSize.name,
    "acceptance_criteria" to acceptanceCriteria,
    "mandates_and_overrides" to mandatesAndOverrides,
    "code_review_mode" to codeReviewMode.wireValue,
    "agent_addon_selection" to
      agentAddonSelection.entries.map { entry ->
        linkedMapOf(
          "slug" to entry.slug,
          "source_identity" to entry.sourceIdentity,
          "content_sha256" to entry.contentSha256,
        )
      },
  )

internal fun featureTaskRuntimeRunInvariantsFromArtifactMap(raw: Map<String, Any?>): FeatureTaskRuntimeRunInvariants {
  raw.requireRunInvariantsContractVersion()
  val specReference = raw.requireInvariantStringField("spec_reference")
  val featureSize = raw.requireFeatureSizeField("feature_size")
  val acceptanceCriteria = raw.requireInvariantStringListField("acceptance_criteria")
  val mandatesAndOverrides = raw.requireInvariantStringListField("mandates_and_overrides")
  val codeReviewMode = raw.requireCodeReviewModeField("code_review_mode")
  val agentAddonSelection = raw.optionalAgentAddonSelection()
  return try {
    FeatureTaskRuntimeRunInvariants(
      specReference = specReference,
      featureSize = featureSize,
      acceptanceCriteria = acceptanceCriteria,
      mandatesAndOverrides = mandatesAndOverrides,
      codeReviewMode = codeReviewMode,
      agentAddonSelection = agentAddonSelection,
    )
  } catch (error: IllegalArgumentException) {
    throw InvalidWorkflowStateSchemaError("Feature-task-runtime run invariants are invalid: ${error.message}", error)
  }
}

private fun Map<String, Any?>.optionalAgentAddonSelection(): AgentAddonSelection {
  val value = this["agent_addon_selection"] ?: return AgentAddonSelection()
  val entries =
    value as? List<*>
      ?: runInvariantSchemaError("Feature-task-runtime artifact field 'agent_addon_selection' must decode to a list.")
  return try {
    AgentAddonSelection(
      entries.mapIndexed { index, rawEntry ->
        val entry =
          rawEntry as? Map<*, *>
            ?: runInvariantSchemaError("Agent add-on selection entry $index must decode to a map.")
        val keys =
          entry.keys.map {
            it as? String
              ?: runInvariantSchemaError("Agent add-on selection entry $index has a non-string field.")
          }.toSet()
        val expected = setOf("slug", "source_identity", "content_sha256")
        if (keys != expected) {
          runInvariantSchemaError(
            "Agent add-on selection entry $index fields must be exactly ${expected.sorted()}.",
          )
        }
        PersistedAgentAddonSelectionEntry(
          slug =
            entry["slug"] as? String ?: runInvariantSchemaError(
              "Agent add-on selection entry $index slug is invalid.",
            ),
          sourceIdentity =
            entry["source_identity"] as? String
              ?: runInvariantSchemaError(
                "Agent add-on selection entry $index source_identity is invalid.",
              ),
          contentSha256 =
            entry["content_sha256"] as? String
              ?: runInvariantSchemaError(
                "Agent add-on selection entry $index content_sha256 is invalid.",
              ),
        )
      },
    )
  } catch (error: IllegalArgumentException) {
    throw InvalidWorkflowStateSchemaError("Agent add-on selection is invalid: ${error.message}", error)
  }
}

private fun Map<String, Any?>.requireRunInvariantsContractVersion() {
  val declared =
    this[SharedPayloadKeys.CONTRACT_VERSION]
      ?: runInvariantSchemaError(
        "Feature-task-runtime run-invariants artifact is missing 'contract_version'; records written " +
          "before $FEATURE_TASK_RUNTIME_RUN_INVARIANTS_CONTRACT_VERSION carry pre-SKILL-159 " +
          "code_review_mode semantics and must be quarantined and regenerated, not reinterpreted.",
      )
  if (declared != FEATURE_TASK_RUNTIME_RUN_INVARIANTS_CONTRACT_VERSION) {
    runInvariantSchemaError(
      "Feature-task-runtime run-invariants artifact declares contract version '$declared'; this " +
        "runtime only reads $FEATURE_TASK_RUNTIME_RUN_INVARIANTS_CONTRACT_VERSION.",
    )
  }
}

private fun Map<String, Any?>.requireInvariantStringField(key: String): String {
  val value = this[key] ?: runInvariantSchemaError("Feature-task-runtime artifact map is missing field '$key'.")
  return (value as? String)?.takeIf(String::isNotBlank)
    ?: runInvariantSchemaError("Feature-task-runtime artifact field '$key' must decode to a non-blank string.")
}

private fun Map<String, Any?>.requireInvariantStringListField(key: String): List<String> {
  val value =
    this[key]
      ?: runInvariantSchemaError("Feature-task-runtime artifact map is missing required list field '$key'.")
  val list =
    value as? List<*>
      ?: runInvariantSchemaError("Feature-task-runtime artifact field '$key' must decode to a list.")
  return list.map { element ->
    element as? String
      ?: runInvariantSchemaError("Feature-task-runtime artifact field '$key' must contain strings.")
  }
}

private fun Map<String, Any?>.requireFeatureSizeField(key: String): FeatureTaskRuntimeFeatureSize {
  val rawValue = requireInvariantStringField(key)
  return try {
    FeatureTaskRuntimeFeatureSize.fromWire(rawValue)
  } catch (_: InvalidFeatureTaskRuntimePhaseHandoffSchemaError) {
    runInvariantSchemaError("Feature-task-runtime artifact field '$key' must be one of SMALL, MEDIUM, LARGE.")
  }
}

private fun Map<String, Any?>.requireCodeReviewModeField(key: String): CodeReviewExecutionMode =
  try {
    CodeReviewExecutionMode.fromWire(requireInvariantStringField(key))
  } catch (_: IllegalArgumentException) {
    runInvariantSchemaError("Feature-task-runtime artifact field '$key' must be one of auto, inline, delegated.")
  }

private fun runInvariantSchemaError(detail: String): Nothing = throw InvalidWorkflowStateSchemaError(detail)
