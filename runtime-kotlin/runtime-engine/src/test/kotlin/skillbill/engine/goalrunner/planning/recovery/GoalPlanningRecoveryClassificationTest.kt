package skillbill.engine.goalrunner.planning.recovery

import skillbill.engine.recovery.staleChildPlanningRecoveryCommand
import skillbill.error.core.SkillBillRuntimeException
import skillbill.error.featuretask.FeatureTaskRuntimeMigrationFailureCode
import skillbill.error.shellcontent.IncompatibleGoalPlanningPreparationRecoveryError
import skillbill.error.shellcontent.InvalidFeatureTaskRuntimePhaseOutputSchemaError
import skillbill.error.shellcontent.InvalidGoalPlanningPreparationSchemaError
import kotlin.test.Test
import kotlin.test.assertContains
import kotlin.test.assertEquals

class GoalPlanningRecoveryClassificationTest {
  @Test
  fun `typed unsupported phase output blocks without destructive reset`() {
    val error =
      IncompatibleGoalPlanningPreparationRecoveryError(
        workflowId = "wftr-parent",
        subtaskId = 2,
        reason =
          "stored import provenance differs from the hydration request at " +
            "phase_output_contract_version",
        cause =
          SkillBillRuntimeException(
            FeatureTaskRuntimeMigrationFailureCode.SOURCE_UNSUPPORTED,
            "unsupported version",
          ),
      )

    assertEquals(GoalPlanningRecoveryKind.BLOCKED, classifyGoalPlanningRecovery(error))
    val blocked = goalPlanningChildImportConflictBlockedReason("SKILL-200", 2, error)
    assertContains(blocked, "Keep the workflow")
    assertEquals(false, blocked.contains("--hard"))
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
  fun `phase output schema contract const failure blocks with original state preserved`() {
    val cause =
      InvalidFeatureTaskRuntimePhaseOutputSchemaError(
        sourceLabel = "plan",
        reason = "contract_version: must be the constant value '0.4'",
        payloadFreeReason = "contract_version: must be the constant value '0.4'",
      )
    assertEquals(
      GoalPlanningRecoveryKind.BLOCKED,
      classifyGoalPlanningRecovery(cause),
    )
  }

  @Test
  fun `an untyped cause echoing hard reset remedy text classifies as scoped replan`() {
    val cause = IllegalStateException("Disk write failed; an earlier note said to hard reset the goal.")

    assertEquals(GoalPlanningRecoveryKind.SCOPED_REPLAN, classifyGoalPlanningRecovery(cause))
  }

  @Test
  fun `preparation schema phase output provenance failure blocks with original state preserved`() {
    val cause =
      InvalidGoalPlanningPreparationSchemaError(
        sourceLabel = "wftr-parent",
        fieldPath = "provenance.phase_output_contract_version",
        reason = "must be the constant value '0.4'. Existing workflow state is incompatible; hard-reset it.",
      )
    assertEquals(
      GoalPlanningRecoveryKind.BLOCKED,
      classifyGoalPlanningRecovery(cause),
    )
  }
}
