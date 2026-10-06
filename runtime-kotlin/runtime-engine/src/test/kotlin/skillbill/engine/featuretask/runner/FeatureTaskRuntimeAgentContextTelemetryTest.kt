package skillbill.engine.featuretask.runner

import skillbill.contracts.telemetry.TelemetryMeasurementAvailability
import skillbill.contracts.workflow.identity.task.FeatureTaskRuntimeExecutionPlanKeys
import skillbill.engine.featuretask.lifecycle.core.featureTaskRuntimeAgentContext
import skillbill.engine.featuretask.lifecycle.core.featureTaskRuntimePhaseStrategies
import skillbill.engine.featuretask.model.core.PHASE_STRATEGIES_DISPATCH_JOIN_EXPECTED
import skillbill.engine.featuretask.model.core.PHASE_STRATEGIES_DISPATCH_JOIN_SEAM
import skillbill.workflow.model.WorkflowStepStatus
import skillbill.workflow.taskruntime.model.phase.FeatureTaskRuntimePhaseRecord
import skillbill.workflow.taskruntime.model.skeleton.PhaseSlot
import skillbill.workflow.taskruntime.model.skeleton.ResolvedPhaseStrategyDispatch
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

class FeatureTaskRuntimeAgentContextTelemetryTest {
  @Test
  fun `a run whose phases resolved two agents reports both and reports only the models it launched`() {
    val context =
      featureTaskRuntimeAgentContext(
        mapOf(
          "implement" to phaseRecord("implement", agentId = "codex", model = "gpt-5-codex"),
          "review" to phaseRecord("review", agentId = "claude", model = null),
          "validate" to phaseRecord("validate", agentId = "codex", model = "gpt-5-codex"),
        ),
      )

    assertEquals(
      listOf("claude", "codex"),
      context.resolvedAgentIds,
      "a run that switched agents mid-run must not report one phase's agent as the run's agent",
    )
    assertEquals(
      listOf("gpt-5-codex"),
      context.launchedModels,
      "a phase that launched with no model directive contributes no model and no placeholder",
    )
  }

  @Test
  fun `a run with no durable phase records reports both as absent rather than as an empty set`() {
    val context = featureTaskRuntimeAgentContext(null)

    assertNull(context.resolvedAgentIds)
    assertNull(context.launchedModels)
  }

  @Test
  fun `a run whose every phase launched without a model knows its agents and not its models`() {
    val context =
      featureTaskRuntimeAgentContext(
        mapOf("implement" to phaseRecord("implement", agentId = "claude", model = null)),
      )

    assertEquals(listOf("claude"), context.resolvedAgentIds)
    assertNull(
      context.launchedModels,
      "an unmeasured model set must stay absent, or a consumer reads it as a run that launched no model",
    )
  }

  @Test
  fun `implement and simplify share one admitted id and audit keeps revision 3`() {
    val result =
      featureTaskRuntimePhaseStrategies(
        listOf("implement", "simplify", "commit_push", "audit"),
        mapOf(
          "implement" to dispatch(PhaseSlot.IMPLEMENTATION, "implement-then-simplify-opus-5-5", 1),
          "simplify" to dispatch(PhaseSlot.IMPLEMENTATION, "implement-then-simplify-opus-5-5", 1),
          "commit_push" to dispatch(PhaseSlot.COMMIT_PUSH, "runtime-commit", 1),
          "audit" to dispatch(PhaseSlot.AUDIT, "acceptance-audit", 3),
          "plan" to dispatch(PhaseSlot.PLAN, "agent-plan", 1),
        ),
      )

    assertEquals(TelemetryMeasurementAvailability.MEASURED, result.availability)
    assertEquals(
      listOf("implement", "simplify", "commit_push", "audit"),
      result.values?.keys?.toList(),
    )
    assertEquals("implement-then-simplify-opus-5-5", strategyId(result.values, "implement"))
    assertEquals("implement-then-simplify-opus-5-5", strategyId(result.values, "simplify"))
    assertEquals("runtime-commit", strategyId(result.values, "commit_push"))
    assertEquals("acceptance-audit", strategyId(result.values, "audit"))
    assertEquals(1, revision(result.values, "implement"))
    assertEquals(1, revision(result.values, "commit_push"))
    assertEquals(3, revision(result.values, "audit"))
    result.values?.values?.forEach { entry ->
      assertTrue(FeatureTaskRuntimeExecutionPlanKeys.SLOT !in entry)
    }
  }

  @Test
  fun `a goal-child build join records build and omits validate`() {
    val result =
      featureTaskRuntimePhaseStrategies(
        listOf("implement", "build"),
        mapOf(
          "implement" to dispatch(PhaseSlot.IMPLEMENTATION, "implement-then-simplify", 1),
          "build" to dispatch(PhaseSlot.QUALITY_GATE, "pack-build-opus-5-5", 1),
          "validate" to dispatch(PhaseSlot.QUALITY_GATE, "agent-validate", 1),
        ),
      )

    assertEquals(TelemetryMeasurementAvailability.MEASURED, result.availability)
    assertEquals("pack-build-opus-5-5", strategyId(result.values, "build"))
    assertTrue("validate" !in result.values.orEmpty())
  }

  @Test
  fun `no phase records or no admitted dispatch reports unavailable and a null map`() {
    listOf(
      featureTaskRuntimePhaseStrategies(
        emptyList(),
        mapOf("implement" to dispatch(PhaseSlot.IMPLEMENTATION, "implement-then-simplify", 1)),
      ),
      featureTaskRuntimePhaseStrategies(listOf("implement"), null),
      featureTaskRuntimePhaseStrategies(listOf("implement"), emptyMap()),
    ).forEach { result ->
      assertEquals(TelemetryMeasurementAvailability.UNAVAILABLE_NO_DURABLE_STATE, result.availability)
      assertNull(result.values)
    }
  }

  @Test
  fun `a recorded phase missing from dispatch reports incomplete and names the missing ids`() {
    val result =
      featureTaskRuntimePhaseStrategies(
        listOf("implement", "validate", "pr"),
        mapOf("implement" to dispatch(PhaseSlot.IMPLEMENTATION, "implement-then-simplify", 1)),
      )

    assertEquals(TelemetryMeasurementAvailability.UNAVAILABLE_INCOMPLETE, result.availability)
    assertNull(result.values)
    assertEquals(listOf("validate", "pr"), result.missingPhaseIds)
    assertEquals("validate,pr", result.missingPhaseIds.joinToString(","))
    assertEquals("telemetry.phase_strategies.dispatch_join", PHASE_STRATEGIES_DISPATCH_JOIN_SEAM)
    assertEquals("admitted dispatch entry", PHASE_STRATEGIES_DISPATCH_JOIN_EXPECTED)
  }

  private fun dispatch(
    slot: PhaseSlot,
    strategyId: String,
    revision: Int,
  ) = ResolvedPhaseStrategyDispatch(slot, strategyId, revision)

  private fun strategyId(
    values: Map<String, Map<String, Any>>?,
    phaseId: String,
  ): String = values?.get(phaseId)?.get(FeatureTaskRuntimeExecutionPlanKeys.STRATEGY_ID) as String

  private fun revision(
    values: Map<String, Map<String, Any>>?,
    phaseId: String,
  ): Int = values?.get(phaseId)?.get(FeatureTaskRuntimeExecutionPlanKeys.SEMANTIC_REVISION) as Int

  private fun phaseRecord(
    phaseId: String,
    agentId: String,
    model: String?,
  ) = FeatureTaskRuntimePhaseRecord(
    phaseId = phaseId,
    status = WorkflowStepStatus.COMPLETED,
    attemptCount = 1,
    startedAt = "2026-09-15T09:16:57Z",
    resolvedAgentId = agentId,
    launchedModel = model,
  )
}
