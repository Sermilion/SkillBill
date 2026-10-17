package skillbill.engine.featuretask.lifecycle.core

import skillbill.config.model.ExecutionMatrix
import skillbill.config.model.ExecutionTier
import skillbill.config.model.PhaseModelDirective
import skillbill.engine.featuretask.model.core.FeatureTaskRuntimeModelAssignment
import skillbill.install.model.SupportedAgent
import skillbill.workflow.taskruntime.model.skeleton.StepLaunchAssignment
import skillbill.workflow.taskruntime.model.skeleton.passThroughLaunchModel
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class FeatureTaskRuntimeResumeLaunchAssignmentTest {
  @Test
  fun `same-agent resume keeps the recorded model when the matrix has drifted`() {
    val recorded = assignment("codex", "gpt-6.1-sol", "high")

    assertEquals(recorded, FeatureTaskRuntimeResumeLaunchAssignment.recordedForAgent(recorded, "codex"))
    assertEquals(
      PhaseModelDirective("gpt-6.1-sol", "high"),
      FeatureTaskRuntimeResumeLaunchAssignment.modelDirective(
        recorded,
        "codex",
        "audit_plan_fix",
        FeatureTaskRuntimeModelAssignment(matrix = matrix()),
      ),
    )
  }

  @Test
  fun `resume with a different agent uses that agent's matrix instead of the recorded model`() {
    val recorded = assignment("codex", "gpt-6.1-sol", "high")

    assertNull(FeatureTaskRuntimeResumeLaunchAssignment.recordedForAgent(recorded, "cursor"))
    assertEquals(
      PhaseModelDirective("cursor-grok-4.6-high"),
      FeatureTaskRuntimeResumeLaunchAssignment.modelDirective(
        recorded,
        "cursor",
        "audit_plan_fix",
        FeatureTaskRuntimeModelAssignment(matrix = matrix()),
      ),
    )
  }

  @Test
  fun `resume with a different agent omits a recorded model when the selected agent has no matrix entry`() {
    val recorded = assignment("codex", "gpt-6.1-sol", "high")

    assertNull(
      FeatureTaskRuntimeResumeLaunchAssignment.modelDirective(
        recorded,
        "cursor",
        "audit_plan_fix",
        FeatureTaskRuntimeModelAssignment(),
      ),
    )
  }

  @Test
  fun `used assignments replace recorded steps whose agent is not the selected resume agent`() {
    val recorded = mapOf("audit_plan_fix" to assignment("codex", "gpt-6.1-sol", "high"))
    val current = mapOf("audit_plan_fix" to assignment("cursor", "cursor-grok-4.6-high", effort = null))

    assertEquals(
      current,
      FeatureTaskRuntimeResumeLaunchAssignment.usedAssignments(recorded, current) { "cursor" },
    )
    assertEquals(
      recorded,
      FeatureTaskRuntimeResumeLaunchAssignment.usedAssignments(recorded, current) { "codex" },
    )
  }

  private fun assignment(
    agentId: String,
    model: String,
    effort: String?,
  ): StepLaunchAssignment =
    StepLaunchAssignment(
      stepId = "audit_plan_fix",
      agentId = agentId,
      launch = passThroughLaunchModel(model, effort),
    )

  private fun matrix(): ExecutionMatrix =
    ExecutionMatrix(
      agents =
        mapOf(
          SupportedAgent.CODEX to
            mapOf(ExecutionTier.REASONING to PhaseModelDirective("gpt-5.6-sol", "high")),
          SupportedAgent.CURSOR to
            mapOf(ExecutionTier.REASONING to PhaseModelDirective("cursor-grok-4.6-high")),
        ),
    )
}
