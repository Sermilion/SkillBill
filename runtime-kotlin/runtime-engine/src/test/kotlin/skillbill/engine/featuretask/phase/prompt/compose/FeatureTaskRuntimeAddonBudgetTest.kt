package skillbill.engine.featuretask.phase.prompt.compose

import skillbill.agentaddon.model.HydratedAgentAddonSelection
import skillbill.agentaddon.model.HydratedAgentAddonSelectionEntry
import skillbill.agentaddon.model.PersistedAgentAddonSelectionEntry
import kotlin.test.Test
import kotlin.test.assertEquals

class FeatureTaskRuntimeAddonBudgetTest {
  @Test
  fun `hydrated add-on content reaches every phase of the declared feature-task consumer`() {
    val selection = selection("small content")

    listOf(
      "preplan",
      "plan",
      "implement",
      "simplify",
      "audit",
      "review",
      "validate",
      "write_history",
      "commit_push",
      "pr",
    )
      .forEach { phaseId ->
        assertEquals(
          selection,
          FeatureTaskRuntimePhasePromptComposer.budgetedAddonsFor(selection),
          "phase '$phaseId' is part of the declared feature-task consumer but lost its add-on content",
        )
      }
  }

  private fun selection(content: String) =
    HydratedAgentAddonSelection(
      entries =
        listOf(
          HydratedAgentAddonSelectionEntry(
            persisted =
              PersistedAgentAddonSelectionEntry(
                slug = "acme-addon",
                sourceIdentity = "local:acme",
                contentSha256 = "0".repeat(64),
              ),
            description = "acme add-on",
            content = content,
          ),
        ),
    )
}
