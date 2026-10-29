package dev.skillbill.desktop.core.data.config

import dev.skillbill.desktop.core.domain.model.CompactionConfig
import dev.skillbill.desktop.core.domain.model.ConfigDocument
import dev.skillbill.desktop.core.domain.model.ConfigFingerprint
import dev.skillbill.desktop.core.domain.model.ConfigWriteOutcome
import dev.skillbill.desktop.core.domain.model.ExecutionMatrixConfig
import dev.skillbill.desktop.core.domain.model.MachineConfig
import dev.skillbill.desktop.core.domain.model.MachineConfigChange
import dev.skillbill.desktop.core.domain.model.ModelDirective
import dev.skillbill.desktop.core.domain.model.PhaseCompaction
import dev.skillbill.desktop.core.domain.model.ProjectConfig
import dev.skillbill.desktop.core.domain.model.ProjectConfigChange
import dev.skillbill.desktop.core.domain.model.ReviewBudget
import dev.skillbill.desktop.core.domain.model.SpecSource
import dev.skillbill.desktop.core.domain.model.Tier
import skillbill.application.config.model.ConfigDocumentRead
import skillbill.application.config.model.ConfigWriteResult
import skillbill.application.config.model.MachineConfigEdit
import skillbill.application.config.model.MachineConfigValues
import skillbill.application.config.model.ProjectConfigEdit
import skillbill.application.config.model.ProjectConfigValues
import skillbill.config.model.CompactionSettings
import skillbill.config.model.ExecutionMatrix
import skillbill.config.model.ExecutionTier
import skillbill.config.model.PhaseModelDirective
import skillbill.config.model.SpecType
import skillbill.review.context.model.accounting.ReviewContextBudgetPolicy
import skillbill.ports.config.model.ConfigFingerprint as RuntimeFingerprint

internal fun <V, D> ConfigDocumentRead<V>.toDomain(values: (V) -> D): ConfigDocument<D> = when (this) {
  is ConfigDocumentRead.Absent -> ConfigDocument.Absent(path.toString())
  is ConfigDocumentRead.Unreadable -> ConfigDocument.Unreadable(path.toString(), keyPath, reason)
  is ConfigDocumentRead.Present -> ConfigDocument.Present(path.toString(), fingerprint.toDomain(), values(this.values), hasComments)
}

internal fun ConfigWriteResult.toDomain(): ConfigWriteOutcome = when (this) {
  is ConfigWriteResult.Written -> ConfigWriteOutcome.Written(fingerprint.toDomain())
  is ConfigWriteResult.Invalid -> ConfigWriteOutcome.Invalid(keyPath, reason)
  is ConfigWriteResult.ChangedOnDisk -> ConfigWriteOutcome.ChangedOnDisk
  is ConfigWriteResult.Unreadable -> ConfigWriteOutcome.Unreadable(reason)
}

internal fun RuntimeFingerprint.toDomain() = ConfigFingerprint(sha256Hex)

internal fun ConfigFingerprint.toRuntime() = RuntimeFingerprint(value)

internal fun MachineConfigValues.toDomain() = MachineConfig(
  executionMatrix = executionMatrix?.toDomain(),
  compaction = compaction.toDomain(),
  telemetryProxyUrl = telemetryProxyUrl,
  telemetryBatchSize = telemetryBatchSize,
)

internal fun ProjectConfigValues.toDomain() = ProjectConfig(
  specSource = specType?.let {
    when (it) {
      SpecType.LOCAL -> SpecSource.LOCAL
      SpecType.LINEAR -> SpecSource.LINEAR
    }
  },
  gradleWrapper = gradleWrapper,
  reviewBudget = reviewContextBudget.toDomain(),
)

internal fun MachineConfigChange.toRuntime(): MachineConfigEdit = when (this) {
  is MachineConfigChange.SetExecutionMatrix -> MachineConfigEdit.SetExecutionMatrix(section)
  is MachineConfigChange.SetCompaction -> MachineConfigEdit.SetCompaction(section)
  is MachineConfigChange.SetTelemetryProxyUrl -> MachineConfigEdit.SetTelemetryProxyUrl(value)
  is MachineConfigChange.SetTelemetryBatchSize -> MachineConfigEdit.SetTelemetryBatchSize(value)
}

internal fun ProjectConfigChange.toRuntime(): ProjectConfigEdit = when (this) {
  is ProjectConfigChange.SetSpecType -> ProjectConfigEdit.SetSpecType(
    when (source) {
      SpecSource.LOCAL -> SpecType.LOCAL
      SpecSource.LINEAR -> SpecType.LINEAR
    },
  )
  is ProjectConfigChange.SetGradleWrapper -> ProjectConfigEdit.SetGradleWrapper(value)
  is ProjectConfigChange.SetReviewContextBudget -> ProjectConfigEdit.SetReviewContextBudget(section)
}

private fun ExecutionMatrix.toDomain() = ExecutionMatrixConfig(
  phaseTiers = phaseTiers.mapValues { it.value.toDomain() },
  agents = agents.entries.associate { (agent, tiers) ->
    agent.id to tiers.entries.associate { (tier, directive) -> tier.toDomain() to directive.toDomain() }
  },
  agentPhaseOverrides = agentPhaseOverrides.entries.associate { (agent, phases) -> agent.id to phases.mapValues { it.value.toDomain() } },
)

internal fun ExecutionTier.toDomain(): Tier = when (this) {
  ExecutionTier.REASONING -> Tier.REASONING
  ExecutionTier.IMPLEMENTATION -> Tier.IMPLEMENTATION
}

internal fun PhaseModelDirective.toDomain() = ModelDirective(model, effort)

internal fun CompactionSettings.toDomain() = CompactionConfig(
  enabled = enabled,
  windowTokens = windowTokens,
  triggerPct = triggerPct,
  phases = phases.mapValues { PhaseCompaction(it.value.windowTokens, it.value.triggerPct) },
)

internal fun ReviewContextBudgetPolicy.toDomain() = ReviewBudget(
  maxParentPacketBytes = maxParentPacketBytes,
  maxLaneLaunchBytes = maxLaneLaunchBytes,
  maxLaneEvidenceBytes = maxLaneEvidenceBytes,
  maxEvidenceResultBytes = maxEvidenceResultBytes,
  maxLaneResultBytes = maxLaneResultBytes,
  maxAssignmentExpansions = maxAssignmentExpansions,
  maxSpecialistToolCalls = maxSpecialistToolCalls,
  maxSpecialistModelTurns = maxSpecialistModelTurns,
  maxRoutingAnalysisPairs = maxRoutingAnalysisPairs,
  maxRoutingAnalysisBytes = maxRoutingAnalysisBytes,
  maxSpecIntentProjectionBytes = maxSpecIntentProjectionBytes,
)
