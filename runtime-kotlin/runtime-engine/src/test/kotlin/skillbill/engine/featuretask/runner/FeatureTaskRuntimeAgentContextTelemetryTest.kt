package skillbill.engine.featuretask.runner

import skillbill.engine.featuretask.lifecycle.core.featureTaskRuntimeAgentContext
import skillbill.workflow.model.WorkflowStepStatus
import skillbill.workflow.taskruntime.model.phase.FeatureTaskRuntimePhaseRecord
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

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
