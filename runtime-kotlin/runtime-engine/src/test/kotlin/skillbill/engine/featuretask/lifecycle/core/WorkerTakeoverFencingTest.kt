package skillbill.engine.featuretask.lifecycle.core

import skillbill.application.testHarnessClock
import skillbill.contracts.JsonCodec
import skillbill.engine.featuretask.lifecycle.execution.ExecutionPlanAdmissionFixture
import skillbill.engine.featuretask.model.phase.FeatureTaskRuntimePhaseStateRequest
import skillbill.engine.featuretask.phase.record.featureTaskRuntimePhaseRecorder
import skillbill.engine.featuretask.phaserun.phaseRunDatabase
import skillbill.engine.featuretask.runner.NoopWorkflowSnapshotValidator
import skillbill.error.featuretask.FeatureTaskRuntimeExecutionPlanAdmissionError
import skillbill.error.featuretask.IncompatibleFeatureTaskRuntimeExecutionPlanError
import skillbill.error.shellcontent.InvalidFeatureTaskExecutionIdentitySchemaError
import skillbill.ports.db.DatabaseSessionFactory
import skillbill.ports.diagnostics.NoopRuntimeDiagnostics
import skillbill.ports.featuretask.model.FeatureTaskRuntimeWorkerLeaseState
import skillbill.ports.featuretask.model.FeatureTaskRuntimeWorkerOwnership
import skillbill.ports.taskruntime.FeatureTaskRuntimeHeartbeat
import skillbill.ports.taskruntime.FeatureTaskRuntimeWorkerSupervisor
import skillbill.ports.taskruntime.model.FeatureTaskRuntimeHeartbeatPlan
import skillbill.ports.taskruntime.model.FeatureTaskRuntimeHeartbeatTick
import skillbill.ports.taskruntime.model.FeatureTaskRuntimeProcessIdentity
import skillbill.ports.taskruntime.model.FeatureTaskRuntimeProcessInspection
import skillbill.ports.workflow.model.toSnapshot
import skillbill.workflow.engine.model.DurableWorkflowArtifactFamily
import skillbill.workflow.model.FeatureTaskRouteScope
import skillbill.workflow.model.FeatureTaskWorkflowMode
import skillbill.workflow.model.WorkflowStatus
import java.nio.file.Files
import java.sql.DriverManager
import java.time.Duration
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue
import skillbill.contracts.workflow.identity.task.FeatureTaskRuntimeExecutionPlanKeys as Keys

class WorkerTakeoverFencingTest {
  @Test
  fun `stale crash candidate cannot remove terminal or reserved lease evidence`() {
    (WorkflowStatus.terminalStatuses + WorkflowStatus.RUNNING).forEach { status ->
      withOwnedWorkflow { database, original ->
        database.transaction { unit ->
          val row = assertNotNull(unit.workflowStates.getFeatureTaskWorkflow(original.workflowId))
          unit.workflowStates.saveFeatureTaskWorkflow(
            row.copy(workflowStatus = status.wireValue),
            FeatureTaskWorkflowMode.RUNTIME,
          )
          if (status == WorkflowStatus.RUNNING) {
            assertTrue(
              unit.workflowStates.reserveFeatureTaskRuntimeWorkerTakeover(
                original.workflowId,
                original.ownerToken,
                original.generation,
              ),
            )
          }
        }
        val before = database.read { it.workflowStates.getFeatureTaskWorkflow(original.workflowId) }
        val lease = database.read { it.workflowStates.getFeatureTaskRuntimeWorkerOwnership(original.workflowId) }
        database.transaction { unit ->
          assertTrue(
            unit.workflowStates.findFeatureTaskRuntimeCrashReconciliationCandidates("3000-01-01T00:00:00Z").isEmpty(),
          )
          assertFalse(
            unit.workflowStates.reconcileFeatureTaskRuntimeCrashedWorker(
              original.workflowId,
              original.ownerToken,
              original.generation,
              "lease_expired",
              "3000-01-01T00:00:00Z",
            ),
          )
        }
        database.read { unit ->
          assertEquals(before, unit.workflowStates.getFeatureTaskWorkflow(original.workflowId))
          assertEquals(lease, unit.workflowStates.getFeatureTaskRuntimeWorkerOwnership(original.workflowId))
        }
      }
    }
  }

  @Test
  fun `stale token generation and competing reservation cannot terminate or launch a worker`() {
    listOf("token", "generation", "reservation").forEach { race ->
      withOwnedWorkflow { database, original ->
        val supervisor =
          TakeoverSupervisor(
            onInspect = {
              database.transaction { unit ->
                assertTrue(
                  unit.workflowStates.reserveFeatureTaskRuntimeWorkerTakeover(
                    original.workflowId,
                    original.ownerToken,
                    original.generation,
                  ),
                )
                if (race != "reservation") {
                  val replacement =
                    original.copy(
                      ownerToken = if (race == "token") "competing-owner-token" else original.ownerToken,
                      generation = if (race == "generation") original.generation + 1 else original.generation,
                    )
                  assertTrue(
                    unit.workflowStates.transferFeatureTaskRuntimeWorker(
                      replacement,
                      original.ownerToken,
                      original.generation,
                    ),
                  )
                }
              }
            },
          )
        val before = database.read { it.workflowStates.getFeatureTaskWorkflow(original.workflowId) }
        val coordinator =
          FeatureTaskRuntimeWorkerCoordinator(database, supervisor, testHarnessClock, execution.admission)
        var launches = 0

        assertFailsWith<IllegalStateException>(race) {
          coordinator.runOwned(
            original.workflowId,
            execution.inputs,
            execution.identity(original.workflowId),
          ) { launches++ }
        }

        assertEquals(0, launches, race)
        assertEquals(0, supervisor.terminations, race)
        assertEquals(0, supervisor.heartbeats, race)
        database.read { unit ->
          assertEquals(before, unit.workflowStates.getFeatureTaskWorkflow(original.workflowId), race)
          val retained = assertNotNull(unit.workflowStates.getFeatureTaskRuntimeWorkerOwnership(original.workflowId))
          when (race) {
            "token" -> assertEquals(original.copy(ownerToken = "competing-owner-token"), retained)
            "generation" -> assertEquals(original.copy(generation = original.generation + 1), retained)
            else ->
              assertEquals(
                original.copy(leaseState = FeatureTaskRuntimeWorkerLeaseState.TAKEOVER_RESERVED),
                retained,
              )
          }
        }
      }
    }
  }

  @Test
  fun `live worker termination follows durable reservation and replacement retains a higher generation`() {
    withOwnedWorkflow { database, original ->
      val supervisor =
        TakeoverSupervisor(onTerminate = {
          database.read { unit ->
            assertEquals(
              original.copy(leaseState = FeatureTaskRuntimeWorkerLeaseState.TAKEOVER_RESERVED),
              unit.workflowStates.getFeatureTaskRuntimeWorkerOwnership(original.workflowId),
            )
          }
        })
      val coordinator = FeatureTaskRuntimeWorkerCoordinator(database, supervisor, testHarnessClock, execution.admission)
      coordinator.runOwned(original.workflowId, execution.inputs, execution.identity(original.workflowId)) { admitted ->
        assertEquals(original.workflowId, admitted.identity.workflowId)
        assertContentEquals(execution.encoded, execution.codec.encode(admitted.plan))
        database.read { unit ->
          val replacement = assertNotNull(unit.workflowStates.getFeatureTaskRuntimeWorkerOwnership(original.workflowId))
          assertEquals(original.generation + 1, replacement.generation)
          assertEquals(FeatureTaskRuntimeWorkerLeaseState.ACTIVE, replacement.leaseState)
          assertTrue(replacement.ownerToken != original.ownerToken)
        }
      }
      assertEquals(1, supervisor.terminations)
      assertEquals(1, supervisor.heartbeats)
      database.read { assertNull(it.workflowStates.getFeatureTaskRuntimeWorkerOwnership(original.workflowId)) }
    }
  }

  @Test
  fun `worker admission refuses invalid descriptors before acquisition or takeover`() {
    val descriptor = execution.descriptor()
    val changed =
      execution.validator.read(
        execution.codec.encodeExecution(execution.plan, execution.inputs.copy(phaseTimeoutMillis = 1)),
        "changed timeout",
      )
    listOf(null, "malformed", descriptor + (Keys.CONTRACT_VERSION to "9.0"), changed).forEach { supplied ->
      listOf(false, true).forEach { owned ->
        withOwnedWorkflow { database, original ->
          if (!owned) {
            database.transaction {
              assertTrue(
                it.workflowStates.releaseFeatureTaskRuntimeWorker(
                  original.workflowId,
                  original.ownerToken,
                  original.generation,
                ),
              )
            }
          }
          replaceWorkerDescriptor(database, original.workflowId, supplied)
          val before = database.read { it.workflowStates.getFeatureTaskWorkflow(original.workflowId) }
          val lease = database.read { it.workflowStates.getFeatureTaskRuntimeWorkerOwnership(original.workflowId) }
          val supervisor = TakeoverSupervisor()
          val coordinator =
            FeatureTaskRuntimeWorkerCoordinator(database, supervisor, testHarnessClock, execution.admission)
          var launches = 0

          assertFailsWith<FeatureTaskRuntimeExecutionPlanAdmissionError> {
            coordinator.runOwned(
              original.workflowId,
              execution.inputs,
              execution.identity(original.workflowId),
            ) { launches++ }
          }

          assertEquals(0, launches)
          assertEquals(0, supervisor.terminations)
          assertEquals(0, supervisor.heartbeats)
          database.read {
            assertEquals(before, it.workflowStates.getFeatureTaskWorkflow(original.workflowId))
            assertEquals(lease, it.workflowStates.getFeatureTaskRuntimeWorkerOwnership(original.workflowId))
          }
        }
      }
    }
  }

  @Test
  fun `worker refuses a caller from another repository route issue or spec before reserving ownership`() {
    withOwnedWorkflow { database, original ->
      val identity = execution.identity(original.workflowId)
      val before = database.read { it.workflowStates.getFeatureTaskWorkflow(original.workflowId) }
      val supervisor = TakeoverSupervisor()
      val coordinator = FeatureTaskRuntimeWorkerCoordinator(database, supervisor, testHarnessClock, execution.admission)
      var launches = 0
      listOf(
        identity.copy(repositoryIdentity = "repo-root-realpath-v1:/tmp/other-repository"),
        identity.copy(routeScope = FeatureTaskRouteScope.GOAL_CHILD),
        identity.copy(normalizedIssueKey = "SKILL-999"),
        identity.copy(governedSpecPath = ".feature-specs/SKILL-384/other.md"),
      ).forEach { expected ->
        assertFailsWith<InvalidFeatureTaskExecutionIdentitySchemaError> {
          coordinator.runOwned(original.workflowId, execution.inputs, expected) { launches++ }
        }
      }
      assertEquals(0, launches)
      assertEquals(0, supervisor.terminations)
      assertEquals(0, supervisor.heartbeats)
      database.read {
        assertEquals(before, it.workflowStates.getFeatureTaskWorkflow(original.workflowId))
        assertEquals(original, it.workflowStates.getFeatureTaskRuntimeWorkerOwnership(original.workflowId))
      }
    }
  }

  @Test
  fun `takeover rechecks descriptor after process inspection and again before transfer`() {
    listOf(false, true).forEach { afterReservation ->
      withOwnedWorkflow { database, original ->
        val changed =
          execution.validator.read(
            execution.codec.encodeExecution(execution.plan, execution.inputs.copy(packSlug = "different-pack")),
            "changed pack",
          )
        val mutate = { replaceWorkerDescriptor(database, original.workflowId, changed) }
        val supervisor =
          TakeoverSupervisor(
            onInspect = if (afterReservation) ({}) else mutate,
            onTerminate = if (afterReservation) mutate else ({}),
          )
        val before = database.read { assertNotNull(it.workflowStates.getFeatureTaskWorkflow(original.workflowId)) }
        val coordinator =
          FeatureTaskRuntimeWorkerCoordinator(database, supervisor, testHarnessClock, execution.admission)
        var launches = 0

        assertFailsWith<IncompatibleFeatureTaskRuntimeExecutionPlanError> {
          coordinator.runOwned(
            original.workflowId,
            execution.inputs,
            execution.identity(original.workflowId),
          ) { launches++ }
        }

        assertEquals(0, launches)
        assertEquals(0, supervisor.heartbeats)
        assertEquals(if (afterReservation) 1 else 0, supervisor.terminations)
        database.read { unit ->
          val row = assertNotNull(unit.workflowStates.getFeatureTaskWorkflow(original.workflowId))
          assertEquals(before.copy(artifactsJson = row.artifactsJson), row)
          val originalArtifacts = before.toSnapshot().artifacts.toMutableMap()
          DurableWorkflowArtifactFamily.FEATURE_TASK_RUNTIME_EXECUTION_PLAN.putInto(originalArtifacts, changed)
          assertEquals(originalArtifacts, row.toSnapshot().artifacts.toMap())
          assertEquals(
            original.copy(
              leaseState =
                if (afterReservation) {
                  FeatureTaskRuntimeWorkerLeaseState.TAKEOVER_RESERVED
                } else {
                  FeatureTaskRuntimeWorkerLeaseState.ACTIVE
                },
            ),
            unit.workflowStates.getFeatureTaskRuntimeWorkerOwnership(original.workflowId),
          )
        }
      }
    }
  }

  @Test
  fun `terminal transition refuses reservation and transfer without changing workflow or lease evidence`() {
    WorkflowStatus.terminalStatuses.forEach { status ->
      listOf(false, true).forEach { reserved ->
        withOwnedWorkflow { database, original ->
          database.transaction { unit ->
            if (reserved) {
              assertTrue(
                unit.workflowStates.reserveFeatureTaskRuntimeWorkerTakeover(
                  original.workflowId,
                  original.ownerToken,
                  original.generation,
                ),
              )
            }
            val row = assertNotNull(unit.workflowStates.getFeatureTaskWorkflow(original.workflowId))
            unit.workflowStates.saveFeatureTaskWorkflow(
              row.copy(workflowStatus = status.wireValue, finishedAt = testHarnessClock.instant().toString()),
              FeatureTaskWorkflowMode.RUNTIME,
            )
          }
          val before = database.read { it.workflowStates.getFeatureTaskWorkflow(original.workflowId) }
          val lease = database.read { it.workflowStates.getFeatureTaskRuntimeWorkerOwnership(original.workflowId) }
          database.transaction { unit ->
            assertFalse(
              unit.workflowStates.reserveFeatureTaskRuntimeWorkerTakeover(
                original.workflowId,
                original.ownerToken,
                original.generation,
              ),
            )
            assertFalse(
              unit.workflowStates.transferFeatureTaskRuntimeWorker(
                original.copy(ownerToken = "replacement-owner-token", generation = original.generation + 1),
                original.ownerToken,
                original.generation,
              ),
            )
          }
          database.read { unit ->
            assertEquals(before, unit.workflowStates.getFeatureTaskWorkflow(original.workflowId))
            assertEquals(lease, unit.workflowStates.getFeatureTaskRuntimeWorkerOwnership(original.workflowId))
          }
        }
      }
    }
  }
}

private fun withOwnedWorkflow(block: (DatabaseSessionFactory, FeatureTaskRuntimeWorkerOwnership) -> Unit) {
  val home = Files.createTempDirectory("worker-takeover-fence")
  try {
    val database = phaseRunDatabase(home, testHarnessClock)
    val recorder =
      featureTaskRuntimePhaseRecorder(
        database,
        NoopWorkflowSnapshotValidator,
        AcceptingFeatureTaskRuntimeWireArtifactValidator,
        AcceptingFeatureTaskRuntimeWireArtifactValidator,
        testHarnessClock,
        NoopRuntimeDiagnostics,
      )
    val original = ownership().copy(workflowId = "wftr-takeover-fence")
    database.transaction { execution.seed(it.workflowStates, original.workflowId) }
    assertTrue(
      recorder.recordPhaseState(
        FeatureTaskRuntimePhaseStateRequest(
          workflowId = original.workflowId,
          phaseId = "commit_push",
          status = "running",
          attemptCount = 2,
          resolvedAgentId = "original-agent",
          finished = false,
          outputArtifact = "retained-uncertain-finalization-evidence",
        ),
      ),
    )
    val finalization = assertNotNull(recorder.loadPhaseRecords(original.workflowId)?.get("commit_push"))
    assertEquals(2, finalization.attemptCount)
    assertEquals("original-agent", finalization.resolvedAgentId)
    assertEquals("retained-uncertain-finalization-evidence", finalization.outputArtifact)
    database.selfManagedWrite { unit ->
      val row = assertNotNull(unit.workflowStates.getFeatureTaskWorkflow(original.workflowId))
      assertTrue(unit.workflowStates.acquireFeatureTaskRuntimeWorker(original, row.updatedAt))
    }
    block(database, original)
  } finally {
    home.toFile().deleteRecursively()
  }
}

private class TakeoverSupervisor(
  private val onInspect: () -> Unit = {},
  private val onTerminate: () -> Unit = {},
) : FeatureTaskRuntimeWorkerSupervisor {
  private var inspected = false
  private var stopped = false
  var terminations = 0
    private set
  var heartbeats = 0
    private set

  override fun currentProcess() = FeatureTaskRuntimeProcessIdentity("host", "boot", 200, "birth-200")

  override fun inspect(ownership: FeatureTaskRuntimeWorkerOwnership): FeatureTaskRuntimeProcessInspection {
    if (!inspected) {
      inspected = true
      onInspect()
    }
    return if (stopped) {
      FeatureTaskRuntimeProcessInspection.NotRunning
    } else {
      FeatureTaskRuntimeProcessInspection.ExactLive
    }
  }

  override fun terminateGracefully(ownership: FeatureTaskRuntimeWorkerOwnership): Boolean {
    onTerminate()
    terminations++
    stopped = true
    return true
  }

  override fun terminateForcibly(ownership: FeatureTaskRuntimeWorkerOwnership): Boolean {
    terminations++
    stopped = true
    return true
  }

  override fun awaitExit(
    ownership: FeatureTaskRuntimeWorkerOwnership,
    timeout: Duration,
  ) = Unit

  override fun pause(durationMillis: Long) = Unit

  override fun startHeartbeat(
    plan: FeatureTaskRuntimeHeartbeatPlan,
    heartbeat: () -> FeatureTaskRuntimeHeartbeatTick,
  ): FeatureTaskRuntimeHeartbeat {
    heartbeats++
    return object : FeatureTaskRuntimeHeartbeat {
      override fun stop() = Unit

      override fun fencingLostReason(): String? = null
    }
  }
}

private val execution = ExecutionPlanAdmissionFixture()

private fun replaceWorkerDescriptor(
  database: DatabaseSessionFactory,
  workflowId: String,
  descriptor: Any?,
) {
  val artifacts =
    database.read {
      assertNotNull(it.workflowStates.getFeatureTaskWorkflow(workflowId)).toSnapshot().artifacts.toMutableMap()
    }
  val family = DurableWorkflowArtifactFamily.FEATURE_TASK_RUNTIME_EXECUTION_PLAN
  if (descriptor == null) family.removeFrom(artifacts) else family.putInto(artifacts, descriptor)
  DriverManager.getConnection("jdbc:sqlite:${database.resolveDbPath()}").use { connection ->
    connection.prepareStatement(
      "UPDATE feature_task_workflows SET artifacts_json = ? WHERE workflow_id = ?",
    ).use { statement ->
      statement.setString(1, JsonCodec.mapToJsonString(artifacts))
      statement.setString(2, workflowId)
      assertEquals(1, statement.executeUpdate())
    }
  }
}
