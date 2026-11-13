package skillbill.engine.featuretask.lifecycle.core

import skillbill.config.model.ExecutionMatrix
import skillbill.config.model.ExecutionTier
import skillbill.config.model.PhaseAgentRoutingSource
import skillbill.config.model.PhaseModelDirective
import skillbill.engine.featuretask.model.core.FeatureTaskRuntimeAgentAssignment
import skillbill.engine.featuretask.model.core.FeatureTaskRuntimeModelAssignment
import skillbill.install.model.SupportedAgent
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class FeatureTaskRuntimeModelResolverTest {
  @Test
  fun `agent routing precedence keeps provider and model selection separate`() {
    val routedMatrix = routedMatrix()
    val cases = configuredRoutingCases(routedMatrix) + overrideAndLegacyRoutingCases(routedMatrix)

    cases.forEach { case ->
      val resolved = FeatureTaskRuntimeAgentResolver.resolve(case.phaseId, case.assignment, case.invokedAgentId)
      val model =
        FeatureTaskRuntimeModelResolver.resolve(
          case.phaseId,
          resolved.resolvedAgentId,
          FeatureTaskRuntimeModelAssignment(matrix = case.assignment.matrix),
        )

      assertEquals(case.expectedProvider, resolved.resolvedAgentId, case.phaseId)
      assertEquals(case.expectedSource, resolved.routingSource, case.phaseId)
      assertEquals(case.expectedModel, model, case.phaseId)
    }

    assertEquals(
      PhaseModelDirective("cli-model", "high"),
      FeatureTaskRuntimeModelResolver.resolve(
        "review",
        "claude",
        FeatureTaskRuntimeModelAssignment(
          perPhaseDirectives = mapOf("review" to PhaseModelDirective("cli-model", "high")),
          matrix = routedMatrix,
        ),
      ),
    )
  }

  @Test
  fun `cli directive wins over matrix directive`() {
    val assignment =
      FeatureTaskRuntimeModelAssignment(
        perPhaseDirectives = mapOf("plan" to PhaseModelDirective("cli-model", "high")),
        matrix = matrix(),
      )

    assertEquals(
      PhaseModelDirective("cli-model", "high"),
      FeatureTaskRuntimeModelResolver.resolve("plan", "claude", assignment),
    )
  }

  @Test
  fun `matrix directive resolves against each phases actual agent`() {
    val assignment = FeatureTaskRuntimeModelAssignment(matrix = matrix())

    assertEquals(
      PhaseModelDirective("claude-opus", "high"),
      FeatureTaskRuntimeModelResolver.resolve("review", "claude", assignment),
    )
    assertEquals(
      PhaseModelDirective("gpt-sol", "xhigh"),
      FeatureTaskRuntimeModelResolver.resolve("review", "codex", assignment),
    )
  }

  @Test
  fun `no matrix or unknown agent resolves no directive`() {
    assertNull(
      FeatureTaskRuntimeModelResolver.resolve(
        "plan",
        "claude",
        FeatureTaskRuntimeModelAssignment(),
      ),
    )
    assertNull(
      FeatureTaskRuntimeModelResolver.resolve(
        "plan",
        "unknown",
        FeatureTaskRuntimeModelAssignment(matrix = matrix()),
      ),
    )
  }

  private fun matrix(): ExecutionMatrix =
    ExecutionMatrix(
      agents =
        mapOf(
          SupportedAgent.CLAUDE to mapOf(ExecutionTier.REASONING to PhaseModelDirective("claude-opus", "high")),
          SupportedAgent.CODEX to mapOf(ExecutionTier.REASONING to PhaseModelDirective("gpt-sol", "xhigh")),
        ),
    )

  private fun routedMatrix(): ExecutionMatrix =
    ExecutionMatrix(
      phaseTiers = mapOf("implement" to ExecutionTier.REASONING),
      agents =
        mapOf(
          SupportedAgent.CLAUDE to
            mapOf(
              ExecutionTier.REASONING to PhaseModelDirective("claude-reasoning", "high"),
              ExecutionTier.IMPLEMENTATION to PhaseModelDirective("claude-implementation"),
            ),
          SupportedAgent.CURSOR to
            mapOf(
              ExecutionTier.REASONING to PhaseModelDirective("cursor-reasoning", "low"),
              ExecutionTier.IMPLEMENTATION to PhaseModelDirective("cursor-fast", "medium"),
            ),
        ),
      agentPhaseOverrides =
        mapOf(
          SupportedAgent.CLAUDE to mapOf("review" to PhaseModelDirective("claude-review-explicit", "max")),
        ),
      tierAgents =
        mapOf(
          ExecutionTier.REASONING to SupportedAgent.CLAUDE,
          ExecutionTier.IMPLEMENTATION to SupportedAgent.CURSOR,
        ),
      phaseAgents = mapOf("review" to SupportedAgent.CODEX),
    )

  private fun configuredRoutingCases(routedMatrix: ExecutionMatrix): List<RoutingCase> =
    listOf(
      RoutingCase(
        "plan",
        FeatureTaskRuntimeAgentAssignment(matrix = routedMatrix),
        "cursor",
        "claude",
        PhaseAgentRoutingSource.CONFIGURED_TIER,
        PhaseModelDirective("claude-reasoning", "high"),
      ),
      RoutingCase(
        "review",
        FeatureTaskRuntimeAgentAssignment(matrix = routedMatrix),
        "cursor",
        "codex",
        PhaseAgentRoutingSource.CONFIGURED_PHASE,
        null,
      ),
      RoutingCase(
        "implement",
        FeatureTaskRuntimeAgentAssignment(matrix = routedMatrix),
        "cursor",
        "claude",
        PhaseAgentRoutingSource.CONFIGURED_TIER,
        PhaseModelDirective("claude-reasoning", "high"),
      ),
      RoutingCase(
        "preplan",
        FeatureTaskRuntimeAgentAssignment(matrix = routedMatrix),
        "claude",
        "cursor",
        PhaseAgentRoutingSource.CONFIGURED_TIER,
        PhaseModelDirective("cursor-fast", "medium"),
      ),
    )

  private fun overrideAndLegacyRoutingCases(routedMatrix: ExecutionMatrix): List<RoutingCase> =
    listOf(
      RoutingCase(
        "plan",
        FeatureTaskRuntimeAgentAssignment(perPhaseAgentIds = mapOf("plan" to "cursor"), matrix = routedMatrix),
        "claude",
        "cursor",
        PhaseAgentRoutingSource.CLI_PHASE,
        PhaseModelDirective("cursor-reasoning", "low"),
      ),
      RoutingCase(
        "review",
        FeatureTaskRuntimeAgentAssignment(
          perPhaseAgentIds = mapOf("review" to "cursor"),
          override = "claude",
          matrix = routedMatrix,
        ),
        "cursor",
        "claude",
        PhaseAgentRoutingSource.RUN_OVERRIDE,
        PhaseModelDirective("claude-review-explicit", "max"),
      ),
      RoutingCase(
        "legacy",
        FeatureTaskRuntimeAgentAssignment(perPhaseAgentIds = mapOf("legacy" to "cursor")),
        "claude",
        "cursor",
        PhaseAgentRoutingSource.CLI_PHASE,
        null,
      ),
      RoutingCase(
        "legacy-invocation",
        FeatureTaskRuntimeAgentAssignment(),
        "claude",
        "claude",
        PhaseAgentRoutingSource.INVOKING_AGENT,
        null,
      ),
    )

  private data class RoutingCase(
    val phaseId: String,
    val assignment: FeatureTaskRuntimeAgentAssignment,
    val invokedAgentId: String,
    val expectedProvider: String,
    val expectedSource: PhaseAgentRoutingSource,
    val expectedModel: PhaseModelDirective?,
  )
}
