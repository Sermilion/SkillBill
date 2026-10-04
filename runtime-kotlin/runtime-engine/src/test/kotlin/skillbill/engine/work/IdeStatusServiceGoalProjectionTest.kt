package skillbill.engine.work

import skillbill.engine.goalrunner.execution.core.lease
import skillbill.engine.goalrunner.manifest
import skillbill.engine.goalrunner.status.liveLease
import skillbill.engine.work.model.IdeStatusRequest
import skillbill.engine.work.model.toStatusWireMap
import skillbill.goalrunner.model.GoalPlanningStatusState
import skillbill.goalrunner.model.GoalRunnerControlState
import skillbill.goalrunner.model.GoalRunnerExecutionLease
import skillbill.ports.goalrunner.EmptyGoalRunnerControlRepository
import skillbill.ports.goalrunner.GoalRunnerControlRepository
import skillbill.ports.idestatus.model.IdeStatusLifecycleState
import skillbill.ports.idestatus.model.IdeStatusPauseReasonCode
import skillbill.ports.idestatus.model.IdeStatusWorkflowFamily
import skillbill.ports.work.model.WorkItemKind
import skillbill.workflow.model.FeatureTaskWorkflowMode
import skillbill.workflow.taskruntime.model.phase.FeatureTaskRuntimeFailureDisposition
import skillbill.workflow.taskruntime.phase.task.FeatureTaskRuntimePhaseWorkflowDefinition
import java.time.Instant
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class IdeStatusServiceGoalProjectionTest {
  @Test
  fun `running goal whose parent lease expired projects idle or active without operator pause`() {
    val fixture = gitRepoFixture("ide-status-goal-lease-expired")
    val identity = testGoalRepositoryIdentity(fixture)
    val heartbeatAt = Instant.parse("2026-08-06T11:50:00Z")
    val lease = expiredLease(heartbeatAt)
    val controls =
      object : GoalRunnerControlRepository by EmptyGoalRunnerControlRepository {
        override fun controlState(parentWorkflowId: String): GoalRunnerControlState =
          GoalRunnerControlState(
            repositoryIdentity = identity,
            executionLease = lease,
            pausedAt = "2026-08-06T11:55:00Z",
          )
      }
    val service =
      ideStatusService(
        TrackingDatabase(
          work = listOf(workItem("goal-1", WorkItemKind.FEATURE_GOAL, "running", "2026-08-06T10:00:00Z")),
          workflows = IdeStatusWorkflowStates(),
          controls = controls,
        ),
        manifestStore =
          StubGoalManifestStore(
            goalManifestState(fixture, identity, childWorkflowId = ""),
            planning = planningSnapshot(GoalPlanningStatusState.PREPLANNED),
            lease = lease,
          ),
      )

    val result =
      service.status(
        IdeStatusRequest(repoRoot = fixture.toString(), observedAt = ideStatusObservedAt),
      )

    assertTrue(
      result.snapshot.lifecycleState == IdeStatusLifecycleState.IDLE ||
        result.snapshot.lifecycleState == IdeStatusLifecycleState.ACTIVE,
    )
    assertEquals(heartbeatAt, result.snapshot.updatedAt)
    val wire = result.snapshot.toStatusWireMap()
    assertFalse(wire.containsKey("paused_at"))
    assertEquals(heartbeatAt.toString(), wire["updated_at"])
    assertFalse(result.snapshot.summary.contains("paused", ignoreCase = true))
    assertEquals("planning", result.snapshot.currentStep.id)
  }

  private fun expiredLease(heartbeatAt: Instant): GoalRunnerExecutionLease = lease(heartbeatAt)

  private fun liveLease(): GoalRunnerExecutionLease = lease(ideStatusObservedAt.minusSeconds(5))

  private fun lease(heartbeatAt: Instant): GoalRunnerExecutionLease =
    GoalRunnerExecutionLease(
      generation = 1,
      ownerToken = "owner-token",
      hostIdentity = "test-host",
      bootIdentity = "boot-id",
      pid = 4321,
      processBirthToken = "birth-token",
      heartbeatAt = heartbeatAt.toString(),
      expiresAt = heartbeatAt.plusSeconds(30).toString(),
    )

  @Test
  fun `goal with prepared planning keeps todays step and summary`() {
    val fixture = gitRepoFixture("ide-status-goal-planning-prepared")
    val identity = testGoalRepositoryIdentity(fixture)
    val service =
      ideStatusService(
        goalOnlyDatabase(),
        manifestStore =
          StubGoalManifestStore(
            goalManifestState(fixture, identity, childWorkflowId = "w-child"),
            planning = planningSnapshot(GoalPlanningStatusState.PREPARED),
          ),
      )

    val result =
      service.status(
        IdeStatusRequest(repoRoot = fixture.toString(), observedAt = ideStatusObservedAt),
      )

    assertEquals("implement", result.snapshot.currentStep.id)
    assertEquals("implement", result.snapshot.currentStep.label)
    assertEquals("Goal SKILL-148 is active on implement.", result.snapshot.summary)
    assertEquals(GoalPlanningStatusState.PREPARED, result.snapshot.planning?.state)
  }

  @Test
  fun `mid-wave goal passes every planning subtask and the single id through to the wire`() {
    val fixture = gitRepoFixture("ide-status-goal-planning-wave")
    val identity = testGoalRepositoryIdentity(fixture)
    val service =
      ideStatusService(
        goalOnlyDatabase(),
        manifestStore =
          StubGoalManifestStore(
            goalManifestState(fixture, identity, childWorkflowId = ""),
            planning = planningSnapshot(GoalPlanningStatusState.PARTIALLY_PLANNED, wave = listOf(2, 3, 4, 5, 6)),
            lease = liveLease(),
          ),
      )

    val result =
      service.status(
        IdeStatusRequest(repoRoot = fixture.toString(), observedAt = ideStatusObservedAt),
      )

    val planning = result.snapshot.toStatusWireMap()["planning"] as Map<*, *>
    assertEquals(listOf("2", "3", "4", "5", "6"), planning["planning_wave_subtask_ids"])
    assertEquals("2", planning["current_planning_subtask_id"])
    assertTrue(result.snapshot.summary.contains("5 subtasks are being planned now."), result.snapshot.summary)
  }

  @Test
  fun `goal with a null planning projection keeps todays step and summary and emits no planning`() {
    val fixture = gitRepoFixture("ide-status-goal-planning-absent")
    val identity = testGoalRepositoryIdentity(fixture)
    val service =
      ideStatusService(
        goalOnlyDatabase(),
        manifestStore =
          StubGoalManifestStore(
            goalManifestState(fixture, identity, childWorkflowId = "w-child"),
            planning = null,
          ),
      )

    val result =
      service.status(
        IdeStatusRequest(repoRoot = fixture.toString(), observedAt = ideStatusObservedAt),
      )

    assertEquals("implement", result.snapshot.currentStep.id)
    assertEquals("implement", result.snapshot.currentStep.label)
    assertEquals("Goal SKILL-148 is active on implement.", result.snapshot.summary)
    assertNull(result.snapshot.planning)
    assertFalse(result.snapshot.toStatusWireMap().containsKey("planning"))
  }

  @Test
  fun `non-goal families never carry planning`() {
    val fixture = gitRepoFixture("ide-status-planning-non-goal")
    val identity = testGoalRepositoryIdentity(fixture)
    val workflows = IdeStatusWorkflowStates()
    workflows.saveFeatureTaskWorkflow(runtimeRecord("w-active", "2026-08-06T10:00:00Z"), FeatureTaskWorkflowMode.PROSE)
    workflows.saveFeatureTaskExecutionIdentity(identityFor("w-active", identity))
    val database =
      TrackingDatabase(
        work = listOf(workItem("w-active", WorkItemKind.FEATURE_TASK_RUNTIME, "running", "2026-08-06T10:00:00Z")),
        workflows = workflows,
      )

    val result =
      ideStatusService(database).status(
        IdeStatusRequest(repoRoot = fixture.toString(), observedAt = ideStatusObservedAt),
      )

    assertEquals(IdeStatusWorkflowFamily.FEATURE_TASK_RUNTIME, result.snapshot.workflowFamily)
    assertNull(result.snapshot.planning)
    assertFalse(result.snapshot.toStatusWireMap().containsKey("planning"))
  }

  @Test
  fun `blocked goal candidate stays blocked under paused and pause_requested controls`() {
    listOf(
      GoalRunnerControlState(paused = true, pauseReason = "operator_request", pausedAt = "2026-08-02T10:00:00Z"),
      GoalRunnerControlState(pauseRequested = true),
    ).forEachIndexed { index, controlState ->
      val fixture = gitRepoFixture("ide-status-goal-blocked-pause-$index")
      val identity = testGoalRepositoryIdentity(fixture)
      val service =
        ideStatusService(
          goalOnlyDatabase(goalState = "blocked"),
          manifestStore =
            StubGoalManifestStore(
              goalManifestState(fixture, identity, childWorkflowId = "w-child")
                .copy(controlState = controlState.copy(repositoryIdentity = identity)),
            ),
        )

      val result =
        service.status(
          IdeStatusRequest(repoRoot = fixture.toString(), observedAt = ideStatusObservedAt),
        )

      assertEquals(IdeStatusLifecycleState.BLOCKED, result.snapshot.lifecycleState)
      assertEquals("Goal SKILL-148 is blocked.", result.snapshot.summary)
    }
  }

  @Test
  fun `active goal candidate projects paused once the pause is consumed`() {
    val wire =
      goalWireMapUnderControls(
        "ide-status-goal-active-pause-consumed",
        GoalRunnerControlState(paused = true, pauseReason = "operator_request", pausedAt = "2026-08-02T10:00:00Z"),
      ) { result ->
        assertEquals(IdeStatusLifecycleState.PAUSED, result.snapshot.lifecycleState)
        assertEquals("Goal SKILL-148 is paused.", result.snapshot.summary)
      }

    assertEquals("paused", wire["lifecycle_state"])
    assertEquals("2026-08-02T10:00:00Z", wire["paused_at"])
    assertEquals("operator_request", nestedWireMap(wire, "pause_reason")["code"])
    assertFalse(wire.containsKey("pause_requested"))
  }

  @Test
  fun `active goal candidate with an unconsumed pause request stays active and reports the request`() {
    val wire =
      goalWireMapUnderControls(
        "ide-status-goal-active-pause-requested",
        GoalRunnerControlState(pauseRequested = true),
      ) { result ->
        assertEquals(IdeStatusLifecycleState.ACTIVE, result.snapshot.lifecycleState)
      }

    assertEquals("active", wire["lifecycle_state"])
    assertEquals(true, wire["pause_requested"])
    assertFalse(wire.containsKey("paused_at"))
  }

  @Test
  fun `plain active goal emits neither pause signal`() {
    val wire =
      goalWireMapUnderControls(
        "ide-status-goal-active-no-pause",
        GoalRunnerControlState(),
      ) { result ->
        assertEquals(IdeStatusLifecycleState.ACTIVE, result.snapshot.lifecycleState)
      }

    assertFalse(wire.containsKey("pause_requested"))
    assertFalse(wire.containsKey("paused_at"))
  }

  @Test
  fun `a goal with no recorded execution omits the active duration rather than publishing zero`() {
    val wire =
      goalWireMapUnderControls(
        "ide-status-goal-active-duration-unrecorded",
        GoalRunnerControlState(),
      ) { result ->
        assertEquals(IdeStatusLifecycleState.ACTIVE, result.snapshot.lifecycleState)
      }

    assertFalse(wire.containsKey("active_duration_ms"))
    assertFalse(wire.containsKey("active_duration_as_of"))
  }

  @Test
  fun `a stale anchor from a dead runner is withheld while the accumulated total still ships`() {
    val wire =
      goalWireMapUnderControls(
        "ide-status-goal-active-duration-stale-anchor",
        GoalRunnerControlState(
          activeDurationMs = 90_000,
          activeDurationAsOf = "2026-08-06T09:00:00Z",
        ),
      ) { result ->
        assertEquals(IdeStatusLifecycleState.ACTIVE, result.snapshot.lifecycleState)
      }

    assertEquals(90_000L, wire["active_duration_ms"])
    assertFalse(wire.containsKey("active_duration_as_of"))
  }

  @Test
  fun `live goal snapshot includes current subtask active duration when recorded`() {
    val fixture = gitRepoFixture("ide-status-subtask-active-duration-live")
    val identity = testGoalRepositoryIdentity(fixture)
    val asOf = Instant.parse("2026-08-06T11:59:00Z")
    val lease =
      GoalRunnerExecutionLease(
        generation = 1,
        ownerToken = "owner-token",
        hostIdentity = "test-host",
        bootIdentity = "boot-id",
        pid = 4321,
        processBirthToken = "birth-token",
        heartbeatAt = asOf.toString(),
        expiresAt = ideStatusObservedAt.plusSeconds(30).toString(),
      )
    val service =
      ideStatusService(
        goalOnlyDatabase(),
        manifestStore =
          StubGoalManifestStore(
            goalManifestState(fixture, identity, childWorkflowId = "")
              .copy(
                controlState =
                  GoalRunnerControlState(
                    repositoryIdentity = identity,
                    executionLease = lease,
                    activeDurationMs = 120_000,
                    activeDurationAsOf = asOf.toString(),
                    currentSubtaskId = 2,
                    subtaskActiveDurationMs = 45_000,
                    subtaskActiveDurationAsOf = asOf.toString(),
                  ),
              ),
            lease = lease,
          ),
      )

    val result =
      service.status(
        IdeStatusRequest(repoRoot = fixture.toString(), observedAt = ideStatusObservedAt),
      )

    assertEquals(IdeStatusLifecycleState.ACTIVE, result.snapshot.lifecycleState)
    assertEquals(45_000L, result.snapshot.currentSubtask?.activeDurationMs)
    assertEquals(asOf, result.snapshot.currentSubtask?.activeDurationAsOf)
    val nested = nestedWireMap(result.snapshot.toStatusWireMap(), "current_subtask")
    assertEquals(45_000L, nested["active_duration_ms"])
    assertEquals(asOf.toString(), nested["active_duration_as_of"])
  }

  @Test
  fun `goal with no recorded subtask execution omits nested active duration`() {
    val wire =
      goalWireMapUnderControls(
        "ide-status-subtask-active-duration-unrecorded",
        GoalRunnerControlState(currentSubtaskId = 2),
      ) { result ->
        assertEquals("2", result.snapshot.currentSubtask?.id)
      }

    val nested = nestedWireMap(wire, "current_subtask")
    assertFalse(nested.containsKey("active_duration_ms"))
    assertFalse(nested.containsKey("active_duration_as_of"))
  }

  @Test
  fun `a stale subtask anchor from a dead runner is withheld while the accumulated total still ships`() {
    val wire =
      goalWireMapUnderControls(
        "ide-status-subtask-active-duration-stale-anchor",
        GoalRunnerControlState(
          currentSubtaskId = 2,
          subtaskActiveDurationMs = 50_000,
          subtaskActiveDurationAsOf = "2026-08-06T09:00:00Z",
        ),
      ) { result ->
        assertEquals("2", result.snapshot.currentSubtask?.id)
        assertEquals(50_000L, result.snapshot.currentSubtask?.activeDurationMs)
        assertNull(result.snapshot.currentSubtask?.activeDurationAsOf)
      }

    val nested = nestedWireMap(wire, "current_subtask")
    assertEquals(50_000L, nested["active_duration_ms"])
    assertFalse(nested.containsKey("active_duration_as_of"))
  }

  @Test
  fun `non-goal families never carry pause signals`() {
    val fixture = gitRepoFixture("ide-status-pause-non-goal")
    val identity = testGoalRepositoryIdentity(fixture)
    val workflows = IdeStatusWorkflowStates()
    workflows.saveFeatureTaskWorkflow(runtimeRecord("w-active", "2026-08-06T10:00:00Z"), FeatureTaskWorkflowMode.PROSE)
    workflows.saveFeatureTaskExecutionIdentity(identityFor("w-active", identity))
    val database =
      TrackingDatabase(
        work = listOf(workItem("w-active", WorkItemKind.FEATURE_TASK_RUNTIME, "running", "2026-08-06T10:00:00Z")),
        workflows = workflows,
      )

    val result =
      ideStatusService(database).status(
        IdeStatusRequest(repoRoot = fixture.toString(), observedAt = ideStatusObservedAt),
      )

    val wire = result.snapshot.toStatusWireMap()
    assertEquals(IdeStatusWorkflowFamily.FEATURE_TASK_RUNTIME, result.snapshot.workflowFamily)
    assertFalse(wire.containsKey("pause_requested"))
    assertFalse(wire.containsKey("paused_at"))
  }

  @Test
  fun `active goal with child validate blocked on operator action projects blocked lifecycle`() {
    val fixture = gitRepoFixture("ide-status-goal-validate-operator-block")
    val identity = testGoalRepositoryIdentity(fixture)
    val operatorReason = "Configure GITHUB_REGISTRY_AUTH then run npm ci:safe"
    val asOf = Instant.parse("2026-08-06T11:59:00Z")
    val lease =
      GoalRunnerExecutionLease(
        generation = 1,
        ownerToken = "owner-token",
        hostIdentity = "test-host",
        bootIdentity = "boot-id",
        pid = 4321,
        processBirthToken = "birth-token",
        heartbeatAt = asOf.toString(),
        expiresAt = ideStatusObservedAt.plusSeconds(30).toString(),
      )
    val database =
      goalWithLaunchedChildDatabase(
        identity,
        Instant.parse("2026-08-06T09:15:00Z"),
        childArtifactsJson =
          blockedQualityGateChildArtifacts(
            FeatureTaskRuntimePhaseWorkflowDefinition.PHASE_VALIDATE,
            operatorReason,
            FeatureTaskRuntimeFailureDisposition.NEEDS_USER_ACTION,
          ),
        childCurrentStep = FeatureTaskRuntimePhaseWorkflowDefinition.PHASE_VALIDATE,
      )
    val service =
      ideStatusService(
        database,
        manifestStore =
          StubGoalManifestStore(
            goalManifestState(fixture, identity, childWorkflowId = "w-child"),
            lease = lease,
          ),
      )

    val result = service.status(IdeStatusRequest(repoRoot = fixture.toString(), observedAt = ideStatusObservedAt))
    val wire = result.snapshot.toStatusWireMap()

    assertEquals(IdeStatusLifecycleState.BLOCKED, result.snapshot.lifecycleState)
    assertEquals(IdeStatusPauseReasonCode.AWAITING_OPERATOR_DECISION, result.snapshot.pauseReason?.code)
    assertEquals(operatorReason, result.snapshot.pauseReason?.label)
    assertTrue(result.snapshot.summary.contains(operatorReason))
    assertFalse(result.snapshot.summary.contains("active on validate", ignoreCase = true))
    assertEquals("blocked", wire["lifecycle_state"])
    assertTrue(wire.containsKey("pause_reason"))
  }

  @Test
  fun `active goal with disposition-less validate blocked child stays active for repair loop`() {
    val fixture = gitRepoFixture("ide-status-goal-validate-repair-loop")
    val identity = testGoalRepositoryIdentity(fixture)
    val asOf = Instant.parse("2026-08-06T11:59:00Z")
    val lease =
      GoalRunnerExecutionLease(
        generation = 1,
        ownerToken = "owner-token",
        hostIdentity = "test-host",
        bootIdentity = "boot-id",
        pid = 4321,
        processBirthToken = "birth-token",
        heartbeatAt = asOf.toString(),
        expiresAt = ideStatusObservedAt.plusSeconds(30).toString(),
      )
    val database =
      goalWithLaunchedChildDatabase(
        identity,
        Instant.parse("2026-08-06T09:15:00Z"),
        childArtifactsJson =
          blockedQualityGateChildArtifacts(
            FeatureTaskRuntimePhaseWorkflowDefinition.PHASE_VALIDATE,
            "fix loop exhausted",
          ),
        childCurrentStep = FeatureTaskRuntimePhaseWorkflowDefinition.PHASE_VALIDATE,
      )
    val service =
      ideStatusService(
        database,
        manifestStore =
          StubGoalManifestStore(
            goalManifestState(fixture, identity, childWorkflowId = "w-child"),
            lease = lease,
          ),
      )

    val result = service.status(IdeStatusRequest(repoRoot = fixture.toString(), observedAt = ideStatusObservedAt))

    assertEquals(IdeStatusLifecycleState.ACTIVE, result.snapshot.lifecycleState)
    assertNull(result.snapshot.pauseReason)
    assertEquals("Goal SKILL-148 is active on validate.", result.snapshot.summary)
    assertFalse(result.snapshot.toStatusWireMap().containsKey("pause_reason"))
  }

  @Test
  fun `WE-4364-shaped validate needs user action projects blocked wire with actionable summary`() {
    val fixture = gitRepoFixture("ide-status-goal-we-4364")
    val identity = testGoalRepositoryIdentity(fixture)
    val operatorReason = "Configure GITHUB_REGISTRY_AUTH credential before validate can pass"
    val asOf = Instant.parse("2026-08-06T11:59:00Z")
    val lease =
      GoalRunnerExecutionLease(
        generation = 1,
        ownerToken = "owner-token",
        hostIdentity = "test-host",
        bootIdentity = "boot-id",
        pid = 4321,
        processBirthToken = "birth-token",
        heartbeatAt = asOf.toString(),
        expiresAt = ideStatusObservedAt.plusSeconds(30).toString(),
      )
    val manifest =
      goalManifestState(fixture, identity, childWorkflowId = "w-child").copy(
        manifest =
          goalManifestState(fixture, identity, childWorkflowId = "w-child").manifest.copy(
            subtasks =
              goalManifestState(fixture, identity, childWorkflowId = "w-child").manifest.subtasks.map { subtask ->
                if (subtask.id == 2) {
                  subtask.copy(status = "blocked", blockedReason = operatorReason)
                } else {
                  subtask
                }
              },
          ),
      )
    val database =
      goalWithLaunchedChildDatabase(
        identity,
        Instant.parse("2026-08-06T09:15:00Z"),
        childArtifactsJson =
          blockedQualityGateChildArtifacts(
            FeatureTaskRuntimePhaseWorkflowDefinition.PHASE_VALIDATE,
            operatorReason,
            FeatureTaskRuntimeFailureDisposition.NEEDS_USER_ACTION,
          ),
        childCurrentStep = FeatureTaskRuntimePhaseWorkflowDefinition.PHASE_VALIDATE,
      )
    val service =
      ideStatusService(
        database,
        manifestStore = StubGoalManifestStore(manifest, lease = lease),
      )

    val result = service.status(IdeStatusRequest(repoRoot = fixture.toString(), observedAt = ideStatusObservedAt))
    val wire = result.snapshot.toStatusWireMap()
    val pauseReason = nestedWireMap(wire, "pause_reason")

    assertEquals(IdeStatusLifecycleState.BLOCKED, result.snapshot.lifecycleState)
    assertEquals("blocked", wire["lifecycle_state"])
    assertEquals("awaiting_operator_decision", pauseReason["code"])
    assertEquals(operatorReason, pauseReason["label"])
    assertTrue((wire["summary"] as String).contains(operatorReason))
    assertFalse((wire["summary"] as String).contains("active on validate", ignoreCase = true))
  }
}
