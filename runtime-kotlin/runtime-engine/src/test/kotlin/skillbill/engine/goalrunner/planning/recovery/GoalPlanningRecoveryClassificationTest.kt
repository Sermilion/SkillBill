package skillbill.engine.goalrunner.planning.recovery

import skillbill.contracts.workflow.featuretask.FEATURE_TASK_RUNTIME_CONTRACT_VERSION
import skillbill.engine.recovery.staleChildPlanningRecoveryCommand
import skillbill.error.shellcontent.IncompatibleGoalPlanningPreparationRecoveryError
import skillbill.error.shellcontent.InvalidFeatureTaskRuntimePhaseOutputSchemaError
import skillbill.error.shellcontent.InvalidGoalPlanningPreparationSchemaError
import kotlin.test.Test
import kotlin.test.assertContains
import kotlin.test.assertEquals

class GoalPlanningRecoveryClassificationTest {
  @Test
  fun `phase output contract version mismatch classifies as hard reset`() {
    val error =
      IncompatibleGoalPlanningPreparationRecoveryError(
        workflowId = "wftr-parent",
        subtaskId = 2,
        reason =
          "stored import provenance differs from the hydration request at " +
            "phase_output_contract_version",
      )

    assertEquals(GoalPlanningRecoveryKind.HARD_RESET, classifyGoalPlanningRecovery(error))
    val blocked = goalPlanningChildImportConflictBlockedReason("SKILL-200", 2, error)
    assertContains(blocked, goalPlanningHardResetRemedy("SKILL-200"))
    assertContains(blocked, FEATURE_TASK_RUNTIME_CONTRACT_VERSION)
    assertEquals(false, blocked.contains("goal replan"))
  }

  @Test
  fun `regenerated after hydration classifies as scoped replan`() {
    val error =
      IncompatibleGoalPlanningPreparationRecoveryError(
        workflowId = "wftr-parent",
        subtaskId = 2,
        reason =
          "stored goal planning 'plan' record for subtask 2 was already imported by this child " +
            "and the stored version now fails its projection contract. This occurs when the shared " +
            "preplan or subtask plan was regenerated after the child was hydrated, making the " +
            "previously-imported bytes stale. Projection failure: produced_outputs missing",
      )

    assertEquals(GoalPlanningRecoveryKind.SCOPED_REPLAN, classifyGoalPlanningRecovery(error))
    val blocked = goalPlanningChildImportConflictBlockedReason("SKILL-200", 2, error)
    assertContains(blocked, staleChildPlanningRecoveryCommand("SKILL-200", 2))
    assertEquals(false, blocked.contains("--hard"))
  }

  @Test
  fun `phase output schema contract const failure classifies as hard reset via cause`() {
    val cause =
      InvalidFeatureTaskRuntimePhaseOutputSchemaError(
        sourceLabel = "plan",
        reason = "contract_version: must be the constant value '0.4'",
        payloadFreeReason = "contract_version: must be the constant value '0.4'",
      )
    assertEquals(
      GoalPlanningRecoveryKind.HARD_RESET,
      classifyGoalPlanningRecovery("projection failure", cause),
    )
  }

  @Test
  fun `an untyped cause echoing hard reset remedy text classifies as scoped replan`() {
    val cause = IllegalStateException("Disk write failed; an earlier note said to hard reset the goal.")

    assertEquals(GoalPlanningRecoveryKind.SCOPED_REPLAN, classifyGoalPlanningRecovery("", cause))
  }

  @Test
  fun `preparation schema phase output provenance failure classifies as hard reset via cause`() {
    val cause =
      InvalidGoalPlanningPreparationSchemaError(
        sourceLabel = "wftr-parent",
        fieldPath = "provenance.phase_output_contract_version",
        reason = "must be the constant value '0.4'. Existing workflow state is incompatible; hard-reset it.",
      )
    assertEquals(
      GoalPlanningRecoveryKind.HARD_RESET,
      classifyGoalPlanningRecovery("stored preparation failed", cause),
    )
  }
}
