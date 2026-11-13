package skillbill.engine.goalrunner.persist.quality

import skillbill.config.model.ExecutionTier
import skillbill.contracts.workflow.goal.GoalQualityExecutionPayloadKeys
import skillbill.install.model.SupportedAgent
import kotlin.test.Test
import kotlin.test.assertEquals

class GoalQualityExecutionCompatibilityTest {
  @Test
  fun `decoding retains retired matrix entries while projecting supported agents`() {
    val keys = GoalQualityExecutionPayloadKeys
    val directive = mapOf(keys.MODEL to "model-x", keys.EFFORT to "high")
    val matrix =
      mapOf(
        keys.PHASE_TIERS to mapOf("implement" to ExecutionTier.IMPLEMENTATION.name),
        keys.AGENTS to
          mapOf(
            "claude" to mapOf(ExecutionTier.IMPLEMENTATION.name to directive),
            RETIRED_AGENT to mapOf(ExecutionTier.REASONING.name to directive),
          ),
        keys.AGENT_PHASE_OVERRIDES to
          mapOf(
            "claude" to mapOf("implement" to directive),
            RETIRED_AGENT to mapOf("plan" to directive),
          ),
      )
    val encoded =
      mapOf(
        keys.ISSUE_KEY to "SONA-19",
        keys.INTAKE to null,
        keys.REPO_ROOT to "/repo",
        keys.INVOKED_AGENT_ID to "claude",
        keys.CONFIGURED_AGENT_OVERRIDE_ID to null,
        keys.TIMEOUT_MILLIS to null,
        keys.QA_SELECTION to "auto",
        keys.PARENT_SPEC_PATH to "/repo/spec.md",
        keys.ACCEPTANCE_CRITERIA to null,
        keys.ACCEPTANCE_CRITERIA_READ_FAILURE to null,
        keys.BRANCH to "branch",
        keys.MODEL_DIRECTIVES to emptyMap<String, Any>(),
        keys.MODEL_MATRIX to matrix,
        keys.ADDONS to emptyList<Any>(),
      )

    val decoded = requireNotNull(decodeInputs(encoded))

    assertEquals(setOf(SupportedAgent.CLAUDE), decoded.modelMatrix?.agents?.keys)
    assertEquals(setOf(SupportedAgent.CLAUDE), decoded.modelMatrix?.agentPhaseOverrides?.keys)
    assertEquals(ExecutionTier.IMPLEMENTATION, decoded.modelMatrix?.phaseTiers?.get("implement"))
    val retiredDirective = decoded.retainedModelMatrix?.agents?.get(RETIRED_AGENT)?.get(ExecutionTier.REASONING)
    assertEquals("model-x", retiredDirective?.model)
    assertEquals(encoded, decoded.toPersistenceWire())
  }

  private companion object {
    const val RETIRED_AGENT = "retired-agent"
  }
}
