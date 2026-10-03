package skillbill.engine.featuretask.lifecycle.core

import skillbill.application.testHarnessClock
import skillbill.contracts.JsonCodec
import skillbill.contracts.workflow.featuretask.FEATURE_TASK_RUNTIME_CHECKPOINT_IDENTITY_CONTRACT_VERSION
import skillbill.engine.featuretask.lifecycle.execution.ExecutionPlanAdmissionFixture
import skillbill.engine.featuretask.runner.InMemoryRuntimeWorkflowRepository
import skillbill.engine.featuretask.runner.RuntimeFakeDatabaseSessionFactory
import skillbill.engine.featuretask.runner.WORKFLOW_ID
import skillbill.infrastructure.sqlite.sqliteSessionFactoryForTests
import skillbill.ports.db.DatabaseSessionFactory
import skillbill.ports.diagnostics.NoopRuntimeDiagnostics
import skillbill.ports.featuretask.model.FeatureTaskRuntimeWorkerLeaseState
import skillbill.ports.featuretask.model.FeatureTaskRuntimeWorkerOwnership
import skillbill.ports.persistence.UnitOfWork
import skillbill.ports.taskruntime.FeatureTaskRuntimeWorkerSupervisor
import skillbill.ports.taskruntime.NoopFeatureTaskRuntimeHeartbeat
import skillbill.ports.taskruntime.model.FeatureTaskRuntimeHeartbeatPlan
import skillbill.ports.taskruntime.model.FeatureTaskRuntimeHeartbeatTick
import skillbill.ports.taskruntime.model.FeatureTaskRuntimeProcessIdentity
import skillbill.ports.taskruntime.model.FeatureTaskRuntimeProcessInspection
import skillbill.ports.taskruntime.model.isConfirmedDead
import skillbill.ports.workflow.model.WorkflowFamily
import skillbill.ports.workflow.model.WorkflowStateRecord
import skillbill.ports.workflow.model.toSnapshot
import skillbill.ports.workflow.toRecord
import skillbill.workflow.engine.WorkflowEngine
import skillbill.workflow.engine.model.DurableWorkflowArtifactFamily
import skillbill.workflow.model.FeatureTaskExecutionIdentityPolicy
import skillbill.workflow.model.FeatureTaskWorkflowMode.RUNTIME
import skillbill.workflow.model.WorkflowStatus
import skillbill.workflow.model.WorkflowStepStatus
import skillbill.workflow.taskruntime.artifact.asWorkflowArtifactEntry
import skillbill.workflow.taskruntime.model.persistence.featureTaskRuntimeCheckpointRefName
import skillbill.workflow.taskruntime.model.phase.FeatureTaskRuntimePhaseRecord
import java.nio.file.Files
import java.nio.file.Path
import java.time.Duration
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import skillbill.contracts.workflow.identity.task.FeatureTaskRuntimeExecutionPlanKeys as Keys

class FeatureTaskRuntimeCrashReconcilerTest {
  private val execution = ExecutionPlanAdmissionFixture()

  @Test
  fun `only NotRunning is confirmed dead while ExactLive and ambiguous evidence stay conservative`() {
    assertTrue(FeatureTaskRuntimeProcessInspection.NotRunning.isConfirmedDead())
    assertFalse(FeatureTaskRuntimeProcessInspection.ExactLive.isConfirmedDead())
    assertFalse(FeatureTaskRuntimeProcessInspection.OwnershipMismatch("pid reuse").isConfirmedDead())
    assertFalse(FeatureTaskRuntimeProcessInspection.Unsupported("no probe").isConfirmedDead())
  }

  @Test
  fun `zero candidates is a no-op`() {
    val reconciler =
      FeatureTaskRuntimeCrashReconciler(
        RuntimeFakeDatabaseSessionFactory(InMemoryRuntimeWorkflowRepository()),
        inspectionSupervisor(FeatureTaskRuntimeProcessInspection.NotRunning),
        NoopRuntimeDiagnostics,
        testHarnessClock,
        execution.compatibility,
        execution.recoveryResolver(),
      )

    val result = reconciler.reconcile()

    assertEquals(0, result.reconciledCount)
    assertTrue(result.reasonClassCounts.isEmpty())
  }

  @Test
  fun `expired-lease crash recovery admits matching semantics and repeats as a no-op`() {
    val repository = crashCandidateRepository(execution)
    val reconciler =
      FeatureTaskRuntimeCrashReconciler(
        RuntimeFakeDatabaseSessionFactory(repository),
        inspectionSupervisor(FeatureTaskRuntimeProcessInspection.NotRunning),
        NoopRuntimeDiagnostics,
        testHarnessClock,
        execution.compatibility,
        execution.recoveryResolver(),
      )

    val before = requireNotNull(repository.getFeatureTaskWorkflow(WORKFLOW_ID))
    val first = reconciler.reconcile()
    val after = requireNotNull(repository.getFeatureTaskWorkflow(WORKFLOW_ID))
    val second = reconciler.reconcile()

    assertEquals(1, first.reconciledCount)
    assertEquals(mapOf("lease_expired" to 1), first.reasonClassCounts)
    assertEquals(0, second.reconciledCount)
    assertEquals("pending", after.workflowStatus)
    assertEquals(before.stepsJson, after.stepsJson)
    assertEquals(before.artifactsJson, after.artifactsJson)
    assertEquals(before.issueKey, after.issueKey)
    assertEquals(null, repository.getFeatureTaskRuntimeWorkerOwnership(WORKFLOW_ID))
  }

  @Test
  fun `crash recovery resolves each candidate repository and recorded timeout before mutation`() {
    val root = Files.createTempDirectory("skillbill-crash-reconcile-repositories")
    val database =
      sqliteSessionFactoryForTests(
        userHome = root,
        dbPathOverride = root.resolve("runtime.db").toString(),
        environment = emptyMap(),
      )
    val rootA = Path.of("/tmp/crash-repo-a")
    val rootB = Path.of("/tmp/crash-repo-b")
    val rootC = Path.of("/tmp/crash-repo-c")
    val idA = "wftr-crash-repo-a"
    val idB = "wftr-crash-repo-b"
    val idC = "wftr-crash-repo-c"
    try {
      listOf(
        Triple(idA, rootA, "wrapper-a" to 45_000L),
        Triple(idB, rootB, "wrapper-b" to null),
        Triple(idC, rootC, "wrapper-a" to null),
      ).forEach { (workflowId, repositoryRoot, policy) ->
        seedRepositoryCandidate(database, workflowId, repositoryRoot, policy)
      }
      val beforeA = database.read { requireNotNull(it.workflowStates.getFeatureTaskWorkflowAsMode(idA, RUNTIME)) }
      val beforeB = database.read { requireNotNull(it.workflowStates.getFeatureTaskWorkflowAsMode(idB, RUNTIME)) }
      val beforeC = database.read { requireNotNull(it.workflowStates.getFeatureTaskWorkflowAsMode(idC, RUNTIME)) }
      val identityA = database.read { requireNotNull(it.workflowStates.getFeatureTaskExecutionIdentity(idA)) }
      val identityB = database.read { requireNotNull(it.workflowStates.getFeatureTaskExecutionIdentity(idB)) }
      val leaseC = database.read { requireNotNull(it.workflowStates.getFeatureTaskRuntimeWorkerOwnership(idC)) }
      val resolvedRoots = mutableListOf<Path>()
      val wrappers = mapOf(rootA to "wrapper-a", rootB to "wrapper-b", rootC to "wrapper-c")
      val reconciler =
        FeatureTaskRuntimeCrashReconciler(
          database,
          inspectionSupervisor(FeatureTaskRuntimeProcessInspection.NotRunning),
          NoopRuntimeDiagnostics,
          testHarnessClock,
          execution.compatibility,
          execution.recoveryResolver(wrappers::get) { candidateRoot -> resolvedRoots.add(candidateRoot) },
        )

      val result = reconciler.reconcile()

      assertEquals(2, result.reconciledCount)
      assertEquals(listOf(rootA, rootB, rootC), resolvedRoots)
      val afterA = database.read { requireNotNull(it.workflowStates.getFeatureTaskWorkflowAsMode(idA, RUNTIME)) }
      val afterB = database.read { requireNotNull(it.workflowStates.getFeatureTaskWorkflowAsMode(idB, RUNTIME)) }
      assertEquals("pending", afterA.workflowStatus)
      assertEquals(beforeA.stepsJson, afterA.stepsJson)
      assertEquals(beforeA.artifactsJson, afterA.artifactsJson)
      assertEquals("pending", afterB.workflowStatus)
      assertEquals(beforeB.stepsJson, afterB.stepsJson)
      assertEquals(beforeB.artifactsJson, afterB.artifactsJson)
      assertEquals(identityA, database.read { it.workflowStates.getFeatureTaskExecutionIdentity(idA) })
      assertEquals(identityB, database.read { it.workflowStates.getFeatureTaskExecutionIdentity(idB) })
      assertEquals(null, database.read { it.workflowStates.getFeatureTaskRuntimeWorkerOwnership(idA) })
      assertEquals(null, database.read { it.workflowStates.getFeatureTaskRuntimeWorkerOwnership(idB) })
      assertEquals(beforeC, database.read { it.workflowStates.getFeatureTaskWorkflowAsMode(idC, RUNTIME) })
      assertEquals(leaseC, database.read { it.workflowStates.getFeatureTaskRuntimeWorkerOwnership(idC) })
      assertEquals(0, execution.launches)
    } finally {
      root.toFile().deleteRecursively()
    }
  }

  @Test
  fun `a live process is never reconciled`() {
    val repository = crashCandidateRepository(execution)
    val reconciler =
      FeatureTaskRuntimeCrashReconciler(
        RuntimeFakeDatabaseSessionFactory(repository),
        inspectionSupervisor(FeatureTaskRuntimeProcessInspection.ExactLive),
        NoopRuntimeDiagnostics,
        testHarnessClock,
        execution.compatibility,
        execution.recoveryResolver(),
      )

    val result = reconciler.reconcile()

    assertEquals(0, result.reconciledCount)
    assertEquals("running", repository.getFeatureTaskWorkflowAsMode(WORKFLOW_ID, RUNTIME)?.workflowStatus)
  }

  @Test
  fun `ambiguous liveness evidence never reconciles`() {
    listOf(
      FeatureTaskRuntimeProcessInspection.OwnershipMismatch("pid reuse"),
      FeatureTaskRuntimeProcessInspection.Unsupported("no probe"),
    ).forEach { inspection ->
      val repository = crashCandidateRepository(execution)
      val reconciler =
        FeatureTaskRuntimeCrashReconciler(
          RuntimeFakeDatabaseSessionFactory(repository),
          inspectionSupervisor(inspection),
          NoopRuntimeDiagnostics,
          testHarnessClock,
          execution.compatibility,
          execution.recoveryResolver(),
        )

      assertEquals(0, reconciler.reconcile().reconciledCount)
      assertEquals("running", repository.getFeatureTaskWorkflowAsMode(WORKFLOW_ID, RUNTIME)?.workflowStatus)
    }
  }

  @Test
  fun `crash recovery refuses incompatible or missing identity before mutation`() {
    val repository = crashCandidateRepository(execution)
    val incompatibleId = "wftr-incompatible-crash"
    val incompatibleDescriptor = incompatibleStrategyDescriptor()
    val incompatibleRow =
      requireNotNull(repository.getFeatureTaskWorkflow(WORKFLOW_ID)).copy(
        workflowId = incompatibleId,
        artifactsJson =
          JsonCodec.mapToJsonString(
            mapOf(DurableWorkflowArtifactFamily.FEATURE_TASK_RUNTIME_EXECUTION_PLAN.entry(incompatibleDescriptor)),
          ),
      )
    repository.saveFeatureTaskWorkflow(incompatibleRow, RUNTIME)
    repository.saveFeatureTaskExecutionIdentity(execution.identity(incompatibleId))
    val incompatibleLease =
      requireNotNull(repository.getFeatureTaskRuntimeWorkerOwnership(WORKFLOW_ID)).copy(
        workflowId = incompatibleId,
        ownerToken = "incompatible-owner-token",
      )
    repository.seedWorkerOwnership(incompatibleLease)
    val identitylessId = "wftr-identityless-crash"
    repository.saveFeatureTaskWorkflow(
      incompatibleRow.copy(workflowId = identitylessId, artifactsJson = "{}"),
      RUNTIME,
    )
    repository.seedWorkerOwnership(
      incompatibleLease.copy(workflowId = identitylessId, ownerToken = "identityless-owner-token"),
    )
    val changedGateId = "wftr-changed-gate-crash"
    val changedGateDescriptor = changedGateDescriptor()
    repository.saveFeatureTaskWorkflow(
      requireNotNull(repository.getFeatureTaskWorkflow(WORKFLOW_ID)).copy(
        workflowId = changedGateId,
        artifactsJson =
          JsonCodec.mapToJsonString(
            mapOf(DurableWorkflowArtifactFamily.FEATURE_TASK_RUNTIME_EXECUTION_PLAN.entry(changedGateDescriptor)),
          ),
      ),
      RUNTIME,
    )
    repository.saveFeatureTaskExecutionIdentity(execution.identity(changedGateId))
    val changedGateLease = incompatibleLease.copy(workflowId = changedGateId, ownerToken = "changed-gate-owner-token")
    repository.seedWorkerOwnership(changedGateLease)
    val incompatibleBefore = requireNotNull(repository.getFeatureTaskWorkflow(incompatibleId))
    val identitylessBefore = requireNotNull(repository.getFeatureTaskWorkflow(identitylessId))
    val identitylessLease = requireNotNull(repository.getFeatureTaskRuntimeWorkerOwnership(identitylessId))
    val changedGateBefore = requireNotNull(repository.getFeatureTaskWorkflow(changedGateId))

    val reconciler =
      FeatureTaskRuntimeCrashReconciler(
        RuntimeFakeDatabaseSessionFactory(repository),
        inspectionSupervisor(FeatureTaskRuntimeProcessInspection.NotRunning),
        NoopRuntimeDiagnostics,
        testHarnessClock,
        execution.compatibility,
        execution.recoveryResolver(),
      )

    val result = reconciler.reconcile()

    assertEquals(1, result.reconciledCount)
    assertEquals("pending", repository.getFeatureTaskWorkflowAsMode(WORKFLOW_ID, RUNTIME)?.workflowStatus)
    assertEquals(null, repository.getFeatureTaskRuntimeWorkerOwnership(WORKFLOW_ID))
    assertEquals(incompatibleBefore, repository.getFeatureTaskWorkflow(incompatibleId))
    assertEquals(incompatibleLease, repository.getFeatureTaskRuntimeWorkerOwnership(incompatibleId))
    assertEquals(identitylessBefore, repository.getFeatureTaskWorkflow(identitylessId))
    assertEquals(identitylessLease, repository.getFeatureTaskRuntimeWorkerOwnership(identitylessId))
    assertEquals(changedGateBefore, repository.getFeatureTaskWorkflow(changedGateId))
    assertEquals(changedGateLease, repository.getFeatureTaskRuntimeWorkerOwnership(changedGateId))
  }

  @Test
  fun `matching crash recovery crosses sqlite boundary and preserves durable row evidence`() {
    val root = Files.createTempDirectory("skillbill-crash-reconcile-admission")
    val database =
      sqliteSessionFactoryForTests(
        userHome = root,
        dbPathOverride = root.resolve("runtime.db").toString(),
        environment = emptyMap(),
      )
    try {
      database.transaction {
        execution.seed(
          it.workflowStates,
          WORKFLOW_ID,
          descriptor = execution.descriptor(execution.inputsFor(null, 45_000)),
        )
      }
      seedCrashPreservationRow(database)
      val before =
        database.read {
          requireNotNull(
            it.workflowStates.getFeatureTaskWorkflowAsMode(WORKFLOW_ID, RUNTIME),
          )
        }
      val reconciler =
        FeatureTaskRuntimeCrashReconciler(
          database,
          inspectionSupervisor(FeatureTaskRuntimeProcessInspection.NotRunning),
          NoopRuntimeDiagnostics,
          testHarnessClock,
          execution.compatibility,
          execution.recoveryResolver(),
        )

      val first = reconciler.reconcile()
      val after = database.read { requireNotNull(it.workflowStates.getFeatureTaskWorkflowAsMode(WORKFLOW_ID, RUNTIME)) }
      val second = reconciler.reconcile()

      assertEquals(1, first.reconciledCount)
      assertEquals(0, second.reconciledCount)
      assertEquals("pending", after.workflowStatus)
      assertEquals("commit_push", after.currentStepId)
      assertEquals(before.stepsJson, after.stepsJson)
      assertEquals(before.artifactsJson, after.artifactsJson)
      assertEquals(before.issueKey, after.issueKey)
      assertTrue(after.artifactsJson.contains("retained-preplan-output"))
      assertTrue(after.artifactsJson.contains("retained-commit-push-evidence"))
      assertTrue(after.artifactsJson.contains(featureTaskRuntimeCheckpointRefName("SKILL-384", "2", 1)))
      assertEquals(null, database.read { it.workflowStates.getFeatureTaskRuntimeWorkerOwnership(WORKFLOW_ID) })
      assertEquals(0, execution.launches)
    } finally {
      root.toFile().deleteRecursively()
    }
  }

  @Test
  fun `crash recovery skips a candidate whose owner changes before admission`() {
    val repository = crashCandidateRepository(execution)
    val delegate = RuntimeFakeDatabaseSessionFactory(repository)
    val replacement =
      requireNotNull(repository.getFeatureTaskRuntimeWorkerOwnership(WORKFLOW_ID)).copy(
        ownerToken = "replacement-owner-token",
        generation = 2,
      )
    val racingDatabase =
      object : DatabaseSessionFactory by delegate {
        var replaced = false

        override fun <T> read(block: (UnitOfWork) -> T): T {
          val result = delegate.read(block)
          if (!replaced) {
            replaced = true
            repository.seedWorkerOwnership(replacement)
          }
          return result
        }
      }
    val reconciler =
      FeatureTaskRuntimeCrashReconciler(
        racingDatabase,
        inspectionSupervisor(FeatureTaskRuntimeProcessInspection.NotRunning),
        NoopRuntimeDiagnostics,
        testHarnessClock,
        execution.compatibility,
        execution.recoveryResolver(),
      )

    val result = reconciler.reconcile()

    assertEquals(0, result.reconciledCount)
    assertEquals("running", repository.getFeatureTaskWorkflowAsMode(WORKFLOW_ID, RUNTIME)?.workflowStatus)
    assertEquals(replacement, repository.getFeatureTaskRuntimeWorkerOwnership(WORKFLOW_ID))
  }

  @Test
  fun `an unexpected fault is counted under a distinct reason class and not as a reconciliation`() {
    val repository = crashCandidateRepository(execution)
    val faultingSupervisor =
      object : FeatureTaskRuntimeWorkerSupervisor {
        override fun currentProcess() = FeatureTaskRuntimeProcessIdentity("h", "b", 1, "birth")

        override fun inspect(ownership: FeatureTaskRuntimeWorkerOwnership): FeatureTaskRuntimeProcessInspection =
          error("probe blew up")

        override fun awaitExit(
          ownership: FeatureTaskRuntimeWorkerOwnership,
          timeout: Duration,
        ) = Unit

        override fun terminateGracefully(ownership: FeatureTaskRuntimeWorkerOwnership) = true

        override fun terminateForcibly(ownership: FeatureTaskRuntimeWorkerOwnership) = true

        override fun startHeartbeat(
          plan: FeatureTaskRuntimeHeartbeatPlan,
          heartbeat: () -> FeatureTaskRuntimeHeartbeatTick,
        ) = NoopFeatureTaskRuntimeHeartbeat

        override fun pause(durationMillis: Long) = Unit
      }
    val reconciler =
      FeatureTaskRuntimeCrashReconciler(
        RuntimeFakeDatabaseSessionFactory(repository),
        faultingSupervisor,
        NoopRuntimeDiagnostics,
        testHarnessClock,
        execution.compatibility,
        execution.recoveryResolver(),
      )

    val result = reconciler.reconcile()

    assertEquals(0, result.reconciledCount)
    assertEquals(mapOf("reconcile_fault" to 1), result.reasonClassCounts)
    assertEquals("running", repository.getFeatureTaskWorkflowAsMode(WORKFLOW_ID, RUNTIME)?.workflowStatus)
  }

  private fun crashCandidateRepository(execution: ExecutionPlanAdmissionFixture): InMemoryRuntimeWorkflowRepository =
    InMemoryRuntimeWorkflowRepository().apply {
      execution.seed(this, WORKFLOW_ID, descriptor = execution.descriptor(execution.inputsFor(null, null)))
      val row = requireNotNull(getFeatureTaskWorkflowAsMode(WORKFLOW_ID, RUNTIME))
      saveFeatureTaskWorkflow(
        row.copy(workflowStatus = WorkflowStatus.RUNNING.wireValue, currentStepId = "implement"),
        RUNTIME,
      )
      seedWorkerOwnership(
        FeatureTaskRuntimeWorkerOwnership(
          workflowId = WORKFLOW_ID,
          generation = 1,
          ownerToken = "owner-token-crashed01",
          hostIdentity = "host",
          bootIdentity = "boot",
          pid = 4242,
          processBirthToken = "birth-4242",
          leaseState = FeatureTaskRuntimeWorkerLeaseState.ACTIVE,
          heartbeatAt = "2000-01-01T00:00:00Z",
          expiresAt = "2000-01-01T00:00:30Z",
          phaseId = "implement",
          phaseAttempt = 1,
        ),
      )
    }

  private fun inspectionSupervisor(
    inspection: FeatureTaskRuntimeProcessInspection,
  ): FeatureTaskRuntimeWorkerSupervisor =
    object : FeatureTaskRuntimeWorkerSupervisor {
      override fun currentProcess() = FeatureTaskRuntimeProcessIdentity("h", "b", 1, "birth")

      override fun inspect(ownership: FeatureTaskRuntimeWorkerOwnership) = inspection

      override fun awaitExit(
        ownership: FeatureTaskRuntimeWorkerOwnership,
        timeout: Duration,
      ) = Unit

      override fun terminateGracefully(ownership: FeatureTaskRuntimeWorkerOwnership) = true

      override fun terminateForcibly(ownership: FeatureTaskRuntimeWorkerOwnership) = true

      override fun startHeartbeat(
        plan: FeatureTaskRuntimeHeartbeatPlan,
        heartbeat: () -> FeatureTaskRuntimeHeartbeatTick,
      ) = NoopFeatureTaskRuntimeHeartbeat

      override fun pause(durationMillis: Long) = Unit
    }

  private fun seedRepositoryCandidate(
    database: DatabaseSessionFactory,
    workflowId: String,
    repositoryRoot: Path,
    policy: Pair<String, Long?>,
  ) {
    database.selfManagedWrite { unit ->
      val inputs = execution.inputsFor(policy.first, policy.second)
      val identity =
        execution.identity(workflowId).copy(
          repositoryIdentity =
            FeatureTaskExecutionIdentityPolicy.REPOSITORY_IDENTITY_PREFIX +
              repositoryRoot.toAbsolutePath().normalize(),
        )
      execution.seed(
        unit.workflowStates,
        workflowId,
        descriptor = execution.descriptor(inputs),
        executionIdentity = identity,
      )
      val row = requireNotNull(unit.workflowStates.getFeatureTaskWorkflowAsMode(workflowId, RUNTIME))
      unit.workflowStates.saveFeatureTaskWorkflow(
        row.copy(workflowStatus = WorkflowStatus.RUNNING.wireValue),
        RUNTIME,
      )
      val updatedRow = requireNotNull(unit.workflowStates.getFeatureTaskWorkflowAsMode(workflowId, RUNTIME))
      unit.workflowStates.acquireFeatureTaskRuntimeWorker(
        FeatureTaskRuntimeWorkerOwnership(
          workflowId = workflowId,
          generation = 1,
          ownerToken = "$workflowId-owner",
          hostIdentity = "host",
          bootIdentity = "boot",
          pid = 4242,
          processBirthToken = "birth-4242",
          leaseState = FeatureTaskRuntimeWorkerLeaseState.ACTIVE,
          heartbeatAt = "2000-01-01T00:00:00Z",
          expiresAt = "2000-01-01T00:00:30Z",
          phaseId = "implement",
          phaseAttempt = 1,
        ),
        updatedRow.updatedAt,
      )
    }
  }

  private fun seedCrashPreservationRow(database: DatabaseSessionFactory) {
    database.selfManagedWrite { unit ->
      val row = requireNotNull(unit.workflowStates.getFeatureTaskWorkflowAsMode(WORKFLOW_ID, RUNTIME))
      val recordTime = "2026-09-28T12:00:00Z"
      val retainedRecords =
        mapOf(
          "preplan" to
            FeatureTaskRuntimePhaseRecord(
              phaseId = "preplan",
              status = WorkflowStepStatus.COMPLETED,
              attemptCount = 2,
              startedAt = recordTime,
              finishedAt = recordTime,
              resolvedAgentId = "agent-preplan",
              outputArtifact = "retained-preplan-output",
            ).asWorkflowArtifactEntry(),
          "commit_push" to
            FeatureTaskRuntimePhaseRecord(
              phaseId = "commit_push",
              status = WorkflowStepStatus.COMPLETED,
              attemptCount = 1,
              startedAt = recordTime,
              finishedAt = recordTime,
              resolvedAgentId = "runtime",
              outputArtifact = "retained-commit-push-evidence",
            ).asWorkflowArtifactEntry(),
        )
      val artifacts = crashPreservationArtifacts(row, retainedRecords, recordTime)
      val steps =
        WorkflowEngine().openRecord(
          WorkflowFamily.TASK_RUNTIME.definition,
          WORKFLOW_ID,
          row.sessionId.orEmpty(),
          "commit_push",
        ).toRecord().stepsJson
      unit.workflowStates.saveFeatureTaskWorkflow(
        row.copy(
          workflowStatus = WorkflowStatus.RUNNING.wireValue,
          currentStepId = "commit_push",
          stepsJson = steps,
          artifactsJson = JsonCodec.mapToJsonString(artifacts),
        ),
        RUNTIME,
      )
      val updatedRow = requireNotNull(unit.workflowStates.getFeatureTaskWorkflowAsMode(WORKFLOW_ID, RUNTIME))
      unit.workflowStates.acquireFeatureTaskRuntimeWorker(
        FeatureTaskRuntimeWorkerOwnership(
          workflowId = WORKFLOW_ID,
          generation = 1,
          ownerToken = "owner-token-crashed01",
          hostIdentity = "host",
          bootIdentity = "boot",
          pid = 4242,
          processBirthToken = "birth-4242",
          leaseState = FeatureTaskRuntimeWorkerLeaseState.ACTIVE,
          heartbeatAt = "2000-01-01T00:00:00Z",
          expiresAt = "2000-01-01T00:00:30Z",
          phaseId = "commit_push",
          phaseAttempt = 1,
        ),
        updatedRow.updatedAt,
      )
    }
  }

  private fun crashPreservationArtifacts(
    row: WorkflowStateRecord,
    retainedRecords: Map<String, Any?>,
    recordTime: String,
  ): Map<String, Any?> {
    return row.toSnapshot().artifacts +
      DurableWorkflowArtifactFamily.FEATURE_TASK_RUNTIME_EXECUTION_PLAN.entry(
        execution.validator.read(
          execution.encoded(execution.inputsFor(null, 45_000)),
          "crash recovery preservation test",
        ),
      ) +
      DurableWorkflowArtifactFamily.FEATURE_TASK_RUNTIME_PHASE_RECORDS.entry(retainedRecords) +
      DurableWorkflowArtifactFamily.FEATURE_TASK_RUNTIME_CHECKPOINT_IDENTITIES.entry(
        mapOf(
          "contract_version" to FEATURE_TASK_RUNTIME_CHECKPOINT_IDENTITY_CONTRACT_VERSION,
          "checkpoints" to
            listOf(
              mapOf(
                "sequence_number" to 1,
                "issue_key" to "SKILL-384",
                "subtask_id" to "2",
                "checkpoint_ref" to featureTaskRuntimeCheckpointRefName("SKILL-384", "2", 1),
                "branch" to "feat/SKILL-384",
                "phase_id" to "implement",
                "generation" to 1,
                "owned_path_digest" to "a".repeat(64),
                "owned_path_count" to 1,
                "commit_sha" to "b".repeat(40),
                "recorded_at" to recordTime,
              ),
            ),
        ),
      )
  }

  private fun changedGateDescriptor(): Map<String, Any?> {
    return execution.descriptor().toMutableMap().apply {
      val policies =
        (get(Keys.EFFECTIVE_POLICIES) as List<*>).map {
          requireNotNull(JsonCodec.anyToStringAnyMap(it))
        }.map { policy ->
          if (policy[Keys.ID] == "gate-commands") policy + (Keys.SEMANTIC_DIGEST to "0".repeat(64)) else policy
        }
      put(Keys.EFFECTIVE_POLICIES, policies)
    }
  }

  private fun incompatibleStrategyDescriptor(): Map<String, Any?> {
    return execution.descriptor().toMutableMap().apply {
      val strategies =
        (get(Keys.SELECTED_STRATEGIES) as List<*>).map {
          requireNotNull(JsonCodec.anyToStringAnyMap(it))
        }.mapIndexed { index, strategy ->
          if (index == 0) strategy + (Keys.SEMANTIC_REVISION to 99) else strategy
        }
      put(Keys.SELECTED_STRATEGIES, strategies)
    }
  }
}
