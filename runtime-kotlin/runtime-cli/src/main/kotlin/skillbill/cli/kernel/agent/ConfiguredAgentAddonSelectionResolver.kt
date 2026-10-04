package skillbill.cli.kernel.agent

import me.tatarka.inject.annotations.Inject
import skillbill.agentaddon.model.AgentAddonConsumer
import skillbill.agentaddon.model.HydratedAgentAddonSelection
import skillbill.cli.model.CliRunInputs
import skillbill.contracts.workflow.identity.task.FeatureTaskRuntimeGoalContinuationArtifactPayloadKeys
import skillbill.model.toPath
import skillbill.ports.agentaddon.AgentAddonSelectionPort
import skillbill.ports.agentaddon.ExternalAgentAddonSourceConfigPort
import skillbill.ports.agentaddon.model.ExternalAgentAddonSourceConfigRequest
import java.nio.file.Path

@Inject
class ConfiguredAgentAddonSelectionResolver(
  private val selectionPort: AgentAddonSelectionPort,
  private val externalSourceConfig: ExternalAgentAddonSourceConfigPort,
  private val inputs: CliRunInputs,
) {
  fun resolveInitial(
    repoRoot: Path,
    requestedSlugs: List<String>,
    receivingAgentIds: List<String>,
  ): HydratedAgentAddonSelection =
    selectionPort.resolveInitial(
      repoRoot = repoRoot,
      requestedSlugs = requestedSlugs,
      consumer = AgentAddonConsumer.SKILL_BILL,
      receivingAgentIds = receivingAgentIds,
      externalSourceRoots =
        externalSourceConfig.readExternalAgentAddonSources(
          ExternalAgentAddonSourceConfigRequest(inputs.userHome, inputs.environment),
        ).sources.map { source -> source.path.toPath() },
    )
}

internal fun HydratedAgentAddonSelection.toCliEntryMaps(): List<Map<String, Any?>> =
  entries.map { entry ->
    linkedMapOf(
      FeatureTaskRuntimeGoalContinuationArtifactPayloadKeys.ADDON_SLUG to entry.persisted.slug,
      FeatureTaskRuntimeGoalContinuationArtifactPayloadKeys.ADDON_SOURCE_IDENTITY to entry.persisted.sourceIdentity,
      FeatureTaskRuntimeGoalContinuationArtifactPayloadKeys.ADDON_CONTENT_SHA256 to entry.persisted.contentSha256,
      "description" to entry.description,
    )
  }
