package skillbill.engine.goalrunner.persist

import skillbill.application.FakeDatabaseSessionFactory
import skillbill.application.InMemoryWorkflowStates
import skillbill.contracts.workflow.goal.GoalBlockArtifactKeys
import skillbill.engine.diagnostics.block.BlockIncidentAliases
import skillbill.engine.diagnostics.block.BlockIncidentIdentity
import skillbill.engine.diagnostics.block.BlockIncidentObservation
import skillbill.engine.diagnostics.block.FailurePhaseEvidence
import skillbill.engine.diagnostics.block.boundTo
import skillbill.engine.diagnostics.block.featureTaskPhaseAliasForTest
import skillbill.engine.diagnostics.block.observeBlockIncidentForTest
import skillbill.engine.diagnostics.block.persistAcceptedBlockIdentityForTest
import skillbill.engine.featuretask.incident.BlockIncidentCapture
import skillbill.engine.featuretask.incident.BlockIncidentCaptureOutcome
import skillbill.engine.goalrunner.model.GoalRunnerBlockRequest
import skillbill.engine.goalrunner.model.GoalRunnerBlockWriteResult
import skillbill.goalrunner.model.GoalRunnerAcceptedBlockReference
import skillbill.goalrunner.model.GoalRunnerControlState
import skillbill.goalrunner.model.GoalRunnerExecutionLease
import skillbill.goalrunner.model.GoalRunnerStopReason
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
  fun `historical replay preserves known and unknown failure phase independently of the resume phase`() {
    listOf("audit_plan_fix", null).forEachIndexed { index, failurePhase ->
      val id = "wftr-replay-$index"
      saveCompletedSimplify(id)
      val original =
        assertNotNull(
          writes.markBlocked(
            GoalRunnerBlockRequest(
              id,
              "child exited",
              "audit",
              stopReason = GoalRunnerStopReason.NO_TERMINAL_STORE_OUTCOME,
              failurePhaseEvidence = FailurePhaseEvidence(failurePhase),
            ),
            workflows,
            incidents,
          ),
        )

      val replay =
        assertNotNull(
          writes.markBlocked(
            GoalRunnerBlockRequest(
              id,
              "retained child failure",
              "audit",
              historicalBlockReference = original.acceptedBlockReference,
            ),
            workflows,
            incidents,
          ),
        )

      assertEquals(original.acceptedBlockReference.incidentToken, replay.acceptedBlockReference.incidentToken)
      assertEquals("audit", replay.stepId)
      assertEquals(failurePhase, replay.observation.phaseId)
      assertNull(replay.observation.attempt)
      val saved = assertNotNull(workflows.get(WorkflowFamily.TASK_RUNTIME, id))
      val evidence = assertNotNull(BlockIncidentIdentity.fromArtifacts(saved.artifacts)?.failurePhaseEvidence)
      assertEquals(failurePhase, evidence.phaseId)
    }
  }

  @Test
  fun `child exit retains observed failure phase separately from the resume step`() {
    listOf("simplify", "audit_plan_fix").forEach { failurePhase ->
      val id = "wftr-$failurePhase"
      saveCompletedSimplify(id)
      val result =
        assertNotNull(
          writes.markBlocked(
            GoalRunnerBlockRequest(
              id,
              "child exited",
              "audit",
              stopReason = GoalRunnerStopReason.NO_TERMINAL_STORE_OUTCOME,
              failurePhaseEvidence = FailurePhaseEvidence(failurePhase),
            ),
            workflows,
            incidents,
          ),
        )
      val saved = assertNotNull(workflows.get(WorkflowFamily.TASK_RUNTIME, id))
      assertEquals("audit", result.stepId)
      assertEquals("audit", saved.currentStepId)
      assertEquals(failurePhase, result.observation.phaseId)
      assertNull(result.observation.attempt)
      assertEquals(
        failurePhase,
        assertNotNull(BlockIncidentIdentity.fromArtifacts(saved.artifacts)).failurePhaseEvidence?.phaseId,
      )
    }
  }

  @Test
  fun `child exit without phase evidence does not attribute failure to the resume step`() {
    saveCompletedSimplify("wftr-unknown")
    val result =
      assertNotNull(
        writes.markBlocked(
          GoalRunnerBlockRequest(
            "wftr-unknown",
            "child exited",
            "audit",
            stopReason = GoalRunnerStopReason.NO_TERMINAL_STORE_OUTCOME,
            failurePhaseEvidence = FailurePhaseEvidence(null),
          ),
          workflows,
          incidents,
        ),
      )
    assertEquals("audit", result.stepId)
    assertNull(result.observation.phaseId)
    val saved = assertNotNull(workflows.get(WorkflowFamily.TASK_RUNTIME, "wftr-unknown"))
    assertNull(assertNotNull(BlockIncidentIdentity.fromArtifacts(saved.artifacts)?.failurePhaseEvidence).phaseId)
  }

  private fun saveCompletedSimplify(id: String) {
    val opened = engine.openRecord(WorkflowFamily.TASK_RUNTIME.definition, id, "session", "simplify")
    val steps =
      listOf("preplan", "plan", "plan_subtasks", "implement", "simplify").map {
        mapOf("step_id" to it, "status" to "completed", "attempt_count" to 1)
      }
    workflows.save(
      WorkflowFamily.TASK_RUNTIME,
      engine.updateRecord(
        WorkflowFamily.TASK_RUNTIME.definition,
        opened,
        WorkflowUpdateInput(
          artifactsPatch = null,
          sessionId = "session",
          workflowStatus = WorkflowStatus.RUNNING,
          currentStepId = "simplify",
          stepUpdates = WorkflowStepUpdates.from(steps),
        ),
      ),
    )
  }

  @Test
  fun `parent retains the causal phase incident after worker release and child advancement`() {
    saveRunning("wftr-child", 1)
    saveRunning("wftr-parent", 1)
    val phase = phaseBlock(1, 7)
    persistAcceptedBlockIdentityForTest(workflows, phase)
    observeBlockIncidentForTest(incidents, clock, phase)
    val childReference = acceptedReference("wftr-child")
    val parent = block("wftr-parent", causingReference = childReference)

    saveRunning("wftr-child", 2)
    val nextPhase = phaseBlock(2, 8)
    persistAcceptedBlockIdentityForTest(workflows, nextPhase)
    observeBlockIncidentForTest(incidents, clock, nextPhase)
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
    persistAcceptedBlockIdentityForTest(workflows, firstPhase)
    observeBlockIncidentForTest(incidents, clock, firstPhase)
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
    persistAcceptedBlockIdentityForTest(workflows, secondPhase)
    observeBlockIncidentForTest(incidents, clock, secondPhase)
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
  fun `parent incident keeps a child audit reference and phase`() {
    saveRunning("wftr-parent", 1, "plan_subtasks")
    val childReference =
      GoalRunnerAcceptedBlockReference(
        sourceWorkflowId = "wftr-child",
        blockedStepId = "audit",
        attempt = 1,
        executionGeneration = 2,
        aliasKind = BlockIncidentAliasKind.PHASE_TRANSITION.wireValue,
        aliasValue = "child-audit",
        incidentToken = "child-audit-token",
        childIncidentToken = null,
      )
    val retainedReference = assertNotNull(childReference.boundTo("wftr-parent", "plan_subtasks"))

    val result =
      assertNotNull(
        writes.markBlocked(
          GoalRunnerBlockRequest(
            workflowId = "wftr-parent",
            blockedReason = "child audit blocked",
            lastResumableStep = "plan_subtasks",
            causingChildWorkflowId = "wftr-child",
            causingAcceptedBlockReference = retainedReference,
          ),
          workflows,
          incidents,
        ),
      )
    val identity =
      assertNotNull(
        BlockIncidentIdentity.fromArtifacts(
          requireNotNull(workflows.get(WorkflowFamily.TASK_RUNTIME, "wftr-parent")).artifacts,
        ),
      )

    assertEquals("wftr-child", identity.childWorkflowId)
    assertEquals("child-audit-token", identity.childIncidentToken)
    assertEquals("audit", result.observation.phaseId)
  }

  @Test
  fun `a different block cause on a still-blocked parent opens its own incident`() {
    saveRunning("wftr-parent", 1)
    val validate = parentBlock("validate", "BLOCKED")
    val validateIncident = observeBlockIncidentForTest(incidents, clock, validate.observation)

    val quality = parentBlock("write_history", "WorkflowFailureCode.INVALID_GOAL_QUALITY_EXECUTION")
    val qualityIncident = observeBlockIncidentForTest(incidents, clock, quality.observation)
    val qualityReplay = parentBlock("write_history", "WorkflowFailureCode.INVALID_GOAL_QUALITY_EXECUTION")

    assertNotEquals(validateIncident.occurrence.occurrenceToken, qualityIncident.occurrence.occurrenceToken)
    assertEquals("WorkflowFailureCode.INVALID_GOAL_QUALITY_EXECUTION", qualityIncident.occurrence.failureCode)
    assertEquals(qualityIncident.occurrence.occurrenceToken, qualityReplay.acceptedBlockReference.incidentToken)
  }

  @Test
  fun `a goal-finish stage failure is recorded at that stage rather than the parent step`() {
    saveRunning("wftr-parent", 1)

    val result =
      assertNotNull(
        writes.markBlocked(
          GoalRunnerBlockRequest(
            "wftr-parent",
            "Running initial build has incomplete or conflicting accepted terminal evidence.",
            "build",
            executionGeneration = 1,
            stopReason = GoalRunnerStopReason.BLOCKED,
            failureCode = "WorkflowFailureCode.INVALID_GOAL_QUALITY_EXECUTION",
            failurePhaseEvidence = FailurePhaseEvidence("build"),
          ),
          workflows,
          incidents,
        ),
      )
    val incident = observeBlockIncidentForTest(incidents, clock, result.observation)

    assertEquals("build", result.observation.phaseId)
    assertEquals("build", incident.occurrence.phaseId)
    assertEquals(
      "build",
      BlockIncidentIdentity.fromArtifacts(
        requireNotNull(workflows.get(WorkflowFamily.TASK_RUNTIME, "wftr-parent")).artifacts,
      )?.failurePhaseEvidence?.phaseId,
    )
  }

  @Test
  fun `a later block without a recovery command clears the earlier one`() {
    saveRunning("wftr-parent", 1)
    writes.markBlocked(
      GoalRunnerBlockRequest(
        "wftr-parent",
        "wedged",
        "implement",
        recoveryCommand = "skill-bill goal reset SKILL-1 --hard --yes",
      ),
      workflows,
      incidents,
    )
    assertEquals(
      "skill-bill goal reset SKILL-1 --hard --yes",
      recordedRecoveryCommand("wftr-parent"),
    )

    parentBlock("write_history", "WorkflowFailureCode.INVALID_GOAL_FINALIZATION_HISTORY")

    assertNull(recordedRecoveryCommand("wftr-parent"))
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
    val exitIncident = observeBlockIncidentForTest(incidents, clock, exited.observation)
    val thrownIncident = observeBlockIncidentForTest(incidents, clock, thrown.observation)
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

  private fun recordedRecoveryCommand(id: String): Any? =
    requireNotNull(
      workflows.get(WorkflowFamily.TASK_RUNTIME, id),
    ).artifacts[GoalBlockArtifactKeys.BLOCKED_RECOVERY_COMMAND]

  private fun parentBlock(
    lastResumableStep: String,
    failureCode: String,
  ): GoalRunnerBlockWriteResult =
    assertNotNull(
      writes.markBlocked(
        GoalRunnerBlockRequest(
          "wftr-parent",
          "blocked at $lastResumableStep",
          lastResumableStep,
          executionGeneration = 1,
          stopReason = GoalRunnerStopReason.BLOCKED,
          failureCode = failureCode,
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
    alias = featureTaskPhaseAliasForTest("wftr-child", "implement", "worker-$generation", attempt),
    reason = "phase failed",
    generation = generation,
    exitBehavior = BlockIncidentExitBehavior.RETURNED_FAILURE,
  )

  private fun saveRunning(
    id: String,
    attempt: Int,
    stepId: String = "implement",
  ) {
    val opened = engine.openRecord(WorkflowFamily.TASK_RUNTIME.definition, id, "session", stepId)
    val record: WorkflowStateSnapshot =
      engine.updateRecord(
        WorkflowFamily.TASK_RUNTIME.definition,
        opened,
        WorkflowUpdateInput(
          artifactsPatch = null,
          sessionId = "session",
          workflowStatus = WorkflowStatus.RUNNING,
          currentStepId = stepId,
          stepUpdates =
            WorkflowStepUpdates.from(
              listOf(mapOf("step_id" to stepId, "status" to "running", "attempt_count" to attempt)),
            ),
        ),
      )
    workflows.save(WorkflowFamily.TASK_RUNTIME, record)
  }
}
