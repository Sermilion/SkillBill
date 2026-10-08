package skillbill.config.model

import skillbill.install.model.SupportedAgent
import skillbill.workflow.taskruntime.model.core.FeatureTaskRuntimePhaseIds
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNull

class ExecutionMatrixModelsTest {
  @Test
  fun `parses a full matrix with phase tier override`() {
    val parsed =
      assertIs<ExecutionMatrixParse.Valid>(
        parseExecutionMatrix(
          mapOf(
            "phase_tiers" to mapOf("plan" to "implementation"),
            "agents" to
              mapOf(
                "claude" to
                  mapOf(
                    "reasoning" to mapOf("model" to "claude-opus", "effort" to "high"),
                    "implementation" to mapOf("model" to "claude-sonnet"),
                  ),
                "codex" to mapOf("reasoning" to mapOf("model" to "gpt-reasoning", "effort" to "xhigh")),
              ),
          ),
        ),
      )

    assertEquals(ExecutionTier.IMPLEMENTATION, parsed.matrix.tierOf("plan"))
    assertEquals(
      PhaseModelDirective("claude-sonnet"),
      parsed.matrix.directiveFor("claude", "plan"),
    )
    assertEquals(
      PhaseModelDirective("gpt-reasoning", "xhigh"),
      parsed.matrix.directiveFor("codex", "review"),
    )
  }

  @Test
  fun `parses a single agent single tier matrix with omitted effort`() {
    val parsed =
      assertIs<ExecutionMatrixParse.Valid>(
        parseExecutionMatrix(
          mapOf("agents" to mapOf("claude" to mapOf("implementation" to mapOf("model" to "sonnet")))),
        ),
      )

    assertEquals(PhaseModelDirective("sonnet"), parsed.matrix.directiveFor("claude", "implement"))
    assertNull(parsed.matrix.directiveFor("claude", "review"))
  }

  @Test
  fun `per-phase agent overrides win over the tier directive for that phase only`() {
    val parsed =
      assertIs<ExecutionMatrixParse.Valid>(
        parseExecutionMatrix(
          mapOf(
            "phase_tiers" to mapOf("preplan" to "reasoning"),
            "agents" to
              mapOf(
                "cursor" to
                  mapOf(
                    "reasoning" to mapOf("model" to "gpt-5.6-luna-xhigh"),
                    "implementation" to mapOf("model" to "cursor-grok-4.5-medium"),
                    "preplan" to mapOf("model" to "gpt-5.6-luna-high"),
                    "review" to mapOf("model" to "claude-opus-5-thinking-max", "effort" to "max"),
                  ),
              ),
          ),
        ),
      )
    val matrix = parsed.matrix

    assertEquals(PhaseModelDirective("gpt-5.6-luna-high"), matrix.directiveFor("cursor", "preplan"))
    assertEquals(PhaseModelDirective("claude-opus-5-thinking-max", "max"), matrix.directiveFor("cursor", "review"))
    assertEquals(PhaseModelDirective("gpt-5.6-luna-xhigh"), matrix.directiveFor("cursor", "audit"))
    assertEquals(PhaseModelDirective("cursor-grok-4.5-medium"), matrix.directiveFor("cursor", "pr"))
  }

  @Test
  fun `rejects every malformed matrix layer with its dotted key path`() {
    val cases =
      listOf(
        "root" to Pair(listOf("not", "a", "map"), "execution_matrix"),
        "top-level field" to
          Pair(
            mapOf("unknown" to "value", "agents" to emptyMap<String, Any?>()),
            "execution_matrix.unknown",
          ),
        "phase tier" to
          Pair(
            mapOf("phase_tiers" to mapOf("unknown_phase" to "reasoning"), "agents" to emptyMap<String, Any?>()),
            "execution_matrix.phase_tiers.unknown_phase",
          ),
        "agents map" to Pair(mapOf("agents" to "claude"), "execution_matrix.agents"),
        "agent tier" to
          Pair(
            mapOf("agents" to mapOf("unknown-agent" to emptyMap<String, Any?>())),
            "execution_matrix.agents.unknown-agent",
          ),
        "directive" to
          Pair(
            mapOf("agents" to mapOf("claude" to mapOf("reasoning" to mapOf("effort" to "high")))),
            "execution_matrix.agents.claude.reasoning.model",
          ),
      )

    cases.forEach { (_, case) ->
      val invalid = assertIs<ExecutionMatrixParse.Invalid>(parseExecutionMatrix(case.first))
      assertEquals(case.second, invalid.keyPath)
    }
  }

  @Test
  fun `rejects invalid tier entry fields and values at their dotted paths`() {
    val cases =
      listOf(
        Pair(mapOf("model" to ""), "execution_matrix.agents.claude.reasoning.model"),
        Pair(mapOf("model" to "m", "effort" to ""), "execution_matrix.agents.claude.reasoning.effort"),
        Pair(mapOf("model" to "m", "other" to "x"), "execution_matrix.agents.claude.reasoning.other"),
      )

    cases.forEach { (directive, path) ->
      val invalid =
        assertIs<ExecutionMatrixParse.Invalid>(
          parseExecutionMatrix(
            mapOf("agents" to mapOf("claude" to mapOf("reasoning" to directive))),
          ),
        )
      assertEquals(path, invalid.keyPath)
    }
  }

  @Test
  fun `rejects every remaining malformed nested shape at its dotted path`() {
    val cases =
      listOf(
        Pair(
          mapOf("phase_tiers" to null, "agents" to emptyMap<String, Any?>()),
          "execution_matrix.phase_tiers",
        ),
        Pair(
          mapOf("phase_tiers" to mapOf("plan" to "other"), "agents" to emptyMap<String, Any?>()),
          "execution_matrix.phase_tiers.plan",
        ),
        Pair(mapOf("agents" to mapOf("claude" to "not-a-map")), "execution_matrix.agents.claude"),
        Pair(
          mapOf("agents" to mapOf("claude" to mapOf("other" to emptyMap<String, Any?>()))),
          "execution_matrix.agents.claude.other",
        ),
        Pair(
          mapOf("agents" to mapOf("claude" to mapOf("reasoning" to "not-a-map"))),
          "execution_matrix.agents.claude.reasoning",
        ),
        Pair(
          mapOf("agents" to mapOf("claude" to mapOf("reasoning" to mapOf("model" to 1)))),
          "execution_matrix.agents.claude.reasoning.model",
        ),
        Pair(
          mapOf("agents" to mapOf("claude" to mapOf("reasoning" to mapOf("model" to "m", "effort" to 1)))),
          "execution_matrix.agents.claude.reasoning.effort",
        ),
        Pair(
          mapOf("agents" to mapOf("claude" to mapOf("review" to mapOf("effort" to "high")))),
          "execution_matrix.agents.claude.review.model",
        ),
        Pair(
          mapOf("agents" to mapOf("claude" to mapOf("planning" to mapOf("model" to "m")))),
          "execution_matrix.agents.claude.planning",
        ),
      )

    cases.forEach { (raw, path) ->
      val invalid = assertIs<ExecutionMatrixParse.Invalid>(parseExecutionMatrix(raw))
      assertEquals(path, invalid.keyPath)
    }
  }

  @Test
  fun `tier defaults cover every runtime phase`() {
    val matrix = ExecutionMatrix()

    assertEquals(FeatureTaskRuntimePhaseIds.all.sorted(), DEFAULT_PHASE_TIERS.keys.sorted())
    FeatureTaskRuntimePhaseIds.all.forEach { phaseId ->
      assertEquals(DEFAULT_PHASE_TIERS.getValue(phaseId), matrix.tierOf(phaseId))
    }
    assertEquals(ExecutionTier.REASONING, matrix.tierOf("plan"))
    assertEquals(ExecutionTier.REASONING, matrix.tierOf("review"))
    assertEquals(ExecutionTier.REASONING, matrix.tierOf("verify_findings"))
    assertEquals(ExecutionTier.REASONING, matrix.tierOf("audit"))
    assertEquals(ExecutionTier.REASONING, matrix.tierOf("validate"))
    assertEquals(ExecutionTier.REASONING, matrix.tierOf("present_findings"))
    assertEquals(ExecutionTier.IMPLEMENTATION, matrix.tierOf("preplan"))
    assertEquals(ExecutionTier.IMPLEMENTATION, matrix.tierOf("implement"))
    assertEquals(ExecutionTier.IMPLEMENTATION, matrix.tierOf("implement_fix"))
    assertEquals(ExecutionTier.IMPLEMENTATION, matrix.tierOf("write_history"))
    assertEquals(ExecutionTier.IMPLEMENTATION, matrix.tierOf("commit_push"))
    assertEquals(ExecutionTier.IMPLEMENTATION, matrix.tierOf("pr"))
  }

  @Test
  fun `a configured agent resolves a directive for the standalone review step`() {
    val matrix =
      ExecutionMatrix(
        agents = mapOf(SupportedAgent.CLAUDE to mapOf(ExecutionTier.REASONING to PhaseModelDirective("opus"))),
      )

    assertEquals(PhaseModelDirective("opus"), matrix.directiveFor("claude", "present_findings"))
  }

  @Test
  fun `unknown agents stay inert when resolving directives`() {
    val matrix =
      ExecutionMatrix(
        agents = mapOf(SupportedAgent.CLAUDE to mapOf(ExecutionTier.REASONING to PhaseModelDirective("opus"))),
      )

    assertNull(matrix.directiveFor("unknown", "plan"))
  }
}
