package skillbill.workflow.taskruntime.model.handoff.task

import skillbill.agent.model.PhaseOutput
import skillbill.agentaddon.model.AgentAddonSelection
import skillbill.contracts.JsonCodec
import skillbill.contracts.SharedPayloadKeys
import skillbill.contracts.workflow.featuretask.FEATURE_TASK_RUNTIME_CONTRACT_VERSION
import skillbill.error.shellcontent.InvalidFeatureTaskRuntimePhaseHandoffSchemaError
import skillbill.error.shellcontent.InvalidFeatureTaskRuntimePhaseOutputSchemaError
import skillbill.review.context.model.execution.CodeReviewExecutionMode
import skillbill.workflow.model.persistence.artifact.durableArtifactMapReader
import skillbill.workflow.taskruntime.model.core.FeatureTaskRuntimeWorkflowArtifactMap
import skillbill.workflow.taskruntime.model.core.MAX_ACCEPTANCE_CRITERION_ORDINAL

data class FeatureTaskRuntimeRunInvariants(
  val specReference: String,
  val featureSize: FeatureTaskRuntimeFeatureSize = FeatureTaskRuntimeFeatureSize.DEFAULT,
  val acceptanceCriteria: List<String>,
  val mandatesAndOverrides: List<String>,
  val codeReviewMode: CodeReviewExecutionMode = CodeReviewExecutionMode.DEFAULT,
  val agentAddonSelection: AgentAddonSelection = AgentAddonSelection(),
) {
  init {
    require(specReference.isNotBlank()) {
      "FeatureTaskRuntimeRunInvariants.specReference must be a non-blank spec reference; " +
        "run-invariants cannot be partially specified."
    }
    require(acceptanceCriteria.isNotEmpty()) {
      "FeatureTaskRuntimeRunInvariants.acceptanceCriteria must list at least one criterion; " +
        "a run with no acceptance criteria has no contract to satisfy."
    }
    require(acceptanceCriteria.none(String::isBlank)) {
      "FeatureTaskRuntimeRunInvariants.acceptanceCriteria must not contain blank entries."
    }
    require(acceptanceCriteria.size <= MAX_ACCEPTANCE_CRITERION_ORDINAL) {
      "FeatureTaskRuntimeRunInvariants.acceptanceCriteria supports at most " +
        "$MAX_ACCEPTANCE_CRITERION_ORDINAL criteria, had ${acceptanceCriteria.size}."
    }
  }
}

enum class FeatureTaskRuntimeFeatureSize {
  SMALL,
  MEDIUM,
  LARGE,
  ;

  companion object {
    val DEFAULT: FeatureTaskRuntimeFeatureSize = MEDIUM

    fun fromWire(value: String): FeatureTaskRuntimeFeatureSize =
      entries.firstOrNull { it.name == value.trim().uppercase() }
        ?: throw InvalidFeatureTaskRuntimePhaseHandoffSchemaError(
          sourceLabel = "<wire>",
          reason = "Unknown feature-task-runtime feature size '$value'.",
        )
  }
}

enum class FeatureTaskRuntimePreplanCeremony(val wireValue: String, val promptLabel: String) {
  LIGHT("light", "lighter preplan focused on the current unit of work"),
  FULL("full", "full preplan covering boundaries, risks, rollout, and unknowns"),
}

enum class FeatureTaskRuntimeReviewScope(val wireValue: String, val promptLabel: String) {
  CURRENT_UNIT_OF_WORK("current_unit_of_work", "current-unit-of-work review scope"),
  BRANCH_DIFF("branch_diff", "branch-diff review scope"),
}

enum class FeatureTaskRuntimeAuditCeremony(val wireValue: String, val promptLabel: String) {
  LIGHT("light", "lighter audit over the current unit of work and every listed criterion"),
  FULL_PER_CRITERION("full_per_criterion", "full per-criterion completeness audit"),
}

data class FeatureTaskRuntimeCeremonyScaling(
  val preplanCeremony: FeatureTaskRuntimePreplanCeremony,
  val reviewScope: FeatureTaskRuntimeReviewScope,
  val auditCeremony: FeatureTaskRuntimeAuditCeremony,
) {
  fun toBriefingLines(): List<String> =
    listOf(
      "preplan_ceremony: ${preplanCeremony.wireValue} (${preplanCeremony.promptLabel})",
      "review_scope: ${reviewScope.wireValue} (${reviewScope.promptLabel})",
      "audit_ceremony: ${auditCeremony.wireValue} (${auditCeremony.promptLabel})",
    )
}

data class NormalizedFeatureTaskRuntimePhaseOutput(
  val phaseId: String,
  val status: String,
  val summary: String,
  val output: PhaseOutput,
  val verdict: String? = null,
  val failureDisposition: String? = null,
  internal val runtimeRecord: Map<String, Any?> = emptyMap(),
  internal val historicalRecord: Map<String, Any?>? = null,
) {
  internal val envelope: Map<String, Any?>
    get() = recordView()

  val canonicalJson: String
    get() = JsonCodec.mapToJsonString(historicalRecord ?: recordView())

  internal fun envelopePayload(): Any = recordView()

  private fun recordView(): Map<String, Any?> {
    val record = linkedMapOf<String, Any?>()
    record[SharedPayloadKeys.CONTRACT_VERSION] =
      runtimeRecord[SharedPayloadKeys.CONTRACT_VERSION] ?: FEATURE_TASK_RUNTIME_CONTRACT_VERSION
    record[SharedPayloadKeys.PHASE_ID] = phaseId
    if (status.isNotEmpty()) record[SharedPayloadKeys.STATUS] = status
    if (summary.isNotEmpty()) record[SharedPayloadKeys.SUMMARY] = summary
    record[SharedPayloadKeys.PRODUCED_OUTPUTS] = producedOutputsView()
    verdict?.let { record[SharedPayloadKeys.VERDICT] = it }
    failureDisposition?.let { record[SharedPayloadKeys.FAILURE_DISPOSITION] = it }
    runtimeRecord.forEach { (key, value) ->
      if (key != SharedPayloadKeys.CONTRACT_VERSION && key != SharedPayloadKeys.PRODUCED_OUTPUTS) record[key] = value
    }
    return record
  }

  private fun producedOutputsView(): Any? {
    val stored = runtimeRecord[SharedPayloadKeys.PRODUCED_OUTPUTS]
    if (stored != null && stored !is Map<*, *>) return stored
    val produced = linkedMapOf<String, Any?>()
    if (output.value.isNotEmpty()) produced[SharedPayloadKeys.VALUE] = output.value
    output.prompt?.takeIf(String::isNotBlank)?.let { produced[SharedPayloadKeys.PROMPT] = it }
    stored?.forEach { (key, value) -> produced.putIfAbsent(key.toString(), value) }
    return produced
  }

  companion object {
    private val CORE_KEYS: Set<String> =
      setOf(
        SharedPayloadKeys.PHASE_ID,
        SharedPayloadKeys.STATUS,
        SharedPayloadKeys.SUMMARY,
        SharedPayloadKeys.VERDICT,
        SharedPayloadKeys.FAILURE_DISPOSITION,
      )

    fun fromEnvelopeText(
      text: String,
      sourceLabel: String,
    ): NormalizedFeatureTaskRuntimePhaseOutput {
      val record =
        JsonCodec
          .parseObjectOrNull(text)
          ?.let(JsonCodec::jsonElementToValue)
          ?.let(JsonCodec::anyToStringAnyMap)
          ?: throw InvalidFeatureTaskRuntimePhaseOutputSchemaError(
            sourceLabel = sourceLabel,
            reason = "must be a JSON object.",
          )
      unsupportedHistoricalShape(record)?.let { reason ->
        throw InvalidFeatureTaskRuntimePhaseOutputSchemaError(sourceLabel = sourceLabel, reason = reason)
      }
      return fromRecordMap(FeatureTaskRuntimeWorkflowArtifactMap.from(record))
    }

    private fun unsupportedHistoricalShape(record: Map<String, Any?>): String? {
      val produced = record[SharedPayloadKeys.PRODUCED_OUTPUTS]
      val value = (produced as? Map<*, *>)?.get(SharedPayloadKeys.VALUE)
      val prompt = (produced as? Map<*, *>)?.get(SharedPayloadKeys.PROMPT)
      return when {
        produced == null -> null
        produced !is Map<*, *> -> "produced_outputs must be an object in a supported record."
        value != null && value !is String -> "produced_outputs.value must be a string in a supported record."
        prompt != null && prompt !is String -> "produced_outputs.prompt must be a string in a supported record."
        else -> null
      }
    }

    fun fromRecordMap(record: FeatureTaskRuntimeWorkflowArtifactMap): NormalizedFeatureTaskRuntimePhaseOutput {
      val produced = (record[SharedPayloadKeys.PRODUCED_OUTPUTS] as? Map<*, *>)?.mapKeys { it.key.toString() }
      val remainder = LinkedHashMap(record.filterKeys { it !in CORE_KEYS })
      if (produced != null) {
        remainder[SharedPayloadKeys.PRODUCED_OUTPUTS] =
          produced.filterKeys { it != SharedPayloadKeys.VALUE && it != SharedPayloadKeys.PROMPT }
      }
      return NormalizedFeatureTaskRuntimePhaseOutput(
        phaseId = record[SharedPayloadKeys.PHASE_ID]?.toString().orEmpty(),
        status = record[SharedPayloadKeys.STATUS]?.toString().orEmpty(),
        summary = record[SharedPayloadKeys.SUMMARY]?.toString().orEmpty(),
        output = durableArtifactMapReader(record).historicalPhaseOutput(),
        verdict = record[SharedPayloadKeys.VERDICT]?.toString(),
        failureDisposition = record[SharedPayloadKeys.FAILURE_DISPOSITION]?.toString(),
        runtimeRecord = remainder,
        historicalRecord = record,
      )
    }
  }
}
