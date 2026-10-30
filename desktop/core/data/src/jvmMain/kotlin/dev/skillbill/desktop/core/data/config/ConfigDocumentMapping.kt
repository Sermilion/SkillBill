package dev.skillbill.desktop.core.data.config

import dev.skillbill.desktop.core.domain.model.AuthoredCompactionField
import dev.skillbill.desktop.core.domain.model.AuthoredCompactionFields
import dev.skillbill.desktop.core.domain.model.AuthoredPhaseCompactionFields
import dev.skillbill.desktop.core.domain.model.AuthoredReviewContextBudget
import dev.skillbill.desktop.core.domain.model.AuthoredReviewContextBudgetEntry
import dev.skillbill.desktop.core.domain.model.CompactionConfig
import dev.skillbill.desktop.core.domain.model.ConfigDocument
import dev.skillbill.desktop.core.domain.model.ConfigFingerprint
import dev.skillbill.desktop.core.domain.model.ConfigWriteOutcome
import dev.skillbill.desktop.core.domain.model.ExecutionMatrixConfig
import dev.skillbill.desktop.core.domain.model.MachineConfig
import dev.skillbill.desktop.core.domain.model.MachineConfigAgentPhaseOverrideEdit
import dev.skillbill.desktop.core.domain.model.MachineConfigAgentTierDirectiveEdit
import dev.skillbill.desktop.core.domain.model.MachineConfigCompactionEditIntent
import dev.skillbill.desktop.core.domain.model.MachineConfigCompactionField
import dev.skillbill.desktop.core.domain.model.MachineConfigChange
import dev.skillbill.desktop.core.domain.model.MachineConfigExecutionMatrixEditIntent
import dev.skillbill.desktop.core.domain.model.MachineConfigPhaseTierEdit
import dev.skillbill.desktop.core.domain.model.ModelDirective
import dev.skillbill.desktop.core.domain.model.PhaseCompaction
import dev.skillbill.desktop.core.domain.model.ProjectConfig
import dev.skillbill.desktop.core.domain.model.ProjectConfigChange
import dev.skillbill.desktop.core.domain.model.ReviewBudget
import dev.skillbill.desktop.core.domain.model.SpecSource
import dev.skillbill.desktop.core.domain.model.Tier
import skillbill.application.config.model.ConfigDocumentRead
import skillbill.application.config.model.ConfigDocumentSnapshotRead
import skillbill.application.config.model.ConfigWriteResult
import skillbill.application.config.model.CompactionEditField
import skillbill.application.config.model.CompactionEditIntent
import skillbill.application.config.model.ExecutionMatrixAgentPhaseOverrideEdit
import skillbill.application.config.model.ExecutionMatrixAgentTierDirectiveEdit
import skillbill.application.config.model.ExecutionMatrixEditIntent
import skillbill.application.config.model.ExecutionMatrixPhaseTierEdit
import skillbill.application.config.model.MachineConfigEdit
import skillbill.application.config.model.MachineConfigValues
import skillbill.application.config.model.ProjectConfigEdit
import skillbill.application.config.model.ProjectConfigValues
import skillbill.config.model.CompactionField
import skillbill.config.model.CompactionSettings
import skillbill.config.model.CompactionSettingsFields
import skillbill.config.model.ExecutionMatrix
import skillbill.config.model.ExecutionTier
import skillbill.config.model.PhaseCompactionFields
import skillbill.config.model.PhaseModelDirective
import skillbill.config.model.SpecType
import skillbill.review.context.model.accounting.ReviewContextBudgetPolicy
import skillbill.ports.config.model.ConfigFingerprint as RuntimeFingerprint
import java.nio.file.Path
import java.util.Collections

internal fun <V, D> ConfigDocumentSnapshotRead<V>.toDomain(values: (V) -> D): ConfigDocument<D> = when (val read = document) {
  is ConfigDocumentRead.Absent -> ConfigDocument.Absent(
    read.path.toString(),
    candidatePaths.map(Path::toString),
    projectStorePath?.toString(),
  )
  is ConfigDocumentRead.Unreadable -> ConfigDocument.Unreadable(
    read.path.toString(),
    read.keyPath,
    read.reason,
    candidatePaths.map(Path::toString),
    projectStorePath?.toString(),
  )
  is ConfigDocumentRead.Present -> ConfigDocument.Present(
    read.path.toString(),
    read.fingerprint.toDomain(),
    values(read.values),
    read.hasComments,
    candidatePaths.map(Path::toString),
    projectStorePath?.toString(),
  )
}

internal fun ConfigWriteResult.toDomain(): ConfigWriteOutcome = when (this) {
  is ConfigWriteResult.Written -> ConfigWriteOutcome.Written(fingerprint.toDomain())
  is ConfigWriteResult.Invalid -> ConfigWriteOutcome.Invalid(keyPath, reason)
  is ConfigWriteResult.ChangedOnDisk -> ConfigWriteOutcome.ChangedOnDisk
  is ConfigWriteResult.Unreadable -> ConfigWriteOutcome.Unreadable(reason, keyPath, path.toString())
}

internal fun RuntimeFingerprint.toDomain() = ConfigFingerprint(sha256Hex)

internal fun ConfigFingerprint.toRuntime() = RuntimeFingerprint(value)

internal fun MachineConfigValues.toDomain() = MachineConfig(
  executionMatrix = executionMatrix?.toDomain(),
  compaction = compaction.toDomain(),
  telemetryProxyUrl = telemetryProxyUrl,
  telemetryBatchSize = telemetryBatchSize,
  telemetryLevel = telemetryLevel,
  telemetryInstallId = telemetryInstallId,
  compactionFields = compactionFields.toDomain(),
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
  authoredReviewContextBudget = AuthoredReviewContextBudget(
    authoredReviewContextBudget.present,
    Collections.unmodifiableList(
      authoredReviewContextBudget.tree.orderedEntries().map { (key, value) ->
        AuthoredReviewContextBudgetEntry(key, value)
      },
    ),
  ),
)

internal fun MachineConfigChange.toRuntime(): MachineConfigEdit = when (this) {
  is MachineConfigChange.SetExecutionMatrix -> MachineConfigEdit.SetExecutionMatrix(section, editIntent?.toRuntime())
  is MachineConfigChange.SetCompaction -> MachineConfigEdit.SetCompaction(section, editIntent?.toRuntime())
  is MachineConfigChange.SetTelemetryProxyUrl -> MachineConfigEdit.SetTelemetryProxyUrl(value)
  is MachineConfigChange.SetTelemetryBatchSize -> MachineConfigEdit.SetTelemetryBatchSize(value)
}

private fun MachineConfigCompactionEditIntent.toRuntime() = CompactionEditIntent(
  globalFields = globalFields.map { it.toRuntime() }.toSet(),
  phaseFields = phaseFields.mapValues { (_, fields) -> fields.map { it.toRuntime() }.toSet() },
  addedPhaseIds = addedPhaseIds,
  removedPhaseIds = removedPhaseIds,
)

private fun MachineConfigCompactionField.toRuntime(): CompactionEditField = when (this) {
  MachineConfigCompactionField.ENABLED -> CompactionEditField.ENABLED
  MachineConfigCompactionField.WINDOW_TOKENS -> CompactionEditField.WINDOW_TOKENS
  MachineConfigCompactionField.TRIGGER_PCT -> CompactionEditField.TRIGGER_PCT
}

private fun MachineConfigExecutionMatrixEditIntent.toRuntime() = ExecutionMatrixEditIntent(
  agentTierDirectives = agentTierDirectives.map { edit ->
    ExecutionMatrixAgentTierDirectiveEdit(edit.agentId, edit.tier.toRuntime())
  },
  phaseTiers = phaseTiers.map { edit -> ExecutionMatrixPhaseTierEdit(edit.phaseId) },
  agentPhaseOverrides = agentPhaseOverrides.map { edit ->
    ExecutionMatrixAgentPhaseOverrideEdit(edit.agentId, edit.phaseId)
  },
)

private fun Tier.toRuntime(): ExecutionTier = when (this) {
  Tier.REASONING -> ExecutionTier.REASONING
  Tier.IMPLEMENTATION -> ExecutionTier.IMPLEMENTATION
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

private fun CompactionSettingsFields.toDomain() = AuthoredCompactionFields(
  enabled = enabled.toDomain(),
  windowTokens = windowTokens.toDomain(),
  triggerPct = triggerPct.toDomain(),
  phases = phases.toDomain { entries -> entries.mapValues { (_, fields) -> fields.toDomain() } },
)

private fun PhaseCompactionFields.toDomain() = AuthoredPhaseCompactionFields(
  windowTokens = windowTokens.toDomain(),
  triggerPct = triggerPct.toDomain(),
)

private fun <T, D> CompactionField<T>.toDomain(value: (T) -> D): AuthoredCompactionField<D> = when (this) {
  CompactionField.Missing -> AuthoredCompactionField.Missing
  CompactionField.Null -> AuthoredCompactionField.Null
  is CompactionField.Value -> AuthoredCompactionField.Value(value(this.value))
}

private fun <T> CompactionField<T>.toDomain(): AuthoredCompactionField<T> = toDomain { it }

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
