package skillbill.engine.goalrunner.persist

import skillbill.application.FakeDatabaseSessionFactory
import skillbill.application.InMemoryWorkflowStates
import skillbill.engine.diagnostics.block.BlockIncidentAliases
import skillbill.engine.diagnostics.block.BlockIncidentCapture
import skillbill.engine.diagnostics.block.BlockIncidentFeatureTaskCorrelation
import skillbill.engine.diagnostics.block.BlockIncidentObservation
import skillbill.engine.diagnostics.block.BlockIncidentService
import skillbill.engine.goalrunner.model.GoalRunnerBlockWriteResult
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
import kotlin.test.assertNotEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull

class WorkflowGoalRunnerBlockWritesTest {
  private val now = Instant.parse("2026-10-22T09:00:00Z")
  private val clock = Clock.fixed(now, ZoneOffset.UTC)
  private val engine = WorkflowEngine()
  private val workflows = InMemoryWorkflowStates()
  private val incidents = InMemoryBlockIncidentRepository(clock)
  private val writes = WorkflowGoalRunnerBlockWrites(engine, clock)

  @Test
  fun `parent retains the causal phase incident after worker release and child advancement`() {
    saveRunning("wftr-child", 1)
    saveRunning("wftr-parent", 1)
    val phase = phaseBlock(1, 7)
    BlockIncidentFeatureTaskCorrelation.persistAcceptedIdentity(workflows, phase)
    BlockIncidentService.observeBlock(incidents, clock, phase)
    val child = block("wftr-child")
    val parent = block("wftr-parent", childToken = child.observation.linkOccurrenceToken)

    saveRunning("wftr-child", 2)
    val nextPhase = phaseBlock(2, 8)
    BlockIncidentFeatureTaskCorrelation.persistAcceptedIdentity(workflows, nextPhase)
    BlockIncidentService.observeBlock(incidents, clock, nextPhase)
    val captured = BlockIncidentService.observeBlock(incidents, clock, parent.observation)

    assertEquals(BlockIncidentAliases.token(requireNotNull(phase.alias)), captured.occurrence.occurrenceToken)
    assertEquals("implement", captured.occurrence.phaseId)
    assertEquals(7, captured.occurrence.generation)
    assertEquals(BlockIncidentAliasKind.CHILD_CAUSE, parent.observation.additionalAliases.single().kind)
    assertEquals(captured.occurrence.occurrenceToken, parent.observation.additionalAliases.single().value)
  }

  @Test
  fun `stale block replay under another owner keeps its alias while a higher child attempt creates a new incident`() {
    saveRunning("wftr-child", 1)
    saveRunning("wftr-parent", 1)
    val firstPhase = phaseBlock(1, 7)
    BlockIncidentFeatureTaskCorrelation.persistAcceptedIdentity(workflows, firstPhase)
    val firstChild = block("wftr-child")
    val first = block("wftr-parent", generation = 4, childToken = firstChild.observation.linkOccurrenceToken)
    val replay = block("wftr-parent", generation = 5, childToken = firstChild.observation.linkOccurrenceToken)
    assertEquals(first.observation.alias, replay.observation.alias)
    assertEquals(first.observation.generation, replay.observation.generation)

    saveRunning("wftr-child", 2)
    BlockIncidentFeatureTaskCorrelation.persistAcceptedIdentity(workflows, phaseBlock(2, 8))
    val secondChild = block("wftr-child")
    val second = block("wftr-parent", generation = 5, childToken = secondChild.observation.linkOccurrenceToken)
    assertNotEquals(first.observation.alias, second.observation.alias)
    assertNotEquals(first.observation.linkOccurrenceToken, second.observation.linkOccurrenceToken)
    assertEquals(5, second.observation.generation)
  }

  @Test
  fun `accepted entry failures preserve typed exit and throwable facts`() {
    saveRunning("wftr-exited", 1)
    saveRunning("wftr-thrown", 1)
    val exited = assertNotNull(writes.markBlocked("wftr-exited", "exit", "implement", null, workflows, failureExitCode = 42))
    val thrown = assertNotNull(
      writes.markBlocked("wftr-thrown", "throw", "implement", null, workflows, failureThrowableType = "java.io.IOException"),
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
  fun `a replaced or expired goal owner cannot commit a block or capture an incident`() {
    saveRunning("wftr-parent", 1)
    val lease = GoalRunnerExecutionLease(3, "current", "host", "boot", 42, "birth", now, now.plusSeconds(30))
    var current = lease
    val controls = object : GoalRunnerControlRepository by EmptyGoalRunnerControlRepository {
      override fun controlState(parentWorkflowId: String) = GoalRunnerControlState(executionLease = current)
    }
    val database = database(controls)
    val capture = BlockIncidentCapture(database, clock, InMemoryBlockIncidentSpool(), NoopRuntimeDiagnostics)
    val bridge = WorkflowGoalRunnerBlockBridge(database, writes, capture, clock)
    assertNull(bridge.markBlocked("wftr-parent", "stale", "implement", null, null, 2, executionOwnerToken = "old"))
    current = lease.copy(expiresAt = now)
    assertNull(bridge.markBlocked("wftr-parent", "expired", "implement", null, null, 3, executionOwnerToken = "current"))
    assertEquals(WorkflowStatus.RUNNING, workflows.get(WorkflowFamily.TASK_RUNTIME, "wftr-parent")?.workflowStatus)
    assertNull(incidents.latestForWorkflow("wftr-parent"))
    current = lease
    val accepted = assertNotNull(
      bridge.markBlocked("wftr-parent", "current", "implement", null, null, 3, executionOwnerToken = "current"),
    )
    assertEquals(3, assertNotNull(incidents.find(assertNotNull(accepted.incidentToken))).occurrence.generation)
  }

  private fun database(controls: GoalRunnerControlRepository): DatabaseSessionFactory {
    val delegate = FakeDatabaseSessionFactory(workflows)
    return object : DatabaseSessionFactory by delegate {
      override fun <T> transaction(block: (UnitOfWork) -> T): T = delegate.transaction { unit ->
        block(object : UnitOfWork by unit {
          override val blockIncidents = incidents
          override val goalRunnerControls = controls
        })
      }
    }
  }

  private fun block(id: String, generation: Long? = null, childToken: String? = null): GoalRunnerBlockWriteResult =
    assertNotNull(
      writes.markBlocked(
        id, "blocked", "implement", null, workflows,
        causingChildWorkflowId = "wftr-child".takeIf { childToken != null },
        executionGeneration = generation,
        causingIncidentToken = childToken,
      ),
    )

  private fun phaseBlock(attempt: Int, generation: Long) = BlockIncidentObservation(
    workflowId = "wftr-child",
    phaseId = "implement",
    attempt = attempt,
    invocationId = "worker-$generation",
    alias = BlockIncidentAliases.featureTaskPhase("wftr-child", "implement", "worker-$generation", attempt),
    reason = "phase failed",
    generation = generation,
    exitBehavior = BlockIncidentExitBehavior.RETURNED_FAILURE,
  )

  private fun saveRunning(id: String, attempt: Int) {
    val opened = engine.openRecord(WorkflowFamily.TASK_RUNTIME.definition, id, "session", "implement")
    val record: WorkflowStateSnapshot = engine.updateRecord(
      WorkflowFamily.TASK_RUNTIME.definition,
      opened,
      WorkflowUpdateInput(
        artifactsPatch = null,
        sessionId = "session",
        workflowStatus = WorkflowStatus.RUNNING,
        currentStepId = "implement",
        stepUpdates = WorkflowStepUpdates.from(listOf(mapOf("step_id" to "implement", "status" to "running", "attempt_count" to attempt))),
      ),
    )
    workflows.save(WorkflowFamily.TASK_RUNTIME, record)
  }
}
