package skillbill.engine.goalrunner.persist

import skillbill.contracts.workflow.featuretask.ACCEPTANCE_CRITERION_REPAIR_STATE_PREVIOUS_CONTRACT_VERSION
import skillbill.engine.featuretask.lifecycle.core.AcquiredFeatureTaskRuntimeWorker
import skillbill.engine.featuretask.model.audit.AcceptanceCriterionRepairHydration
import skillbill.engine.featuretask.model.audit.AcceptanceCriterionRepairMutation
import skillbill.engine.featuretask.model.audit.AcceptanceCriterionRepairWriteResult
import skillbill.engine.featuretask.persist.acceptance.SerialRepairSqliteFixture
import skillbill.engine.featuretask.persist.acceptance.hydrateForTest
import skillbill.engine.featuretask.phase.core.decodePhaseRecords
import skillbill.engine.goal.OperatorBlockedPhaseReopenRequest
import skillbill.ports.diagnostics.NoopRuntimeDiagnostics
import skillbill.ports.taskruntime.model.ValidatedFeatureTaskRuntimeExecutionPlan
import skillbill.ports.workflow.model.WorkflowFamily
import skillbill.workflow.engine.model.DurableWorkflowArtifactFamily
import skillbill.workflow.engine.model.DurableWorkflowArtifacts
import skillbill.workflow.model.WorkflowStatus
import skillbill.workflow.model.WorkflowStepStatus
import skillbill.workflow.taskruntime.artifact.asRepairWireMap
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
import skillbill.contracts.workflow.featuretask.AcceptanceCriterionRepairPayloadKeys as Keys

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
                    DurableWorkflowArtifactFamily.FEATURE_TASK_RUNTIME_ACCEPTANCE_CRITERION_REPAIR.entry(
                      oldVersion02(blocked),
                    ) +
                    DurableWorkflowArtifactFamily.FEATURE_TASK_RUNTIME_PHASE_RECORDS.entry(
                      records.mapValues { it.value.asWorkflowArtifactEntry() },
                    ),
                ),
            ),
          )
        }
        val writes = WorkflowGoalRunnerBlockWrites(f.clock, NoopRuntimeDiagnostics)

        fun resume() =
          f.database.transaction { unit ->
            writes.reopenBlockedPhaseForOperatorResume(
              unit,
              OperatorBlockedPhaseReopenRequest("audit", "Operator resumed the blocked goal."),
              f.execution.identity(f.workflowId),
              ValidatedFeatureTaskRuntimeExecutionPlan.read(f.execution.encoded, f.execution.validator),
            )
          }
        assertTrue(resume())
        val resumed =
          assertIs<AcceptanceCriterionRepairHydration.Present>(
            f.service().hydrateForTest(f.workflowId, f.binding),
          ).state
        assertEquals(0, resumed.chargedAttempts.getOrDefault("AC-001", 0))
        assertEquals(AcceptanceCriterionRepairStage.DISCOVERY_ADMITTED, resumed.stage)
        assertEquals(blocked.outcomes.map { it.outcomeId }, resumed.outcomes.map { it.outcomeId })
        assertTrue(resumed.outcomes.none { it.charged })
        assertEquals(blocked.criteria.map { it.copy(escalated = false) }, resumed.criteria)
        assertEquals(blocked.unresolvedQueue, resumed.unresolvedQueue)
        assertEquals(WorkflowStepStatus.PENDING, decodePhaseRecords(f.snapshot().artifacts).getValue("audit").status)
        assertTrue(resume())
        assertEquals(
          resumed,
          assertIs<AcceptanceCriterionRepairHydration.Present>(
            f.service().hydrateForTest(f.workflowId, f.binding),
          ).state,
        )
        f.owned { worker ->
          val exhaustedAgain = failThreeRepairs(f, worker, resumed, "after")
          assertEquals(6, exhaustedAgain.outcomes.size)
          assertEquals(3, exhaustedAgain.chargedAttempts["AC-001"])
          assertIs<AcceptanceCriterionRepairWriteResult.Refused>(
            f.write(worker, exhaustedAgain, AcceptanceCriterionRepairMutation.Reserve("fourth")),
          )
        }
      }
    }
  }

  private fun oldVersion02(state: AcceptanceCriterionRepairState): Map<String, Any?> {
    val wire = state.asRepairWireMap().toMutableMap()
    wire[Keys.CONTRACT_VERSION] = ACCEPTANCE_CRITERION_REPAIR_STATE_PREVIOUS_CONTRACT_VERSION
    wire[Keys.CRITERIA] =
      assertIs<List<*>>(wire[Keys.CRITERIA]).map { raw ->
        assertIs<Map<*, *>>(raw).filterKeys { it !in setOf(Keys.GAPS, Keys.PLAN_GENERATION, Keys.ESCALATED) }
      }
    wire[Keys.OUTCOMES] =
      assertIs<List<*>>(wire[Keys.OUTCOMES]).map { raw ->
        assertIs<Map<*, *>>(raw).filterKeys { it !in setOf(Keys.CHARGED, Keys.OPEN_GAPS_BEFORE, Keys.OPEN_GAPS_AFTER) }
      }
    wire[Keys.UNSUCCESSFUL_TOTALS] = state.chargedAttempts
    wire[Keys.BUDGET_START_OUTCOME_INDEX] = 0
    wire.remove(Keys.CHARGED_ATTEMPTS)
    wire.remove(Keys.LATE_FINDINGS)
    wire.remove(Keys.REPAIR_CHANGED_PATHS)
    return wire
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
