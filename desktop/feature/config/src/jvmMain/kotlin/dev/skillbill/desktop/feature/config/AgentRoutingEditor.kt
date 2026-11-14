package dev.skillbill.desktop.feature.config

import androidx.compose.foundation.layout.Box
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import dev.skillbill.desktop.core.designsystem.component.SbMenu
import dev.skillbill.desktop.core.designsystem.component.SbMenuEntry
import dev.skillbill.desktop.core.designsystem.component.SbPopover
import dev.skillbill.desktop.core.designsystem.settings.SettingPick
import dev.skillbill.desktop.core.domain.model.AgentRoutingSource
import dev.skillbill.desktop.core.domain.model.AgentRoutingView
import dev.skillbill.desktop.core.domain.model.AgentRuntimeAvailability
import dev.skillbill.desktop.core.domain.model.ConfigAgent
import dev.skillbill.desktop.core.domain.model.ConfigDefaults
import dev.skillbill.desktop.core.domain.model.ConfigWriteOutcome
import dev.skillbill.desktop.core.domain.model.ExecutionMatrixConfig
import dev.skillbill.desktop.core.domain.model.MachineConfigChange
import dev.skillbill.desktop.core.domain.model.MachineConfigExecutionMatrixEditIntent
import dev.skillbill.desktop.core.domain.model.MachineConfigPhaseAgentEdit
import dev.skillbill.desktop.core.domain.model.MachineConfigTierAgentEdit
import dev.skillbill.desktop.core.domain.model.PhaseRoutingPreview
import dev.skillbill.desktop.core.domain.model.Tier
import dev.skillbill.desktop.core.domain.repository.RuntimeConfigRepository
import dev.skillbill.desktop.core.ui.input.ShellLayer

internal fun routingSourceText(preview: PhaseRoutingPreview?): String =
  when (preview?.source) {
    AgentRoutingSource.CONFIGURED_PHASE ->
      "phase_agents routes to ${preview.providerId}"
    AgentRoutingSource.CONFIGURED_TIER ->
      "inherits the ${preview.tier.name.lowercase()} tier provider ${preview.providerId}"
    AgentRoutingSource.INHERITED_INVOCATION, null ->
      "inherits the invoking provider"
  }

internal fun routingAvailabilityText(read: AgentRuntimeAvailability?): String? =
  when (read) {
    null, is AgentRuntimeAvailability.Registered -> null
    is AgentRuntimeAvailability.ExecutableMissing -> read.reason
    is AgentRuntimeAvailability.Unsupported -> read.reason
    is AgentRuntimeAvailability.Unknown -> "Availability could not be read: ${read.reason}"
  }

/**
 * Routing rows are ordinary [ConfigEdit] rows whose single `agent` field carries the selected
 * provider id; saves and removes go through the same fingerprinted snapshot path as every other row.
 */
internal fun agentRoutingRows(
  current: ExecutionMatrixConfig,
  routing: AgentRoutingView,
  defaults: ConfigDefaults,
  repository: RuntimeConfigRepository,
  unavailableDocument: Boolean,
): List<ConfigEdit> {
  val providerIds = defaults.agents.map { it.id }.toSet()
  fun routingRow(
    key: String,
    label: String,
    description: String,
    authored: String?,
    preview: PhaseRoutingPreview?,
    intent: () -> MachineConfigExecutionMatrixEditIntent,
    apply: (String?) -> ExecutionMatrixConfig,
  ): ConfigEdit {
    val selected = authored.orEmpty()
    val unavailableReason = routingAvailabilityText(
      routing.availability[selected.takeIf(String::isNotBlank)],
    )
    return ConfigEdit(
      key = key,
      value = if (unavailableDocument) "unavailable" else authored ?: routingSourceText(preview),
      default = routingSourceText(preview),
      fields = mapOf(ConfigFileKeys.AGENT to selected),
      group = "Agent routing",
      fieldPaths = mapOf(ConfigFileKeys.AGENT to key),
      label = label,
      description = listOfNotNull(description, unavailableReason).joinToString(" · "),
      save = { input, _, basis ->
        val providerId = input[ConfigFileKeys.AGENT].orEmpty().trim()
        if (providerId !in providerIds) {
          ConfigWriteOutcome.Invalid("$key.agent", "Choose a supported provider id.")
        } else {
          repository.writeMachineConfigFromSnapshot(
            basis.path,
            listOf(
              MachineConfigChange.SetExecutionMatrix(
                executionMatrixSection(apply(providerId), defaults, current),
                intent(),
              ),
            ),
            basis.fingerprint,
          )
        }
      },
      remove = if (authored != null) {
        { basis ->
          repository.writeMachineConfigFromSnapshot(
            basis.path,
            listOf(
              MachineConfigChange.SetExecutionMatrix(
                executionMatrixSection(apply(null), defaults, current),
                intent(),
              ),
            ),
            basis.fingerprint,
          )
        }
      } else {
        null
      },
    )
  }
  return buildList {
    listOf(Tier.REASONING to "Reasoning phases", Tier.IMPLEMENTATION to "Implementation phases")
      .forEach { (tier, label) ->
        add(
          routingRow(
            key = "execution_matrix.tier_agents.${tier.name.lowercase()}",
            label = label,
            description =
              "tier_agents.${tier.name.lowercase()} · phases without a phase route use this provider",
            authored = current.tierAgents[tier],
            preview = routing.phases.firstOrNull { it.tier == tier && it.source != AgentRoutingSource.CONFIGURED_PHASE },
            intent = {
              MachineConfigExecutionMatrixEditIntent(tierAgents = listOf(MachineConfigTierAgentEdit(tier)))
            },
            apply = { provider ->
              current.copy(
                tierAgents =
                  if (provider == null) current.tierAgents - tier else current.tierAgents + (tier to provider),
              )
            },
          ),
        )
      }
    defaults.phases.forEach { phase ->
      val preview = routing.phases.firstOrNull { it.phaseId == phase.id }
      add(
        routingRow(
          key = "execution_matrix.phase_agents.${phase.id}",
          label = phase.id,
          description = "phase_agents.${phase.id} · ${routingSourceText(preview)}",
          authored = current.phaseAgents[phase.id],
          preview = preview,
          intent = {
            MachineConfigExecutionMatrixEditIntent(phaseAgents = listOf(MachineConfigPhaseAgentEdit(phase.id)))
          },
          apply = { provider ->
            current.copy(
              phaseAgents =
                if (provider == null) current.phaseAgents - phase.id else current.phaseAgents + (phase.id to provider),
            )
          },
        ),
      )
    }
  }
}

/** A labeled, keyboard-operable provider picker: one pick opens the menu; selecting commits the draft. */
@Composable
internal fun RoutingProviderSelector(
  selected: String,
  inheritedText: String,
  providers: List<ConfigAgent>,
  availability: Map<String, AgentRuntimeAvailability>,
  authored: Boolean,
  enabled: Boolean,
  label: String,
  onSelect: (String) -> Unit,
  onInherit: () -> Unit,
) {
  var menuOpen by remember { mutableStateOf(false) }
  Box {
    SettingPick(
      text = selected.ifBlank { inheritedText },
      onClick = { menuOpen = true },
      enabled = enabled,
      mono = true,
      contentDescription = label,
    )
    if (menuOpen) {
      ShellLayer { menuOpen = false }
      SbPopover(menuOpen, { menuOpen = false }) {
        SbMenu(
          buildList {
            add(SbMenuEntry.Head("Provider for $label"))
            if (authored) {
              add(
                SbMenuEntry.Item(
                  "Inherit",
                  { menuOpen = false; onInherit() },
                  reason = "Remove this route so the phase inherits again.",
                ),
              )
            }
            providers.forEach { provider ->
              val reason = routingAvailabilityText(availability[provider.id])
              add(
                SbMenuEntry.Item(
                  provider.id,
                  { menuOpen = false; onSelect(provider.id) },
                  checked = provider.id == selected,
                  mono = true,
                  reason = reason,
                ),
              )
            }
          },
        )
      }
    }
  }
}
