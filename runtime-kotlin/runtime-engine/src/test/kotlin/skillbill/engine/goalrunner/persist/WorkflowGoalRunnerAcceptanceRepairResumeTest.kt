package skillbill.engine.goalrunner.persist

import skillbill.engine.featuretask.lifecycle.core.AcquiredFeatureTaskRuntimeWorker
import skillbill.engine.featuretask.model.audit.AcceptanceCriterionRepairHydration
import skillbill.engine.featuretask.model.audit.AcceptanceCriterionRepairMutation
import skillbill.engine.featuretask.model.audit.AcceptanceCriterionRepairWriteResult
import skillbill.engine.featuretask.persist.acceptance.SerialRepairSqliteFixture
import skillbill.engine.featuretask.phase.core.decodePhaseRecords
import skillbill.ports.taskruntime.model.ValidatedFeatureTaskRuntimeExecutionPlan
import skillbill.ports.workflow.model.WorkflowFamily
import skillbill.workflow.engine.WorkflowEngine
import skillbill.workflow.engine.model.DurableWorkflowArtifactFamily
import skillbill.workflow.engine.model.DurableWorkflowArtifacts
import skillbill.workflow.model.WorkflowStatus
import skillbill.workflow.model.WorkflowStepStatus
import skillbill.workflow.taskruntime.artifact.asWorkflowArtifactEntry
import skillbill.workflow.taskruntime.model.audit.AcceptanceCriterionRepairOutcome
import skillbill.workflow.taskruntime.model.audit.AcceptanceCriterionRepairOutcomeKind
import skillbill.workflow.taskruntime.model.audit.AcceptanceCriterionRepairStage
import skillbill.workflow.taskruntime.model.audit.AcceptanceCriterionRepairState
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

class WorkflowGoalRunnerAcceptanceRepairResumeTest {
  @Test
  fun `explicit resume resets exhausted budget durably and preserves history while retries still stop at three`() {
    for (recordStatus in listOf(WorkflowStepStatus.BLOCKED, WorkflowStepStatus.COMPLETED)) {
      SerialRepairSqliteFixture().use { f ->
        lateinit var blocked: AcceptanceCriterionRepairState
        f.owned { worker ->
          blocked = failThreeRepairs(f, worker, f.discover(worker), "before")
        }
        f.database.transaction { unit ->
          val row = assertNotNull(unit.workflowStates.get(WorkflowFamily.TASK_RUNTIME, f.workflowId))
          val records = decodePhaseRecords(row.artifacts).toMutableMap()
          records["audit"] = records.getValue("audit").copy(status = recordStatus)
          unit.workflowStates.save(
            WorkflowFamily.TASK_RUNTIME,
            row.copy(
              workflowStatus = WorkflowStatus.BLOCKED,
              currentStepId = "audit",
              artifacts =
                DurableWorkflowArtifacts.fromMap(
                  row.artifacts +
                    DurableWorkflowArtifactFamily.FEATURE_TASK_RUNTIME_PHASE_RECORDS.entry(
                      records.mapValues { it.value.asWorkflowArtifactEntry() },
                    ),
                ),
            ),
          )
        }
        val writes = WorkflowGoalRunnerBlockWrites(WorkflowEngine(), f.clock)

        fun resume() =
          f.database.transaction { unit ->
            writes.reopenBlockedPhaseForOperatorResume(
              unit,
              "audit",
              "Operator resumed the blocked goal.",
              f.execution.identity(f.workflowId),
              ValidatedFeatureTaskRuntimeExecutionPlan.read(f.execution.encoded, f.execution.validator),
            )
          }
        assertTrue(resume())
        val resumed =
          assertIs<AcceptanceCriterionRepairHydration.Present>(
            f.service().hydrate(f.workflowId, f.binding),
          ).state
        assertEquals(0, resumed.unsuccessfulTotals.getOrDefault("AC-001", 0))
        assertEquals(AcceptanceCriterionRepairStage.DISCOVERY_ADMITTED, resumed.stage)
        assertEquals(blocked.outcomes, resumed.outcomes)
        assertEquals(blocked.criteria, resumed.criteria)
        assertEquals(blocked.unresolvedQueue, resumed.unresolvedQueue)
        assertEquals(blocked.outcomes.size, resumed.budgetStartOutcomeIndex)
        assertEquals(WorkflowStepStatus.PENDING, decodePhaseRecords(f.snapshot().artifacts).getValue("audit").status)
        assertTrue(resume())
        assertEquals(
          resumed,
          assertIs<AcceptanceCriterionRepairHydration.Present>(
            f.service().hydrate(f.workflowId, f.binding),
          ).state,
        )
        f.owned { worker ->
          val exhaustedAgain = failThreeRepairs(f, worker, resumed, "after")
          assertEquals(6, exhaustedAgain.outcomes.size)
          assertEquals(3, exhaustedAgain.unsuccessfulTotals["AC-001"])
          assertIs<AcceptanceCriterionRepairWriteResult.Refused>(
            f.write(worker, exhaustedAgain, AcceptanceCriterionRepairMutation.Reserve("fourth")),
          )
        }
      }
    }
  }

  private fun failThreeRepairs(
    f: SerialRepairSqliteFixture,
    worker: AcquiredFeatureTaskRuntimeWorker,
    initial: AcceptanceCriterionRepairState,
    prefix: String,
  ): AcceptanceCriterionRepairState {
    var state = initial
    repeat(3) { index ->
      val id = "$prefix-$index"
      state =
        assertIs<AcceptanceCriterionRepairWriteResult.Committed>(
          f.write(worker, state, AcceptanceCriterionRepairMutation.Reserve(id)),
        ).state
      state =
        assertIs<AcceptanceCriterionRepairWriteResult.Committed>(
          f.write(
            worker,
            state,
            AcceptanceCriterionRepairMutation.Outcome(
              AcceptanceCriterionRepairOutcome(
                id,
                id,
                "AC-001",
                "c".repeat(64),
                AcceptanceCriterionRepairOutcomeKind.INCOMPLETE,
                "incomplete:$id",
              ),
            ),
          ),
        ).state
    }
    assertEquals(AcceptanceCriterionRepairStage.BUDGET_BLOCKED, state.stage)
    return state
  }
}
