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
  fun `resume with a different caller agent keeps the accepted model and effort`() {
    val recorded = assignment("codex", "gpt-6.1-sol", "high")

    assertEquals(
      PhaseModelDirective("gpt-6.1-sol", "high"),
      FeatureTaskRuntimeResumeLaunchAssignment.modelDirective(
        recorded,
        "cursor",
        "audit_plan_fix",
        FeatureTaskRuntimeModelAssignment(matrix = matrix()),
      ),
    )
  }

  @Test
  fun `resume without an accepted model keeps the provider default after matrix changes`() {
    val recorded = assignment("opencode", null, null)

    assertNull(
      FeatureTaskRuntimeResumeLaunchAssignment.modelDirective(
        recorded,
        "cursor",
        "audit_plan_fix",
        FeatureTaskRuntimeModelAssignment(matrix = matrix()),
      ),
    )
  }

  @Test
  fun `used assignments retain admitted providers and include newly admitted steps`() {
    val recorded = mapOf("audit_plan_fix" to assignment("codex", "gpt-6.1-sol", "high"))
    val next = assignment("opencode", "provider/new-model", effort = null).copy(stepId = "qa")
    val current = mapOf("audit_plan_fix" to assignment("cursor", "cursor-grok-4.6-high", null), "qa" to next)

    assertEquals(
      recorded + ("qa" to next),
      FeatureTaskRuntimeResumeLaunchAssignment.usedAssignments(recorded, current),
    )
  }

  private fun assignment(
    agentId: String,
    model: String?,
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
