package skillbill.engine.featuretask.lifecycle.execution

import skillbill.application.testDecompositionManifestValidator
import skillbill.application.testDecompositionManifestWriter
import skillbill.application.testHarnessClock
import skillbill.application.testRepositoryRoot
import skillbill.application.testWorkflowSnapshotValidator
import skillbill.application.workflow.model.WorkflowFamilyKind
import skillbill.application.workflow.model.WorkflowOpenResult
import skillbill.application.workflow.model.WorkflowServiceOpenFeatureTaskArgs
import skillbill.application.workflow.persist.openFeatureTask
import skillbill.application.workflow.service.WorkflowService
import skillbill.contracts.JsonCodec
import skillbill.contracts.SharedPayloadKeys
import skillbill.contracts.workflow.featuretask.FEATURE_TASK_RUNTIME_CONTRACT_VERSION
import skillbill.engine.featuretask.lifecycle.core.AcceptingFeatureTaskRuntimeWireArtifactValidator
import skillbill.engine.featuretask.model.execution.FeatureTaskRuntimeExecutionPlanCreationRequest
import skillbill.engine.featuretask.persist.FeatureTaskRuntimeWorkflowPersistence
import skillbill.engine.featuretask.phase.core.decodePhaseRecords
import skillbill.engine.featuretask.phaserun.phaseRunDatabase
import skillbill.engine.featuretask.runner.SlotBaselineSqlite
import skillbill.engine.featuretask.slot.artifactValue
import skillbill.engine.goalrunner.manifest.GoalParentProjectionWriter
import skillbill.engine.goalrunner.model.GoalChildPlanningHydrationRequest
import skillbill.engine.goalrunner.model.GoalRunnerChildExecutionPlanAdmission
import skillbill.engine.goalrunner.model.GoalRunnerChildWorkflowSetup
import skillbill.engine.goalrunner.model.GoalRunnerManifestState
import skillbill.engine.goalrunner.persist.WorkflowGoalRunnerBlockWrites
import skillbill.engine.goalrunner.persist.engineWorkflowGoalRunnerManifestStore
import skillbill.engine.goalrunner.planning.hydration.GoalChildPlanningHydratorPortAdapter
import skillbill.engine.goalrunner.reset.WorkflowGoalRunnerChildWorkflowPersistence
import skillbill.error.featuretask.FeatureTaskRuntimeExecutionPlanAdmissionError
import skillbill.error.featuretask.FeatureTaskRuntimeExecutionPlanConflictError
import skillbill.error.featuretask.IncompatibleFeatureTaskRuntimeExecutionPlanError
import skillbill.error.featuretask.MissingFeatureTaskRuntimeExecutionPlanError
import skillbill.goalrunner.model.GoalRunnerControlState
import skillbill.goalrunner.model.GoalRunnerExecutionLease
import skillbill.ports.db.DatabaseSessionFactory
import skillbill.ports.diagnostics.NoopRuntimeDiagnostics
import skillbill.ports.featuretask.model.FeatureTaskRuntimeWorkerLeaseState
import skillbill.ports.featuretask.model.FeatureTaskRuntimeWorkerOwnership
import skillbill.ports.goalrunner.model.GoalPlanningContractProvenance
import skillbill.ports.goalrunner.model.GoalPlanningIdentity
import skillbill.ports.goalrunner.model.GoalSubtaskPlanCheckpoint
import skillbill.ports.goalrunner.model.GovernedGoalSubtaskDescriptor
import skillbill.ports.goalrunner.model.SharedGoalPreplanCheckpoint
import skillbill.ports.goalrunner.runner.model.GoalRunnerReviewPolicy
import skillbill.ports.persistence.UnitOfWork
import skillbill.ports.taskruntime.model.ValidatedFeatureTaskRuntimeExecutionPlan
import skillbill.ports.workflow.WorkflowStateRepository
import skillbill.ports.workflow.decomposition.UnavailableDecompositionManifestStore
import skillbill.ports.workflow.decomposition.encodeManifestWireMap
import skillbill.ports.workflow.gitops.NoopWorkflowGitOperations
import skillbill.ports.workflow.gitops.model.GoalSubtaskReviewBaseline
import skillbill.ports.workflow.model.WorkflowFamily
import skillbill.ports.workflow.model.WorkflowStateRecord
import skillbill.ports.workflow.model.toSnapshot
import skillbill.ports.workflow.toRecord
import skillbill.review.context.model.execution.CodeReviewExecutionMode
import skillbill.text.sha256HexUtf8
import skillbill.workflow.decomposition.model.CurrentSubtaskIntent
import skillbill.workflow.decomposition.model.DecompositionManifest
import skillbill.workflow.decomposition.model.DecompositionSubtask
import skillbill.workflow.engine.WorkflowEngine
import skillbill.workflow.engine.model.DurableWorkflowArtifactFamily
import skillbill.workflow.engine.model.DurableWorkflowArtifacts
import skillbill.workflow.model.FeatureTaskRouteScope
import skillbill.workflow.model.FeatureTaskWorkflowMode
import skillbill.workflow.model.ValidationDepth
import skillbill.workflow.model.WorkflowStatus
import skillbill.workflow.model.WorkflowStepStatus
import skillbill.workflow.taskruntime.model.phase.FeatureTaskRuntimePhaseExecutionOrigin
import skillbill.workflow.taskruntime.model.skeleton.FeatureTaskRuntimeQualityGateSelection
import skillbill.workflow.taskruntime.model.skeleton.SkeletonDefinition
import java.nio.file.Files
import java.nio.file.Path
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue
import skillbill.contracts.workflow.identity.task.FeatureTaskRuntimeExecutionPlanKeys as Keys

class FeatureTaskExecutionPlanCreationTest {
  @Test
  fun `standalone descriptor write rejection rolls back the workflow and route identity`() =
    withDatabase { database ->
      val execution = ExecutionPlanAdmissionFixture()
      val descriptor =
        execution.creationResolver().resolveCreation(
          FeatureTaskRuntimeExecutionPlanCreationRequest(
            testRepositoryRoot.path,
            SkeletonDefinition.STANDALONE,
            CodeReviewExecutionMode.INLINE,
            null,
            ValidationDepth.FULL,
            null,
          ),
        )
      val rejecting = RejectDescriptorWrites(database)
      assertFailsWith<DescriptorWriteRejected> { service(rejecting).openFeatureTask(openArgs(descriptor)) }
      val rejectedId = assertNotNull(rejecting.rejectedId)
      database.read { unit ->
        assertNull(unit.workflowStates.getFeatureTaskWorkflow(rejectedId))
        assertNull(unit.workflowStates.getFeatureTaskExecutionIdentity(rejectedId))
        assertTrue(unit.workflowStates.list(WorkflowFamily.TASK_RUNTIME, 100).isEmpty())
      }

      val opened = assertIs<WorkflowOpenResult.Ok>(service(database).openFeatureTask(openArgs(descriptor)))
      database.read { unit ->
        val row = assertNotNull(unit.workflowStates.get(WorkflowFamily.TASK_RUNTIME, opened.workflowId))
        assertEquals(descriptor.artifactValue, family.value(row.artifacts))
        val identity = assertNotNull(unit.workflowStates.getFeatureTaskExecutionIdentity(opened.workflowId))
        assertEquals(REPOSITORY, identity.repositoryIdentity)
        assertEquals(FeatureTaskRouteScope.STANDALONE, identity.routeScope)
        assertEquals(SPEC, identity.governedSpecPath)
      }
      assertEquals(0, execution.launches)
    }

  @Test
  fun `standalone creation without a descriptor leaves no workflow or route identity`() =
    withDatabase { database ->
      assertFailsWith<MissingFeatureTaskRuntimeExecutionPlanError> {
        service(database).openFeatureTask(openArgs(null))
      }
      database.read { unit ->
        assertNull(unit.workflowStates.getFeatureTaskWorkflow(CHILD))
        assertNull(unit.workflowStates.getFeatureTaskExecutionIdentity(CHILD))
        assertTrue(unit.workflowStates.list(WorkflowFamily.TASK_RUNTIME, 100).isEmpty())
      }
    }

  @Test
  fun `direct creation retry preserves its descriptor and conflict cannot update session or issue metadata`() =
    withDatabase { database ->
      val execution = ExecutionPlanAdmissionFixture()
      val bytes = execution.encoded.copyOf()
      val descriptor = ValidatedFeatureTaskRuntimeExecutionPlan.read(bytes, execution.validator)
      bytes.fill(0)
      val persistence = FeatureTaskRuntimeWorkflowPersistence(database, testWorkflowSnapshotValidator)
      assertTrue(persistence.ensureWorkflowOpen(CHILD, "creation-session", null, descriptor))
      val first = database.read { assertNotNull(it.workflowStates.getFeatureTaskWorkflow(CHILD)) }
      assertTrue(persistence.ensureWorkflowOpen(CHILD, "creation-session", null, descriptor))
      database.read { assertEquals(first, it.workflowStates.getFeatureTaskWorkflow(CHILD)) }
      assertEquals(execution.descriptor(), family.value(first.toSnapshot().artifacts))
      descriptor.encoded().fill(0)
      assertEquals(execution.descriptor(), descriptor.artifactValue)
      val changed =
        ValidatedFeatureTaskRuntimeExecutionPlan.read(
          execution.codec.encodeExecution(execution.plan, execution.inputs.copy(phaseTimeoutMillis = 1)),
          execution.validator,
        )

      assertFailsWith<FeatureTaskRuntimeExecutionPlanConflictError> {
        persistence.ensureWorkflowOpen(CHILD, "replacement-session", ISSUE, changed)
      }

      database.read { assertEquals(first, it.workflowStates.getFeatureTaskWorkflow(CHILD)) }
    }

  @Test
  fun `direct executable workflow creation without a descriptor leaves no row`() =
    withDatabase { database ->
      val persistence = FeatureTaskRuntimeWorkflowPersistence(database, testWorkflowSnapshotValidator)
      assertFailsWith<MissingFeatureTaskRuntimeExecutionPlanError> {
        persistence.ensureWorkflowOpen(CHILD, "creation-session")
      }
      database.read { assertNull(it.workflowStates.getFeatureTaskWorkflow(CHILD)) }
    }

  @Test
  fun `goal child descriptor failure rolls back parent linkage child identity and imported planning`() =
    withDatabase { database ->
      val fixture = ChildCreation(database)
      fixture.seed()
      val before = database.read { assertNotNull(it.workflowStates.getFeatureTaskWorkflow(PARENT)) }
      val rejecting = RejectDescriptorWrites(database)

      assertFailsWith<DescriptorWriteRejected> { fixture.save(rejecting) }

      assertEquals(CHILD, rejecting.rejectedId)
      database.read { unit ->
        assertEquals(before, unit.workflowStates.getFeatureTaskWorkflow(PARENT))
        assertNull(unit.workflowStates.getFeatureTaskWorkflow(CHILD))
        assertNull(unit.workflowStates.getFeatureTaskExecutionIdentity(CHILD))
        assertEquals(listOf(PARENT), unit.workflowStates.list(WorkflowFamily.TASK_RUNTIME, 100).map { it.workflowId })
        assertEquals(fixture.preplan, unit.goalPlanningPreparations.findSharedPreplan(fixture.identity)?.preplanPayload)
        assertEquals(
          fixture.plan,
          unit.goalPlanningPreparations.findSubtaskPlan(fixture.identity, 1, SPEC)?.planPayload,
        )
      }

      fixture.save(database)
      val first = database.read { assertNotNull(it.workflowStates.getFeatureTaskWorkflow(CHILD)) }
      fixture.save(database)
      database.read { unit ->
        assertEquals(first, unit.workflowStates.getFeatureTaskWorkflow(CHILD))
        val identity = assertNotNull(unit.workflowStates.getFeatureTaskExecutionIdentity(CHILD))
        assertEquals(REPOSITORY, identity.repositoryIdentity)
        assertEquals(FeatureTaskRouteScope.GOAL_CHILD, identity.routeScope)
        val snapshot = first.toSnapshot()
        assertEquals(fixture.setup.executionPlan?.artifactValue, family.value(snapshot.artifacts))
        val records = decodePhaseRecords(snapshot.artifacts)
        listOf("preplan" to fixture.preplan, "plan" to fixture.plan).forEach { (step, output) ->
          val record = assertNotNull(records[step])
          assertEquals(WorkflowStepStatus.COMPLETED, record.status)
          assertEquals(output, record.outputArtifact)
          assertEquals(1, record.attemptCount)
          assertEquals("goal-planning-import", record.resolvedAgentId)
          assertEquals(0L, record.durationMillis)
          assertEquals(FeatureTaskRuntimePhaseExecutionOrigin.GOAL_PLANNING_HYDRATED, record.executionOrigin)
        }
      }
      assertEquals(0, fixture.execution.launches)
    }

  @Test
  fun `goal child reuse rejects corrupt unsupported and incompatible descriptors before parent or child mutation`() {
    listOf("missing", "corrupt", "unsupported-version", "unsupported", "incompatible").forEach { invalidKind ->
      withDatabasePath { database, databasePath ->
        val fixture = ChildCreation(database)
        fixture.seed()
        fixture.save(database)
        val corruptArtifactsJson = corruptChildArtifacts(database, fixture, invalidKind)
        SlotBaselineSqlite.updateFeatureTaskArtifacts(databasePath, CHILD, corruptArtifactsJson)
        val controls =
          GoalRunnerControlState(
            currentSubtaskId = 99,
            subtaskActiveDurationMs = 123,
            paused = true,
            pauseRequested = true,
            pauseConsumed = true,
            pauseReason = "runner_interrupted",
            pausedAt = "2026-09-28T10:00:00Z",
            executionLease = parentLease,
          )
        database.transaction { it.goalRunnerControls.persistControlState(PARENT, controls) }
        val parentBefore = database.read { assertNotNull(it.workflowStates.getFeatureTaskWorkflow(PARENT)) }
        val childBefore = database.read { assertNotNull(it.workflowStates.getFeatureTaskWorkflow(CHILD)) }
        val store = fixture.store()
        assertFailsWith<FeatureTaskRuntimeExecutionPlanAdmissionError>(invalidKind) {
          store.acquireExecutionLeaseWithChildAdmission(
            PARENT,
            parentLease.copy(ownerToken = "new-parent-owner", generation = 2),
            parentLease.ownerToken,
            GoalRunnerChildExecutionPlanAdmission(CHILD, assertNotNull(fixture.setup.executionPlan)),
          )
        }
        val error = assertFailsWith<RuntimeException>(invalidKind) { fixture.save(database) }
        if (invalidKind == "incompatible") {
          assertIs<IncompatibleFeatureTaskRuntimeExecutionPlanError>(error)
        } else {
          val admissionError = assertIs<FeatureTaskRuntimeExecutionPlanAdmissionError>(error)
          assertEquals(
            when (invalidKind) {
              "missing" -> "missing_descriptor"
              "corrupt" -> "corrupt_descriptor"
              else -> "unsupported_descriptor"
            },
            admissionError.reasonCode,
          )
        }
        database.read { unit ->
          assertEquals(parentBefore, unit.workflowStates.getFeatureTaskWorkflow(PARENT), invalidKind)
          assertEquals(childBefore, unit.workflowStates.getFeatureTaskWorkflow(CHILD), invalidKind)
          assertEquals(controls, unit.goalRunnerControls.controlState(PARENT), invalidKind)
        }
        assertEquals(0, fixture.execution.launches)
      }
    }
  }

  @Test
  fun `parent lease admission fences stale owners and stale child linkage before control reconciliation`() =
    withDatabase { database ->
      val fixture = ChildCreation(database)
      fixture.seed()
      fixture.save(database)
      val store = fixture.store()
      val controls =
        GoalRunnerControlState(currentSubtaskId = 99, subtaskActiveDurationMs = 123, executionLease = parentLease)
      store.persistControlState(PARENT, controls)
      val admission = GoalRunnerChildExecutionPlanAdmission(CHILD, assertNotNull(fixture.setup.executionPlan))
      val next = parentLease.copy(ownerToken = "new-parent-owner", generation = 2)

      assertFalse(store.acquireExecutionLeaseWithChildAdmission(PARENT, next, "stale-owner", admission))
      assertFailsWith<FeatureTaskRuntimeExecutionPlanConflictError> {
        store.acquireExecutionLeaseWithChildAdmission(
          PARENT,
          next,
          parentLease.ownerToken,
          admission.copy(workflowId = "stale-child"),
        )
      }
      assertFailsWith<FeatureTaskRuntimeExecutionPlanConflictError> {
        store.acquireExecutionLease(PARENT, next, parentLease.ownerToken)
      }
      assertEquals(controls, store.controlState(PARENT))
      val childBefore = database.read { it.workflowStates.getFeatureTaskWorkflow(CHILD) }
      assertTrue(store.acquireExecutionLeaseWithChildAdmission(PARENT, next, parentLease.ownerToken, admission))
      assertEquals(next, store.executionLease(PARENT))
      assertEquals(1, store.controlState(PARENT).currentSubtaskId)
      assertEquals(childBefore, database.read { it.workflowStates.getFeatureTaskWorkflow(CHILD) })
    }

  @Test
  fun `conflicting goal child descriptor refuses before either workflow changes`() =
    withDatabase { database ->
      val fixture = ChildCreation(database)
      fixture.seed()
      fixture.save(database)
      val before =
        database.read {
          it.workflowStates.list(
            WorkflowFamily.TASK_RUNTIME,
            100,
          ).map { row -> row.toRecord() }
        }
      val changed =
        ValidatedFeatureTaskRuntimeExecutionPlan.read(
          fixture.execution.codec.encodeExecution(
            fixture.execution.plan,
            fixture.execution.inputs.copy(phaseTimeoutMillis = 1),
          ),
          fixture.execution.validator,
        )

      assertFailsWith<IncompatibleFeatureTaskRuntimeExecutionPlanError> {
        fixture.save(database, fixture.setup.copy(executionPlan = changed))
      }
      database.read { unit ->
        assertEquals(before, unit.workflowStates.list(WorkflowFamily.TASK_RUNTIME, 100).map { it.toRecord() })
      }
      assertFailsWith<FeatureTaskRuntimeExecutionPlanConflictError> {
        fixture.save(database, fixture.setup.copy(executionPlan = null))
      }
      database.read { unit ->
        assertEquals(before, unit.workflowStates.list(WorkflowFamily.TASK_RUNTIME, 100).map { it.toRecord() })
        val child = assertNotNull(unit.workflowStates.getFeatureTaskWorkflow(CHILD))
        assertEquals(fixture.setup.executionPlan?.artifactValue, family.value(child.toSnapshot().artifacts))
      }
      assertEquals(0, fixture.execution.launches)
    }

  @Test
  fun `blocked child reopen rechecks descriptor and worker ownership before writes`() =
    withDatabasePath { database, databasePath ->
      listOf("descriptor", "owner").forEach { changedFact ->
        val workflowId = "$CHILD-$changedFact"
        val execution = ExecutionPlanAdmissionFixture(SkeletonDefinition.GOAL_CHILD)
        database.transaction { unit ->
          execution.seed(unit.workflowStates, workflowId)
          val row = assertNotNull(unit.workflowStates.getFeatureTaskWorkflow(workflowId))
          unit.workflowStates.saveFeatureTaskWorkflow(
            row.copy(workflowStatus = WorkflowStatus.BLOCKED.wireValue),
            FeatureTaskWorkflowMode.RUNTIME,
          )
        }
        changeBlockedChildAdmission(database, databasePath, execution, workflowId, changedFact)
        val before = database.read { assertNotNull(it.workflowStates.getFeatureTaskWorkflow(workflowId)) }
        val ownerBefore = database.read { it.workflowStates.getFeatureTaskRuntimeWorkerOwnership(workflowId) }
        val identity = execution.identity(workflowId)
        val expectedPlan = ValidatedFeatureTaskRuntimeExecutionPlan.read(execution.encoded, execution.validator)
        val writes = WorkflowGoalRunnerBlockWrites(WorkflowEngine(), testHarnessClock)

        assertFailsWith<IncompatibleFeatureTaskRuntimeExecutionPlanError>(changedFact) {
          database.transaction { unit ->
            writes.reopenBlockedPhaseForOperatorResume(
              unit,
              "implement",
              "operator resume",
              identity,
              expectedPlan,
            )
          }
        }

        database.read { unit ->
          assertEquals(before, unit.workflowStates.getFeatureTaskWorkflow(workflowId), changedFact)
          assertEquals(identity, unit.workflowStates.getFeatureTaskExecutionIdentity(workflowId), changedFact)
          assertEquals(ownerBefore, unit.workflowStates.getFeatureTaskRuntimeWorkerOwnership(workflowId), changedFact)
        }
        assertEquals(0, execution.launches)
      }
    }

  @Test
  fun `goal child reuse and blocked reopen reject changed admission before parent or child mutation`() {
    listOf("descriptor", "owner").forEach { changedFact ->
      withDatabasePath { database, databasePath ->
        val fixture = ChildCreation(database)
        fixture.seed()
        fixture.save(database)
        if (changedFact == "descriptor") {
          val row = database.read { assertNotNull(it.workflowStates.getFeatureTaskWorkflow(CHILD)) }
          val descriptor =
            fixture.setup.executionPlan!!.artifactValue.toMutableMap().apply {
              val policies =
                (
                  get(
                    Keys.EFFECTIVE_POLICIES,
                  ) as List<*>
                ).map { requireNotNull(JsonCodec.anyToStringAnyMap(it)) }
              put(
                Keys.EFFECTIVE_POLICIES,
                policies.map { policy ->
                  if (policy[Keys.ID] == "gate-commands") policy + (Keys.SEMANTIC_DIGEST to "0".repeat(64)) else policy
                },
              )
            }
          SlotBaselineSqlite.updateFeatureTaskArtifacts(
            databasePath,
            CHILD,
            JsonCodec.mapToJsonString(row.toSnapshot().artifacts + family.entry(descriptor)),
          )
        } else {
          database.selfManagedWrite { unit ->
            val row = assertNotNull(unit.workflowStates.getFeatureTaskWorkflow(CHILD))
            assertTrue(
              unit.workflowStates.acquireFeatureTaskRuntimeWorker(
                FeatureTaskRuntimeWorkerOwnership(
                  workflowId = CHILD, generation = 1, ownerToken = "owner-token-reopen-0001",
                  hostIdentity = "host", bootIdentity = "boot", pid = 4242, processBirthToken = "birth",
                  leaseState = FeatureTaskRuntimeWorkerLeaseState.ACTIVE, heartbeatAt = "2026-09-28T00:00:00Z",
                  expiresAt = "2026-09-28T00:00:30Z", phaseId = "implement", phaseAttempt = 1,
                ),
                row.updatedAt,
              ),
            )
          }
        }
        val before =
          database.read { unit ->
            Pair(
              assertNotNull(unit.workflowStates.getFeatureTaskWorkflow(PARENT)),
              assertNotNull(unit.workflowStates.getFeatureTaskWorkflow(CHILD)),
            )
          }
        val ownerBefore = database.read { it.workflowStates.getFeatureTaskRuntimeWorkerOwnership(CHILD) }

        assertFailsWith<IncompatibleFeatureTaskRuntimeExecutionPlanError>(changedFact) {
          fixture.save(
            database,
            fixture.setup.copy(
              operatorResumePhaseId = "implement",
              operatorResumeReason = "operator resumed after blocked stop",
            ),
          )
        }

        database.read { unit ->
          assertEquals(before.first, unit.workflowStates.getFeatureTaskWorkflow(PARENT), changedFact)
          assertEquals(before.second, unit.workflowStates.getFeatureTaskWorkflow(CHILD), changedFact)
          assertEquals(ownerBefore, unit.workflowStates.getFeatureTaskRuntimeWorkerOwnership(CHILD), changedFact)
        }
      }
    }
  }

  @Test
  fun `goal child creation without a descriptor leaves parent and child untouched`() =
    withDatabase { database ->
      val fixture = ChildCreation(database)
      fixture.seed()
      val before =
        database.read {
          it.workflowStates.list(
            WorkflowFamily.TASK_RUNTIME,
            100,
          ).map { row -> row.toRecord() }
        }
      assertFailsWith<MissingFeatureTaskRuntimeExecutionPlanError> {
        fixture.save(database, fixture.setup.copy(executionPlan = null))
      }
      database.read {
        assertEquals(before, it.workflowStates.list(WorkflowFamily.TASK_RUNTIME, 100).map { row -> row.toRecord() })
      }
    }

  private class ChildCreation(val database: DatabaseSessionFactory) {
    val execution = ExecutionPlanAdmissionFixture(SkeletonDefinition.GOAL_CHILD)
    val identity = GoalPlanningIdentity(PARENT, ISSUE, REPOSITORY)
    private val provenance =
      GoalPlanningContractProvenance(
        "a".repeat(64),
        "b".repeat(64),
        "https://skill-bill.dev/contracts/goal-planning-preparation-schema.yaml",
      )
    private val descriptor = GovernedGoalSubtaskDescriptor(1, 0, SPEC, "c".repeat(64))
    val preplan = output("preplan", "retained shared preplan")
    val plan = output("plan", "retained child plan")
    private val manifest =
      DecompositionManifest(
        issueKey = ISSUE,
        featureName = "descriptor creation",
        parentSpecPath = SPEC,
        baseBranch = "main",
        featureBranch = "feat/SKILL-384",
        currentSubtaskIntent = CurrentSubtaskIntent(1, "resume"),
        subtasks = listOf(DecompositionSubtask(1, "child", SPEC)),
      )
    val setup =
      GoalRunnerChildWorkflowSetup(
        subtaskId = 1, workflowId = CHILD, goalBranch = "feat/SKILL-384", normalizedIssueKey = ISSUE,
        repositoryIdentity = REPOSITORY, governedSpecPath = SPEC,
        reviewBaseline = GoalSubtaskReviewBaseline("d".repeat(40), emptyList()),
        reviewPolicy = GoalRunnerReviewPolicy(CodeReviewExecutionMode.INLINE),
        planningHydration = GoalChildPlanningHydrationRequest(identity, provenance, descriptor),
        executionPlan =
          execution.creationResolver().resolveCreation(
            FeatureTaskRuntimeExecutionPlanCreationRequest(
              testRepositoryRoot.path,
              SkeletonDefinition.GOAL_CHILD,
              CodeReviewExecutionMode.INLINE,
              FeatureTaskRuntimeQualityGateSelection.VALIDATE,
              ValidationDepth.FULL,
              null,
            ),
          ),
      )
    private val engine = WorkflowEngine()
    private val persistence =
      WorkflowGoalRunnerChildWorkflowPersistence(
        engine,
        GoalChildPlanningHydratorPortAdapter(testHarnessClock),
        GoalParentProjectionWriter(engine, testDecompositionManifestValidator),
        execution.admission,
        testHarnessClock,
      )

    fun store() =
      engineWorkflowGoalRunnerManifestStore(
        database, testWorkflowSnapshotValidator, testDecompositionManifestValidator,
        UnavailableDecompositionManifestStore, testHarnessClock, testDecompositionManifestWriter,
        testRepositoryRoot,
        GoalChildPlanningHydratorPortAdapter(testHarnessClock),
        executionPlanCompatibility = execution.compatibility,
      )

    fun seed() =
      database.selfManagedWrite { unit ->
        val row = engine.openRecord(WorkflowFamily.TASK_RUNTIME.definition, PARENT, "parent-session", "plan")
        unit.workflowStates.saveRecord(
          WorkflowFamily.TASK_RUNTIME,
          row.copy(
            artifacts =
              DurableWorkflowArtifacts.fromMap(
                mapOf(
                  DurableWorkflowArtifactFamily.DECOMPOSITION_RUNTIME.entry(
                    testDecompositionManifestValidator.encodeManifestWireMap(manifest),
                  ),
                ),
              ),
          ).toRecord().copy(issueKey = ISSUE),
        )
        unit.goalPlanningPreparations.checkpointSharedPreplan(
          SharedGoalPreplanCheckpoint(
            identity = identity,
            provenance = provenance,
            payloadSha256 = sha256HexUtf8(preplan),
            preplanPayload = preplan,
          ),
        )
        unit.goalPlanningPreparations.checkpointSubtaskPlan(
          GoalSubtaskPlanCheckpoint(
            identity = identity,
            subtaskId = 1,
            manifestOrder = 0,
            governedSubSpecPath = SPEC,
            subSpecHash = descriptor.subSpecHash,
            provenance = provenance,
            payloadSha256 = sha256HexUtf8(plan),
            planPayload = plan,
          ),
        )
      }

    fun save(
      target: DatabaseSessionFactory,
      proposed: GoalRunnerChildWorkflowSetup = setup,
    ) = target.transaction { unit ->
      persistence.saveInTransaction(
        unit,
        GoalRunnerManifestState(
          PARENT,
          unit.dbPath.toString(),
          manifest.copy(subtasks = manifest.subtasks.map { it.copy(workflowId = CHILD, status = "in_progress") }),
        ),
        proposed,
      )
    }
  }

  private class DescriptorWriteRejected : RuntimeException()

  private class RejectDescriptorWrites(
    private val delegate: DatabaseSessionFactory,
  ) : DatabaseSessionFactory by delegate {
    var rejectedId: String? = null

    override fun <T> transaction(block: (UnitOfWork) -> T): T =
      delegate.transaction { unit ->
        val states =
          object : WorkflowStateRepository by unit.workflowStates {
            override fun saveRecord(
              family: WorkflowFamily,
              record: WorkflowStateRecord,
            ) {
              unit.workflowStates.saveRecord(family, record)
              if (FeatureTaskExecutionPlanCreationTest.family.contains(record.toSnapshot().artifacts)) {
                rejectedId = record.workflowId
                throw DescriptorWriteRejected()
              }
            }
          }
        block(
          object : UnitOfWork by unit {
            override val workflowStates = states
          },
        )
      }
  }

  private fun service(database: DatabaseSessionFactory) =
    WorkflowService(
      database, NoopWorkflowGitOperations, UnavailableDecompositionManifestStore, testWorkflowSnapshotValidator,
      testDecompositionManifestValidator, testDecompositionManifestWriter, testRepositoryRoot,
      AcceptingFeatureTaskRuntimeWireArtifactValidator, NoopRuntimeDiagnostics, testHarnessClock,
    )

  private fun openArgs(descriptor: ValidatedFeatureTaskRuntimeExecutionPlan?) =
    WorkflowServiceOpenFeatureTaskArgs(
      kind = WorkflowFamilyKind.TASK_RUNTIME,
      issueKey = ISSUE,
      repositoryIdentity = REPOSITORY,
      governedSpecPath = SPEC,
      executionPlan = descriptor,
    )

  private fun withDatabase(block: (DatabaseSessionFactory) -> Unit) {
    withDatabasePath { database, _ -> block(database) }
  }

  private fun withDatabasePath(block: (DatabaseSessionFactory, Path) -> Unit) {
    val root = Files.createTempDirectory("execution-plan-creation")
    try {
      block(phaseRunDatabase(root, testHarnessClock), root.resolve("metrics.db"))
    } finally {
      root.toFile().deleteRecursively()
    }
  }

  private companion object {
    val parentLease =
      GoalRunnerExecutionLease(
        generation = 1,
        ownerToken = "old-parent-owner",
        hostIdentity = "host",
        bootIdentity = "boot",
        pid = 123,
        processBirthToken = "birth",
        heartbeatAt = "2026-09-28T09:00:00Z",
        expiresAt = "2026-09-28T09:01:00Z",
      )
    const val ISSUE = "SKILL-384"
    const val PARENT = "wftr-creation-parent"
    const val CHILD = "wftr-creation-child"
    const val REPOSITORY = "repo-root-realpath-v1:/tmp/execution-plan-creation"
    const val SPEC = ".feature-specs/SKILL-384/spec.md"
    val family = DurableWorkflowArtifactFamily.FEATURE_TASK_RUNTIME_EXECUTION_PLAN

    fun output(
      phase: String,
      value: String,
    ): String =
      JsonCodec.mapToJsonString(
        mapOf(
          SharedPayloadKeys.CONTRACT_VERSION to FEATURE_TASK_RUNTIME_CONTRACT_VERSION,
          SharedPayloadKeys.PHASE_ID to phase,
          SharedPayloadKeys.STATUS to "completed",
          SharedPayloadKeys.SUMMARY to phase,
          SharedPayloadKeys.PRODUCED_OUTPUTS to mapOf(SharedPayloadKeys.VALUE to value),
        ),
      )
  }

  private fun corruptChildArtifacts(
    database: DatabaseSessionFactory,
    fixture: ChildCreation,
    invalidKind: String,
  ): String {
    return database.transaction { unit ->
      val row = assertNotNull(unit.workflowStates.get(WorkflowFamily.TASK_RUNTIME, CHILD))
      val stored = assertNotNull(JsonCodec.anyToStringAnyMap(family.value(row.artifacts)))
      val replacement =
        when (invalidKind) {
          "missing" -> null
          "corrupt" -> stored - Keys.SELECTED_STRATEGIES
          "unsupported-version" -> stored + (Keys.CONTRACT_VERSION to "99.0")
          "unsupported" ->
            (
              stored + (
                Keys.SELECTED_STRATEGIES to
                  (stored[Keys.SELECTED_STRATEGIES] as List<*>).map {
                    requireNotNull(
                      JsonCodec.anyToStringAnyMap(it),
                    )
                  }.map { strategy ->
                    strategy + (Keys.SEMANTIC_REVISION to 99)
                  }
              )
            ).let { selected ->
              selected + (
                Keys.DISPATCH_OWNERSHIP to
                  (stored[Keys.DISPATCH_OWNERSHIP] as List<*>).map {
                    requireNotNull(
                      JsonCodec.anyToStringAnyMap(it),
                    )
                  }.map { dispatch ->
                    dispatch + (Keys.SEMANTIC_REVISION to 99)
                  }
              )
            }
          else ->
            fixture.execution.codec.encodeExecution(
              fixture.execution.plan,
              fixture.execution.inputs.copy(phaseTimeoutMillis = 1),
            ).let { fixture.execution.validator.read(it, "changed execution policy") }
        }
      JsonCodec.mapToJsonString(
        if (replacement == null) {
          row.artifacts.toMutableMap().also(
            family::removeFrom,
          )
        } else {
          row.artifacts + family.entry(replacement)
        },
      )
    }
  }

  private fun changeBlockedChildAdmission(
    database: DatabaseSessionFactory,
    databasePath: Path,
    execution: ExecutionPlanAdmissionFixture,
    workflowId: String,
    changedFact: String,
  ) {
    if (changedFact == "descriptor") {
      val row = database.read { assertNotNull(it.workflowStates.getFeatureTaskWorkflow(workflowId)) }
      val descriptor =
        execution.descriptor().toMutableMap().apply {
          val policies =
            (
              get(
                Keys.EFFECTIVE_POLICIES,
              ) as List<*>
            ).map { requireNotNull(JsonCodec.anyToStringAnyMap(it)) }
          put(
            Keys.EFFECTIVE_POLICIES,
            policies.map { policy ->
              if (policy[Keys.ID] == "gate-commands") policy + (Keys.SEMANTIC_DIGEST to "0".repeat(64)) else policy
            },
          )
        }
      SlotBaselineSqlite.updateFeatureTaskArtifacts(
        databasePath,
        workflowId,
        JsonCodec.mapToJsonString(row.toSnapshot().artifacts + family.entry(descriptor)),
      )
    } else {
      database.selfManagedWrite { unit ->
        val row = assertNotNull(unit.workflowStates.getFeatureTaskWorkflow(workflowId))
        assertTrue(
          unit.workflowStates.acquireFeatureTaskRuntimeWorker(
            FeatureTaskRuntimeWorkerOwnership(
              workflowId = workflowId, generation = 1, ownerToken = "owner-token-child-0001",
              hostIdentity = "host", bootIdentity = "boot", pid = 4242, processBirthToken = "birth",
              leaseState = FeatureTaskRuntimeWorkerLeaseState.ACTIVE, heartbeatAt = "2026-09-28T00:00:00Z",
              expiresAt = "2026-09-28T00:00:30Z", phaseId = "implement", phaseAttempt = 1,
            ),
            row.updatedAt,
          ),
        )
      }
    }
  }
}
