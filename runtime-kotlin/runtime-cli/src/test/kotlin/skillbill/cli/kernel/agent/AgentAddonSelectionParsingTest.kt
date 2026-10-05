package skillbill.cli.kernel.agent

import com.github.ajalt.clikt.core.UsageError
import skillbill.contracts.JsonCodec
import skillbill.contracts.SharedPayloadKeys
import skillbill.contracts.agentaddon.AGENT_ADDON_SELECTION_CONTRACT_VERSION
import skillbill.contracts.workflow.identity.task.FeatureTaskRuntimeGoalContinuationArtifactPayloadKeys
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

class AgentAddonSelectionParsingTest {
  @Test
  fun `duplicate persisted slugs are reported as invalid selection input`() {
    val entry =
      mapOf(
        FeatureTaskRuntimeGoalContinuationArtifactPayloadKeys.ADDON_SLUG to "codex-policy",
        FeatureTaskRuntimeGoalContinuationArtifactPayloadKeys.ADDON_SOURCE_IDENTITY to "/addons/codex-policy",
        FeatureTaskRuntimeGoalContinuationArtifactPayloadKeys.ADDON_CONTENT_SHA256 to "a".repeat(64),
      )
    val raw =
      JsonCodec.mapToJsonString(
        mapOf(
          SharedPayloadKeys.CONTRACT_VERSION to AGENT_ADDON_SELECTION_CONTRACT_VERSION,
          FeatureTaskRuntimeGoalContinuationArtifactPayloadKeys.ENTRIES to listOf(entry, entry),
        ),
      )

    val error = assertFailsWith<UsageError> { parseAgentAddonSelection(raw) }

    assertEquals(
      "Invalid agent add-on selection: Agent add-on selection contains duplicate slugs.",
      error.message,
    )
  }
}
