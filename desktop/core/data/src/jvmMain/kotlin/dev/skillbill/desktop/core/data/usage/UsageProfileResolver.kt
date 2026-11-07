package dev.skillbill.desktop.core.data.usage

import dev.skillbill.desktop.core.common.HostEnvironment
import dev.skillbill.desktop.core.common.di.AppScope
import dev.skillbill.desktop.core.data.session.SelectedAgentHomes
import dev.skillbill.desktop.core.data.session.claudeEnvironment
import dev.skillbill.desktop.core.domain.usage.model.UsageProfileLookup
import dev.skillbill.desktop.core.domain.usage.model.UsageProvider
import me.tatarka.inject.annotations.Inject
import software.amazon.lastmile.kotlin.inject.anvil.SingleIn
import java.util.concurrent.atomic.AtomicLong
import skillbill.contracts.system.AgentHomeEnvironmentKeys

internal data class EffectiveUsageProfile(
  val lookup: UsageProfileLookup,
  val environment: Map<String, String>,
)

@Inject
@SingleIn(AppScope::class)
class UsageProfileResolver(private val host: HostEnvironment) {
  private val generation = AtomicLong()

  internal fun codex(selectedProfile: String?): EffectiveUsageProfile {
    val environment = codexEnvironment(host.variables, selectedProfile)
    return EffectiveUsageProfile(
      UsageProfileLookup(UsageProvider.Codex, environment[AgentHomeEnvironmentKeys.CODEX_HOME], generation.incrementAndGet()),
      environment,
    )
  }

  internal fun resolveCodex(profile: UsageProfileLookup): EffectiveUsageProfile =
    EffectiveUsageProfile(profile, codexEnvironment(host.variables, profile.configurationReference))

  internal fun cursor(selectedProfile: String?): EffectiveUsageProfile {
    val environment = cursorEnvironment(host.variables, selectedProfile)
    return EffectiveUsageProfile(
      UsageProfileLookup(UsageProvider.Cursor, environment[CURSOR_CONFIG_DIR], generation.incrementAndGet()),
      environment,
    )
  }

  internal fun claude(selectedProfile: String?): EffectiveUsageProfile {
    val homes = SelectedAgentHomes(selectedProfile, host.userHome).current()
    val environment = claudeEnvironment(host.variables, homes)
    val selectedHome = homes[AgentHomeEnvironmentKeys.CLAUDE_CONFIG_DIR]
    val effectiveDirectory = when {
      selectedHome == "" -> "${host.userHome}/.claude"
      selectedHome != null -> selectedHome
      else -> host.variables[AgentHomeEnvironmentKeys.CLAUDE_CONFIG_DIR]
        ?.takeIf(String::isNotEmpty)
        ?: "${host.userHome}/.claude"
    }
    return EffectiveUsageProfile(
      UsageProfileLookup(UsageProvider.Claude, effectiveDirectory, generation.incrementAndGet()),
      environment,
    )
  }

  companion object {
    const val CURSOR_CONFIG_DIR = "CURSOR_CONFIG_DIR"
  }
}

internal fun codexEnvironment(variables: Map<String, String>, selectedProfile: String?): Map<String, String> =
  variables + selectedProfile?.let { mapOf(AgentHomeEnvironmentKeys.CODEX_HOME to it) }.orEmpty()

internal fun cursorEnvironment(variables: Map<String, String>, selectedProfile: String?): Map<String, String> =
  variables + selectedProfile?.let { mapOf(UsageProfileResolver.CURSOR_CONFIG_DIR to it) }.orEmpty()
