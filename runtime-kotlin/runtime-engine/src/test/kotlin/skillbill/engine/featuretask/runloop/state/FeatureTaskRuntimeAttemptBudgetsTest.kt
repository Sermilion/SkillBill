package skillbill.engine.featuretask.runloop.state

import skillbill.engine.featuretask.lifecycle.remediation.featureTaskRuntimeRepeatedUnresolvedBlockReason
import skillbill.engine.featuretask.phase.prompt.compose.productionStrategyFor
import skillbill.workflow.taskruntime.model.skeleton.PhaseStepPolicy
import skillbill.workflow.taskruntime.phase.task.FeatureTaskRuntimePhaseWorkflowDefinition
import kotlin.test.Test
import kotlin.test.assertContains
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class FeatureTaskRuntimeAttemptBudgetsTest {
  @Test
  fun `default output-gate retries cap at one and process-failure stays at three`() {
    assertEquals(1, FeatureTaskRuntimeAttemptBudgets.MAX_OUTPUT_GATE_RETRY_ATTEMPTS)
    assertEquals(3, FeatureTaskRuntimeAttemptBudgets.MAX_PROCESS_FAILURE_ATTEMPTS)
  }

  @Test
  fun `a phase that keeps dying before its output gate blocks on its own budget, not a repair loop`() {
    val below =
      FeatureTaskRuntimeAttemptBudgets.processFailureBlockReason(
        FeatureTaskRuntimePhaseWorkflowDefinition.PHASE_VALIDATE,
        RELAUNCHING_POLICY,
        processFailureCount = FeatureTaskRuntimeAttemptBudgets.MAX_PROCESS_FAILURE_ATTEMPTS - 1,
        lastFailureReason = "agent exited with non-zero status 1",
      )
    assertEquals(null, below)

    val blocked =
      requireNotNull(
        FeatureTaskRuntimeAttemptBudgets.processFailureBlockReason(
          FeatureTaskRuntimePhaseWorkflowDefinition.PHASE_VALIDATE,
          RELAUNCHING_POLICY,
          processFailureCount = FeatureTaskRuntimeAttemptBudgets.MAX_PROCESS_FAILURE_ATTEMPTS,
          lastFailureReason = "agent exited with non-zero status 1",
        ),
      )
    assertContains(blocked, "failed to execute")
    assertContains(blocked, "No repair attempt was consumed.")
    assertContains(blocked, "agent exited with non-zero status 1")
    assertTrue(!blocked.contains("invalid output"), blocked)
    assertTrue(!blocked.contains("fix loop"), blocked)
  }

  @Test
  fun `validate malformed output blocks after its single session`() {
    val phase = FeatureTaskRuntimePhaseWorkflowDefinition.PHASE_VALIDATE
    val policy = productionPolicy(phase)
    assertContains(requireNotNull(FeatureTaskRuntimeAttemptBudgets.outputGateBlockReason(phase, policy, 1)), "cap=1")
    assertEquals(true, policy.singleAgentSession)
  }

  @Test
  fun `the first schema-invalid output blocks instead of relaunching`() {
    val phase = FeatureTaskRuntimePhaseWorkflowDefinition.PHASE_AUDIT
    val reason = FeatureTaskRuntimeAttemptBudgets.outputGateBlockReason(phase, productionPolicy(phase), 1)
    assertContains(requireNotNull(reason), "cap=1")
    assertContains(reason, "blocks rather than relaunching")
  }

  @Test
  fun `a round that drops findings is sent back while it keeps closing them, and blocks when it stalls`() {
    val phase = FeatureTaskRuntimePhaseWorkflowDefinition.PHASE_IMPLEMENT_FIX
    val firstOmission = setOf("F-001", "F-003")

    assertEquals(
      null,
      FeatureTaskRuntimeAttemptBudgets.findingCoverageBlockReason(phase, firstOmission, priorOmitted = null),
    )
    assertEquals(
      null,
      FeatureTaskRuntimeAttemptBudgets.findingCoverageBlockReason(phase, setOf("F-003"), firstOmission),
    )

    val stalled =
      requireNotNull(
        FeatureTaskRuntimeAttemptBudgets.findingCoverageBlockReason(phase, firstOmission, firstOmission),
      )
    assertContains(stalled, "F-001, F-003")
    assertContains(stalled, "attempted_unresolved")

    val substituted =
      requireNotNull(
        FeatureTaskRuntimeAttemptBudgets.findingCoverageBlockReason(phase, setOf("F-002"), setOf("F-001")),
      )
    assertContains(substituted, "no progress on coverage")
  }

  @Test
  fun `a finding reported unresolved gets one more fix attempt and blocks on the second report`() {
    val phase = FeatureTaskRuntimePhaseWorkflowDefinition.PHASE_IMPLEMENT_FIX
    val detail = "F-001 (the migration path has no owner)"

    assertEquals(
      null,
      featureTaskRuntimeRepeatedUnresolvedBlockReason(
        phase,
        unresolved = setOf("F-001"),
        priorUnresolved = emptySet(),
        detail = detail,
      ),
    )
    assertEquals(
      null,
      featureTaskRuntimeRepeatedUnresolvedBlockReason(
        phase,
        unresolved = setOf("F-002"),
        priorUnresolved = setOf("F-001"),
        detail = detail,
      ),
    )

    val repeated =
      requireNotNull(
        featureTaskRuntimeRepeatedUnresolvedBlockReason(
          phase,
          unresolved = setOf("F-001", "F-003"),
          priorUnresolved = setOf("F-001"),
          detail = detail,
        ),
      )
    assertContains(repeated, "F-001")
    assertTrue(!repeated.contains("F-003"), "only the repeated finding exhausted its retry: $repeated")
    assertContains(repeated, detail)
  }

  @Test
  fun `a rejection reports the budget spent exactly when the loop refuses to relaunch`() {
    val phase = FeatureTaskRuntimePhaseWorkflowDefinition.PHASE_IMPLEMENT
    (0..3).forEach { priorFailures ->
      assertEquals(
        FeatureTaskRuntimeAttemptBudgets.outputGateBlockReason(phase, RELAUNCHING_POLICY, priorFailures + 1) != null,
        FeatureTaskRuntimeAttemptBudgets.outputGateRejectionExhaustsBudget(phase, RELAUNCHING_POLICY, priorFailures),
        "the rejection record and the block decision must agree at $priorFailures prior failures",
      )
    }
  }

  @Test
  fun `a phase that never relaunches on invalid output spends its budget on the first rejection`() {
    assertTrue(
      FeatureTaskRuntimeAttemptBudgets.outputGateRejectionExhaustsBudget(
        FeatureTaskRuntimePhaseWorkflowDefinition.PHASE_AUDIT,
        RELAUNCHING_POLICY.copy(singleAgentSession = true),
        priorOutputGateFailures = 0,
      ),
      "a single-agent-session phase blocks on its first rejection, so that rejection spent the budget",
    )
  }

  private companion object {
    val RELAUNCHING_POLICY =
      PhaseStepPolicy(
        mutating = false,
        singleAgentSession = false,
        readOnlyIdle = false,
        fileMutating = true,
        generationScoped = false,
      )

    fun productionPolicy(stepId: String): PhaseStepPolicy = productionStrategyFor(stepId).policyFor(stepId)
  }
}
