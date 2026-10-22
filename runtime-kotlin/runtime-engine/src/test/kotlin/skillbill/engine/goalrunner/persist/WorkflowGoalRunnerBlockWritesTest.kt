package skillbill.engine.goalrunner.persist

import skillbill.application.FakeDatabaseSessionFactory
import skillbill.application.InMemoryWorkflowStates
import skillbill.engine.diagnostics.block.BlockIncidentAliases
import skillbill.engine.diagnostics.block.BlockIncidentCapture
import skillbill.engine.diagnostics.block.BlockIncidentCaptureOutcome
import skillbill.engine.diagnostics.block.BlockIncidentFeatureTaskCorrelation
import skillbill.engine.diagnostics.block.BlockIncidentIdentity
import skillbill.engine.diagnostics.block.BlockIncidentObservation
import skillbill.engine.diagnostics.block.BlockIncidentService
import skillbill.engine.goalrunner.model.GoalRunnerBlockRequest
import skillbill.engine.goalrunner.model.GoalRunnerBlockWriteResult
import skillbill.goalrunner.model.GoalRunnerAcceptedBlockReference
import skillbill.goalrunner.model.GoalRunnerControlState
import skillbill.goalrunner.model.GoalRunnerExecutionLease
import skillbill.ports.blockincident.InMemoryBlockIncidentRepository
import skillbill.ports.blockincident.InMemoryBlockIncidentSpool
import skillbill.ports.db.DatabaseSessionFactory
import skillbill.ports.diagnostics.NoopRuntimeDiagnostics
import skillbill.ports.goalrunner.EmptyGoalRunnerControlRepository
import skillbill.ports.goalrunner.GoalRunnerControlRepository
import skillbill.ports.persistence.UnitOfWork
import skillbill.ports.workflow.model.WorkflowFamily
import skillbill.workflow.engine.WorkflowEngine
import skillbill.workflow.engine.model.WorkflowStateSnapshot
import skillbill.workflow.engine.model.WorkflowStepUpdates
import skillbill.workflow.engine.model.WorkflowUpdateInput
import skillbill.workflow.model.WorkflowStatus
import skillbill.workflow.taskruntime.model.blockincident.BlockIncidentAliasKind
import skillbill.workflow.taskruntime.model.blockincident.BlockIncidentExitBehavior
import skillbill.workflow.taskruntime.model.blockincident.BlockIncidentOrigin
import java.time.Clock
import java.time.Instant
import java.time.ZoneOffset
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNotEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull

class WorkflowGoalRunnerBlockWritesTest {
  private val now = Instant.parse("2026-10-22T09:00:00Z")
  private val clock = Clock.fixed(now, ZoneOffset.UTC)
  private val engine = WorkflowEngine()
  private val workflows = InMemoryWorkflowStates()
  private val incidents = InMemoryBlockIncidentRepository(clock)
  private val writes = WorkflowGoalRunnerBlockWrites(clock)

  @Test
  fun `parent retains the causal phase incident after worker release and child advancement`() {
    saveRunning("wftr-child", 1)
    saveRunning("wftr-parent", 1)
    val phase = phaseBlock(1, 7)
    BlockIncidentFeatureTaskCorrelation.persistAcceptedIdentity(workflows, phase)
    BlockIncidentService.observeBlock(incidents, clock, phase)
    val childReference = acceptedReference("wftr-child")
    val parent = block("wftr-parent", causingReference = childReference)

    saveRunning("wftr-child", 2)
    val nextPhase = phaseBlock(2, 8)
    BlockIncidentFeatureTaskCorrelation.persistAcceptedIdentity(workflows, nextPhase)
    BlockIncidentService.observeBlock(incidents, clock, nextPhase)
    val capturedToken = (capture(parent.observation) as BlockIncidentCaptureOutcome.Captured).occurrenceToken
    val captured = assertNotNull(incidents.find(capturedToken))

    assertEquals(BlockIncidentAliases.token(requireNotNull(phase.alias)), captured.occurrence.occurrenceToken)
    assertEquals("implement", captured.occurrence.phaseId)
    assertEquals(7, captured.occurrence.generation)
    assertEquals(BlockIncidentAliasKind.CHILD_CAUSE, parent.observation.additionalAliases.single().kind)
    assertEquals(captured.occurrence.occurrenceToken, parent.observation.additionalAliases.single().value)
  }

  @Test
  fun `accepted generation attempt and child identity keep goal blocks distinct across replay`() {
    saveRunning("wftr-child", 1)
    saveRunning("wftr-parent", 1)
    val firstPhase = phaseBlock(1, 7)
    BlockIncidentFeatureTaskCorrelation.persistAcceptedIdentity(workflows, firstPhase)
    BlockIncidentService.observeBlock(incidents, clock, firstPhase)
    val childReference = acceptedReference("wftr-child")
    val first = block("wftr-parent", generation = 4, causingReference = childReference)
    assertIs<BlockIncidentCaptureOutcome.Captured>(capture(first.observation))
    val replay = block("wftr-parent", generation = 4, causingReference = childReference)
    assertIs<BlockIncidentCaptureOutcome.Captured>(capture(replay.observation))
    assertEquals(first.acceptedBlockReference.incidentToken, replay.acceptedBlockReference.incidentToken)
    assertEquals(first.acceptedBlockReference.aliasValue, replay.acceptedBlockReference.aliasValue)

    saveRunning("wftr-parent", 1)
    val newGeneration = block("wftr-parent", generation = 5, causingReference = childReference)
    assertIs<BlockIncidentCaptureOutcome.Captured>(capture(newGeneration.observation))
    assertNotEquals(first.acceptedBlockReference.aliasValue, newGeneration.acceptedBlockReference.aliasValue)

    saveRunning("wftr-parent", 2)
    val newAttempt = block("wftr-parent", generation = 4, causingReference = childReference)
    assertIs<BlockIncidentCaptureOutcome.Captured>(capture(newAttempt.observation))
    assertNotEquals(first.acceptedBlockReference.aliasValue, newAttempt.acceptedBlockReference.aliasValue)

    saveRunning("wftr-child", 2)
    val secondPhase = phaseBlock(2, 8)
    BlockIncidentFeatureTaskCorrelation.persistAcceptedIdentity(workflows, secondPhase)
    BlockIncidentService.observeBlock(incidents, clock, secondPhase)
    val secondChildReference = acceptedReference("wftr-child")
    saveRunning("wftr-parent-child-cause", 1)
    val newChildCause =
      block(
        "wftr-parent-child-cause",
        generation = 4,
        causingReference = secondChildReference,
      )
    assertIs<BlockIncidentCaptureOutcome.Captured>(capture(newChildCause.observation))
    assertNotEquals(childReference.incidentToken, secondChildReference.incidentToken)
    assertEquals(secondChildReference.incidentToken, newChildCause.observation.additionalAliases.single().value)
  }

  @Test
  fun `accepted entry failures preserve typed exit and throwable facts`() {
    saveRunning("wftr-exited", 1)
    saveRunning("wftr-thrown", 1)
    val exited =
      assertNotNull(
        writes.markBlocked(
          GoalRunnerBlockRequest("wftr-exited", "exit", "implement", failureExitCode = 42),
          workflows,
          incidents,
        ),
      )
    val thrown =
      assertNotNull(
        writes.markBlocked(
          GoalRunnerBlockRequest("wftr-thrown", "throw", "implement", failureThrowableType = "java.io.IOException"),
          workflows,
          incidents,
        ),
      )
    val exitIncident = BlockIncidentService.observeBlock(incidents, clock, exited.observation)
    val thrownIncident = BlockIncidentService.observeBlock(incidents, clock, thrown.observation)
    assertEquals(WorkflowStatus.BLOCKED, workflows.get(WorkflowFamily.TASK_RUNTIME, "wftr-exited")?.workflowStatus)
    assertEquals(BlockIncidentOrigin.ENTRY_FAILURE, exitIncident.occurrence.origin)
    assertEquals(BlockIncidentExitBehavior.EXITED_WITH_CODE, exitIncident.occurrence.exitBehavior)
    assertEquals(42, exitIncident.occurrence.exitCode)
    assertEquals(BlockIncidentOrigin.ENTRY_FAILURE, thrownIncident.occurrence.origin)
    assertEquals(BlockIncidentExitBehavior.THROWN, thrownIncident.occurrence.exitBehavior)
    assertEquals("java.io.IOException", thrownIncident.occurrence.throwableType)
  }

  @Test
  fun `unresolvable child reference does not roll back the accepted goal block`() {
    saveRunning("wftr-parent", 1)
    val spool = InMemoryBlockIncidentSpool()
    val database = database(EmptyGoalRunnerControlRepository)
    val capture = BlockIncidentCapture(database, clock, spool, NoopRuntimeDiagnostics)
    val reference =
      GoalRunnerAcceptedBlockReference(
        sourceWorkflowId = "wftr-child",
        blockedStepId = "implement",
        attempt = 1,
        executionGeneration = 1,
        aliasKind = BlockIncidentAliasKind.PHASE_TRANSITION.wireValue,
        aliasValue = "missing-child-alias",
        incidentToken = "missing-child-token",
        childIncidentToken = null,
      )

    val observation =
      database.transaction { unit ->
        assertNotNull(
          writes.markBlocked(
            GoalRunnerBlockRequest(
              "wftr-parent",
              "child blocked",
              "implement",
              causingChildWorkflowId = "wftr-child",
              causingAcceptedBlockReference = reference,
            ),
            unit.workflowStates,
            unit.blockIncidents,
          ),
        ).observation
      }
    assertIs<BlockIncidentCaptureOutcome.Spooled>(capture.capture(observation))

    assertEquals(WorkflowStatus.BLOCKED, workflows.get(WorkflowFamily.TASK_RUNTIME, "wftr-parent")?.workflowStatus)
    assertEquals("missing-child-token", spool.pending(1).single().record.occurrenceToken)
  }

  @Test
  fun `a replaced or expired goal owner cannot commit a block or capture an incident`() {
    saveRunning("wftr-parent", 1)
    val lease = GoalRunnerExecutionLease(3, "current", "host", "boot", 42, "birth", now, now.plusSeconds(30))
    var current = lease
    val controls =
      object : GoalRunnerControlRepository by EmptyGoalRunnerControlRepository {
        override fun controlState(parentWorkflowId: String) = GoalRunnerControlState(executionLease = current)
      }
    val database = database(controls)
    val capture = BlockIncidentCapture(database, clock, InMemoryBlockIncidentSpool(), NoopRuntimeDiagnostics)
    val bridge = WorkflowGoalRunnerBlockBridge(database, writes, capture, clock)
    assertNull(bridge.markBlocked(ownedRequest("stale", 2, "old")))
    current = lease.copy(expiresAt = now)
    assertNull(
      bridge.markBlocked(ownedRequest("expired", 3, "current")),
    )
    assertEquals(WorkflowStatus.RUNNING, workflows.get(WorkflowFamily.TASK_RUNTIME, "wftr-parent")?.workflowStatus)
    assertNull(incidents.latestForWorkflow("wftr-parent"))
    current = lease
    val accepted =
      assertNotNull(
        bridge.markBlocked(ownedRequest("current", 3, "current")),
      )
    assertEquals(3, assertNotNull(incidents.find(assertNotNull(accepted.incidentToken))).occurrence.generation)
  }

  private fun ownedRequest(
    reason: String,
    generation: Long,
    ownerToken: String,
  ) = GoalRunnerBlockRequest(
    "wftr-parent",
    reason,
    "implement",
    executionGeneration = generation,
    executionOwnerToken = ownerToken,
  )

  private fun database(controls: GoalRunnerControlRepository): DatabaseSessionFactory {
    val delegate = FakeDatabaseSessionFactory(workflows)
    return object : DatabaseSessionFactory by delegate {
      override fun <T> transaction(block: (UnitOfWork) -> T): T =
        delegate.transaction { unit ->
          block(
            object : UnitOfWork by unit {
              override val blockIncidents = incidents
              override val goalRunnerControls = controls
            },
          )
        }
    }
  }

  private fun capture(observation: BlockIncidentObservation) =
    BlockIncidentCapture(
      database(EmptyGoalRunnerControlRepository),
      clock,
      InMemoryBlockIncidentSpool(),
      NoopRuntimeDiagnostics,
    ).capture(observation)

  private fun acceptedReference(workflowId: String) =
    assertNotNull(
      BlockIncidentIdentity.fromArtifacts(
        requireNotNull(workflows.get(WorkflowFamily.TASK_RUNTIME, workflowId)).artifacts,
      )?.acceptedReference(workflowId),
    )

  private fun block(
    id: String,
    generation: Long? = null,
    causingReference: GoalRunnerAcceptedBlockReference? = null,
  ): GoalRunnerBlockWriteResult =
    assertNotNull(
      writes.markBlocked(
        GoalRunnerBlockRequest(
          id,
          "blocked",
          "implement",
          causingChildWorkflowId = causingReference?.sourceWorkflowId,
          executionGeneration = generation,
          causingAcceptedBlockReference = causingReference,
        ),
        workflows,
        incidents,
      ),
    )

  private fun phaseBlock(
    attempt: Int,
    generation: Long,
  ) = BlockIncidentObservation(
    workflowId = "wftr-child",
    phaseId = "implement",
    attempt = attempt,
    invocationId = "worker-$generation",
    alias = BlockIncidentAliases.featureTaskPhase("wftr-child", "implement", "worker-$generation", attempt),
    reason = "phase failed",
    generation = generation,
    exitBehavior = BlockIncidentExitBehavior.RETURNED_FAILURE,
  )

  private fun saveRunning(
    id: String,
    attempt: Int,
  ) {
    val opened = engine.openRecord(WorkflowFamily.TASK_RUNTIME.definition, id, "session", "implement")
    val record: WorkflowStateSnapshot =
      engine.updateRecord(
        WorkflowFamily.TASK_RUNTIME.definition,
        opened,
        WorkflowUpdateInput(
          artifactsPatch = null,
          sessionId = "session",
          workflowStatus = WorkflowStatus.RUNNING,
          currentStepId = "implement",
          stepUpdates =
            WorkflowStepUpdates.from(
              listOf(mapOf("step_id" to "implement", "status" to "running", "attempt_count" to attempt)),
            ),
        ),
      )
    workflows.save(WorkflowFamily.TASK_RUNTIME, record)
  }
}
