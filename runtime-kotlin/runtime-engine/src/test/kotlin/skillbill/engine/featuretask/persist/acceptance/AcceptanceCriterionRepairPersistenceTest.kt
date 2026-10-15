package skillbill.engine.featuretask.persist.acceptance

import skillbill.engine.featuretask.lifecycle.core.AcquiredFeatureTaskRuntimeWorker
import skillbill.engine.featuretask.model.audit.AcceptanceCriterionRepairAuditEvidence
import skillbill.engine.featuretask.model.audit.AcceptanceCriterionRepairCommit
import skillbill.engine.featuretask.model.audit.AcceptanceCriterionRepairHydration
import skillbill.engine.featuretask.model.audit.AcceptanceCriterionRepairMutation
import skillbill.engine.featuretask.model.audit.AcceptanceCriterionRepairRefusal
import skillbill.engine.featuretask.model.audit.AcceptanceCriterionRepairWriteResult
import skillbill.engine.featuretask.model.audit.ExpectedAcceptanceCriterionRepairState
import skillbill.engine.featuretask.phase.core.decodePhaseLedger
import skillbill.engine.featuretask.phase.core.decodePhaseRecords
import skillbill.engine.featuretask.phaserun.phaseRunDatabase
import skillbill.error.core.SkillBillRuntimeException
import skillbill.ports.db.DatabaseSessionFactory
import skillbill.ports.persistence.UnitOfWork
import skillbill.ports.workflow.model.WorkflowFamily
import skillbill.ports.workflow.model.toSnapshot
import skillbill.ports.workflow.toRecord
import skillbill.workflow.engine.model.DurableWorkflowArtifactFamily
import skillbill.workflow.taskruntime.artifact.decodeImplementationAttemptsFromArtifact
import skillbill.workflow.taskruntime.artifact.toAcceptanceCriterionRepairState
import skillbill.workflow.taskruntime.model.audit.AcceptanceCriterionRepairMode
import skillbill.workflow.taskruntime.model.audit.AcceptanceCriterionRepairOutcome
import skillbill.workflow.taskruntime.model.audit.AcceptanceCriterionRepairOutcomeKind
import skillbill.workflow.taskruntime.model.audit.AcceptanceCriterionRepairStage
import skillbill.workflow.taskruntime.model.audit.AcceptanceCriterionRepairState
import skillbill.workflow.taskruntime.model.core.FeatureTaskRuntimeWorkflowArtifactMap
import java.io.IOException
import kotlin.coroutines.cancellation.CancellationException
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertTrue
import skillbill.contracts.workflow.featuretask.AcceptanceCriterionRepairPayloadKeys as Keys

class AcceptanceCriterionRepairPersistenceTest {
  @Test
  fun `restart replays targeted failure once and final regression retains the same criterion budget`() {
    SerialRepairSqliteFixture().use { f ->
      f.owned { worker ->
        val initial = f.discover(worker)
        val verifying = f.prepareRepair(worker, initial, "reservation-1")
        val outcome =
          AcceptanceCriterionRepairOutcome(
            "failure-1",
            "reservation-1",
            "AC-001",
            "c".repeat(64),
            AcceptanceCriterionRepairOutcomeKind.TARGETED_FAILURE,
            "targeted:1",
          )
        val evidence = f.audit(AcceptanceCriterionRepairMode.CURRENT_CRITERION_TARGETED, verifying, outcome = outcome)
        val failed =
          assertIs<AcceptanceCriterionRepairWriteResult.Committed>(
            f.write(worker, verifying, AcceptanceCriterionRepairMutation.Outcome(outcome), evidence),
          ).state
        f.assertReplayAfterRestart(worker, verifying, failed, outcome, evidence)
        var state = closeAndReopen(f, worker, failed, outcome)
        assertEquals(1, state.unsuccessfulTotals["AC-001"])
        assertEquals("AC-001", state.currentCriterionRef)
        for (index in 3..4) {
          state =
            assertIs<AcceptanceCriterionRepairWriteResult.Committed>(
              f.write(worker, state, AcceptanceCriterionRepairMutation.Reserve("reservation-$index")),
            ).state
          state =
            assertIs<AcceptanceCriterionRepairWriteResult.Committed>(
              f.write(
                worker,
                state,
                AcceptanceCriterionRepairMutation.Outcome(
                  outcome.copy(
                    outcomeId = "incomplete-$index",
                    reservationId = "reservation-$index",
                    kind =
                      AcceptanceCriterionRepairOutcomeKind.INCOMPLETE,
                  ),
                ),
              ),
            ).state
        }
        assertEquals(AcceptanceCriterionRepairStage.BUDGET_BLOCKED, state.stage)
        assertEquals(3, state.unsuccessfulTotals["AC-001"])
        assertIs<AcceptanceCriterionRepairWriteResult.Refused>(
          f.write(
            worker,
            state,
            AcceptanceCriterionRepairMutation.Reserve("reservation-5"),
          ),
        )
        assertEquals(2, state.sourceTransitions.size)
        assertTrue(f.warnings.any { "record_kind=replay" in it && "owner_generation=" in it })
      }
    }
  }

  private fun SerialRepairSqliteFixture.assertReplayAfterRestart(
    worker: AcquiredFeatureTaskRuntimeWorker,
    verifying: AcceptanceCriterionRepairState,
    failed: AcceptanceCriterionRepairState,
    outcome: AcceptanceCriterionRepairOutcome,
    evidence: AcceptanceCriterionRepairAuditEvidence,
  ) {
    val f = this
    val restarted = f.service(phaseRunDatabase(f.root, f.clock))
    assertEquals(
      failed,
      assertIs<AcceptanceCriterionRepairHydration.Present>(
        restarted.hydrate(
          f.workflowId,
          f.binding,
        ),
      ).state,
    )
    val beforeReplay = f.snapshot().toRecord()
    assertTrue(
      assertIs<AcceptanceCriterionRepairWriteResult.Committed>(
        f.write(worker, verifying, AcceptanceCriterionRepairMutation.Outcome(outcome), evidence, restarted),
      ).replayed,
    )
    assertEquals(beforeReplay, f.snapshot().toRecord())
    val conflicting =
      AcceptanceCriterionRepairAuditEvidence(
        FeatureTaskRuntimeWorkflowArtifactMap.from(
          evidence.payload + (Keys.CHECKPOINT_ID to "different-checkpoint"),
        ),
      )
    assertIs<AcceptanceCriterionRepairWriteResult.Refused>(
      f.write(
        worker,
        verifying,
        AcceptanceCriterionRepairMutation.Outcome(outcome),
        conflicting,
        restarted,
      ),
    )
    assertEquals(beforeReplay, f.snapshot().toRecord())
  }

  private fun closeAndReopen(
    f: SerialRepairSqliteFixture,
    worker: AcquiredFeatureTaskRuntimeWorker,
    failed: AcceptanceCriterionRepairState,
    outcome: AcceptanceCriterionRepairOutcome,
  ): AcceptanceCriterionRepairState {
    val verifying = f.prepareRepair(worker, failed, "reservation-2")
    val success =
      outcome.copy(
        outcomeId = "success-2",
        reservationId = "reservation-2",
        digest =
          "e".repeat(
            64,
          ),
        kind = AcceptanceCriterionRepairOutcomeKind.TARGETED_SUCCESS,
        evidenceRef = "targeted:2",
      )
    val closed =
      assertIs<AcceptanceCriterionRepairWriteResult.Committed>(
        f.write(
          worker,
          verifying,
          AcceptanceCriterionRepairMutation.Outcome(success),
          f.audit(AcceptanceCriterionRepairMode.CURRENT_CRITERION_TARGETED, verifying, outcome = success),
        ),
      ).state
    assertEquals(AcceptanceCriterionRepairStage.FINAL_AUDIT_REQUIRED, closed.stage)
    assertTrue(decodePhaseRecords(f.snapshot().artifacts).containsKey("audit_implement_fix"))
    assertTrue(decodePhaseLedger(f.snapshot().artifacts).any { it.phaseId == "audit_implement_fix" })
    assertEquals(
      2,
      decodeImplementationAttemptsFromArtifact(
        DurableWorkflowArtifactFamily.FEATURE_TASK_RUNTIME_IMPLEMENTATION_ATTEMPTS.value(f.snapshot().artifacts),
      ).size,
    )
    return assertIs<AcceptanceCriterionRepairWriteResult.Committed>(
      f.write(
        worker,
        closed,
        AcceptanceCriterionRepairMutation.FinalAudit(
          setOf("AC-001", "AC-002"),
          setOf("AC-001"),
          "f".repeat(64),
          "d".repeat(64),
          "checkpoint:1",
        ),
        f.audit(
          AcceptanceCriterionRepairMode.FINAL_FULL,
          closed,
          outputDigest = "f".repeat(64),
          regressed =
            setOf("AC-001"),
        ),
      ),
    ).state
  }

  @Test
  fun `failed commit and cancellation roll back serial phase ledger and attempt writes before publication`() {
    SerialRepairSqliteFixture().use { f ->
      f.owned { worker ->
        val discovered =
          assertIs<AcceptanceCriterionRepairWriteResult.Committed>(
            f.write(
              worker,
              null,
              AcceptanceCriterionRepairMutation.Discover(f.admission),
              f.audit(
                AcceptanceCriterionRepairMode.INITIAL_DISCOVERY,
              ),
            ),
          ).state
        val reserved =
          assertIs<AcceptanceCriterionRepairWriteResult.Committed>(
            f.write(
              worker,
              discovered,
              AcceptanceCriterionRepairMutation.Reserve("reservation-1"),
            ),
          ).state
        val before = f.snapshot().toRecord()
        val projections = f.projections
        for (failure in listOf(IOException("commit rejected"), CancellationException("cancelled before commit"))) {
          val failing =
            object : DatabaseSessionFactory by f.database {
              override fun <T> transaction(block: (UnitOfWork) -> T): T =
                f.database.transaction { unit ->
                  block(unit)
                  assertNotNull(
                    DurableWorkflowArtifactFamily
                      .FEATURE_TASK_RUNTIME_ACCEPTANCE_CRITERION_REPAIR.value(
                        assertNotNull(
                          unit
                            .workflowStates.getFeatureTaskWorkflow(f.workflowId),
                        ).toSnapshot().artifacts,
                      ),
                  )
                  throw failure
                }
            }
          val service = f.service(failing)
          if (failure is CancellationException) {
            assertFailsWith<CancellationException> {
              f.write(
                worker,
                reserved,
                AcceptanceCriterionRepairMutation.RepairCompleted(
                  "reservation-1",
                  "repair",
                  "d"
                    .repeat(64),
                  "checkpoint:1",
                ),
                service = service,
              )
            }
          } else {
            assertFailsWith<SkillBillRuntimeException> {
              f.write(
                worker,
                reserved,
                AcceptanceCriterionRepairMutation.RepairCompleted(
                  "reservation-1",
                  "repair",
                  "d"
                    .repeat(64),
                  "checkpoint:1",
                ),
                service = service,
              )
            }
          }
          assertEquals(before, f.snapshot().toRecord())
          assertEquals(projections, f.projections)
        }
        assertTrue(f.warnings.any { "record_kind=rollback" in it && "reservation=reservation-1" in it })
        assertTrue(f.warnings.any { "record_kind=cancellation" in it })
      }
    }
  }

  @Test
  fun `failed closure commit leaves source unresolved and publishes no closure`() {
    SerialRepairSqliteFixture().use { f ->
      f.owned { worker ->
        val verifying = f.prepareRepair(worker, f.discover(worker), "reservation-1")
        val success =
          AcceptanceCriterionRepairOutcome(
            "success",
            "reservation-1",
            "AC-001",
            "e".repeat(64),
            AcceptanceCriterionRepairOutcomeKind.TARGETED_SUCCESS,
            "targeted:success",
          )
        val before = f.snapshot().toRecord()
        val projections = f.projections
        val failing =
          object : DatabaseSessionFactory by f.database {
            override fun <T> transaction(block: (UnitOfWork) -> T): T =
              f.database.transaction { unit ->
                block(unit)
                val staged = assertNotNull(unit.workflowStates.get(WorkflowFamily.TASK_RUNTIME, f.workflowId))
                val state =
                  FeatureTaskRuntimeWorkflowArtifactMap.from(
                    DurableWorkflowArtifactFamily.FEATURE_TASK_RUNTIME_ACCEPTANCE_CRITERION_REPAIR.value(
                      staged.artifacts,
                    ),
                  ).toAcceptanceCriterionRepairState("staged")
                assertEquals(AcceptanceCriterionRepairStage.FINAL_AUDIT_REQUIRED, state.stage)
                throw IOException("closure commit rejected")
              }
          }
        assertFailsWith<SkillBillRuntimeException> {
          f.write(
            worker,
            verifying,
            AcceptanceCriterionRepairMutation.Outcome(success),
            f.audit(AcceptanceCriterionRepairMode.CURRENT_CRITERION_TARGETED, verifying, outcome = success),
            f.service(failing),
          )
        }
        assertEquals(before, f.snapshot().toRecord())
        assertEquals(projections, f.projections)
      }
    }
  }

  @Test
  fun `stale expected revision and changed source refuse without serial or phase writes`() {
    SerialRepairSqliteFixture().use { f ->
      f.owned { worker ->
        val state = f.discover(worker)
        val before = f.snapshot().toRecord()
        val projections = f.projections
        val mutation = AcceptanceCriterionRepairMutation.Reserve("reservation-1")
        assertEquals(
          AcceptanceCriterionRepairRefusal.EXPECTED_STATE_CHANGED,
          assertIs<AcceptanceCriterionRepairWriteResult.Refused>(
            f.persistence.write(
              worker,
              f.binding,
              AcceptanceCriterionRepairCommit(
                ExpectedAcceptanceCriterionRepairState(-1, state.currentCriterionRef, null),
                mutation,
                f.phase(
                  mutation,
                ),
                null,
              ),
              f.root,
            ),
          ).reason,
        )
        f.sourceDigest = "e".repeat(64)
        assertEquals(
          AcceptanceCriterionRepairRefusal.IDENTITY_CHANGED,
          assertIs<AcceptanceCriterionRepairWriteResult.Refused>(f.write(worker, state, mutation)).reason,
        )
        assertEquals(before, f.snapshot().toRecord())
        assertEquals(projections, f.projections)
      }
    }
  }

  @Test
  fun `post commit projection failure regenerates from SQLite without repeating the serial transition`() {
    SerialRepairSqliteFixture().use { f ->
      f.owned { worker ->
        f.project = { throw IOException("projection path unavailable") }
        val result =
          assertIs<AcceptanceCriterionRepairWriteResult.CommittedWithProjectionFailure>(
            f.write(
              worker,
              null,
              AcceptanceCriterionRepairMutation.Discover(f.admission),
              f.audit(
                AcceptanceCriterionRepairMode.INITIAL_DISCOVERY,
              ),
            ),
          )
        val before = f.snapshot().toRecord()
        f.project = { request -> assertEquals(result.state, request.state) }
        assertIs<AcceptanceCriterionRepairWriteResult.Committed>(
          f.service(
            phaseRunDatabase(
              f.root,
              f.clock,
            ),
          ).recoverProjection(f.root, f.workflowId, f.binding),
        )
        assertEquals(before, f.snapshot().toRecord())
        assertEquals(2, f.projections)
      }
    }
  }

  @Test
  fun `reserved takeover and escaped acquired worker cannot write serial state`() {
    SerialRepairSqliteFixture().use { f ->
      lateinit var escaped: AcquiredFeatureTaskRuntimeWorker
      f.owned { worker ->
        escaped = worker
        val before = f.snapshot().toRecord()
        f.database.transaction { unit ->
          val owner = assertNotNull(unit.workflowStates.getFeatureTaskRuntimeWorkerOwnership(f.workflowId))
          assertTrue(
            unit.workflowStates.reserveFeatureTaskRuntimeWorkerTakeover(
              f.workflowId,
              owner
                .ownerToken,
              owner.generation,
            ),
          )
        }
        assertEquals(
          AcceptanceCriterionRepairRefusal.OWNER_CHANGED,
          assertIs<AcceptanceCriterionRepairWriteResult.Refused>(
            f.write(
              worker,
              null,
              AcceptanceCriterionRepairMutation.Discover(f.admission),
              f.audit(
                AcceptanceCriterionRepairMode.INITIAL_DISCOVERY,
              ),
            ),
          ).reason,
        )
        assertEquals(before, f.snapshot().toRecord())
      }
      assertEquals(
        AcceptanceCriterionRepairRefusal.OWNER_CHANGED,
        assertIs<AcceptanceCriterionRepairWriteResult.Refused>(
          f.write(
            escaped,
            null,
            AcceptanceCriterionRepairMutation.Discover(f.admission),
            f.audit(
              AcceptanceCriterionRepairMode.INITIAL_DISCOVERY,
            ),
          ),
        ).reason,
      )
      assertEquals(0, f.projections)
    }
  }
}
