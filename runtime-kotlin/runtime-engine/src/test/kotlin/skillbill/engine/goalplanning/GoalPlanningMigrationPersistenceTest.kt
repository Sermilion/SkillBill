package skillbill.engine.goalplanning

import skillbill.contracts.JsonCodec
import skillbill.contracts.workflow.goal.GOAL_PLANNING_PREPARATION_SCHEMA_ID
import skillbill.engine.featuretask.lifecycle.execution.ExecutionPlanAdmissionFixture
import skillbill.engine.goalrunner.persist.planningMigrationForTest
import skillbill.engine.goalrunner.planning.context.GoalPlanningSharedContextPacket
import skillbill.engine.goalrunner.planning.context.GoalPlanningSharedContextPacketPayloadKeys
import skillbill.engine.goalrunner.planning.sweep.GoalPlanningSweepConstants
import skillbill.engine.migration.RuntimeMigrationReceipt
import skillbill.error.core.SkillBillRuntimeException
import skillbill.infrastructure.contracts.workflow.decomposition.DecompositionManifestSchemaValidator
import skillbill.infrastructure.contracts.workflow.featuretask.ContractFeatureTaskRuntimePhaseOutputMigration
import skillbill.infrastructure.sqlite.ensureTestDatabase
import skillbill.infrastructure.sqlite.sqliteSessionFactoryForTests
import skillbill.ports.goalrunner.model.GoalPlanningContractProvenance
import skillbill.ports.goalrunner.model.GoalPlanningIdentity
import skillbill.ports.goalrunner.model.GoalSubtaskPlanCheckpoint
import skillbill.ports.goalrunner.model.SharedGoalPreplanCheckpoint
import skillbill.ports.goalrunner.planning.model.GoalPlanningContext
import skillbill.ports.taskruntime.FeatureTaskRuntimePhaseOutputMigration
import skillbill.ports.taskruntime.model.FeatureTaskRuntimePhaseOutputMigrationResult
import skillbill.ports.workflow.decomposition.encodeManifestWireMap
import skillbill.ports.workflow.model.WorkflowFamily
import skillbill.ports.workflow.toRecord
import skillbill.review.context.model.execution.CodeReviewExecutionMode
import skillbill.text.sha256HexUtf8
import skillbill.workflow.decomposition.model.CurrentSubtaskIntent
import skillbill.workflow.decomposition.model.DecompositionManifest
import skillbill.workflow.decomposition.model.DecompositionSubtask
import skillbill.workflow.engine.WorkflowEngine
import skillbill.workflow.engine.model.DurableWorkflowArtifactFamily
import skillbill.workflow.engine.model.DurableWorkflowArtifacts
import skillbill.workflow.model.WorkflowStepStatus
import skillbill.workflow.taskruntime.artifact.asWorkflowArtifactEntry
import skillbill.workflow.taskruntime.model.persistence.FeatureTaskRuntimeGoalContinuationArtifact
import skillbill.workflow.taskruntime.model.persistence.FeatureTaskRuntimeGoalPlanningImport
import skillbill.workflow.taskruntime.model.phase.FeatureTaskRuntimePhaseExecutionOrigin
import skillbill.workflow.taskruntime.model.phase.FeatureTaskRuntimePhaseLedgerAction
import skillbill.workflow.taskruntime.model.phase.FeatureTaskRuntimePhaseLedgerEntry
import skillbill.workflow.taskruntime.model.phase.FeatureTaskRuntimePhaseRecord
import skillbill.workflow.taskruntime.model.skeleton.SkeletonDefinition
import java.nio.file.Files
import java.time.Instant
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNotEquals
import kotlin.test.assertTrue

class GoalPlanningMigrationPersistenceTest {
  @Test
  fun `historical coupled planning migrates once and retains completed commits and import history`() {
    val fixture = MigrationFixture()
    val beforeParent = fixture.parent()
    val beforeChild = fixture.child()
    val beforeShared = fixture.shared()
    val beforePlan = fixture.plan()
    assertTrue(fixture.migrate())
    assertEquals(beforeParent, fixture.parent())
    val migrated = fixture.child()
    assertEquals(beforeChild.copy(artifactsJson = migrated.artifactsJson), migrated)
    assertEquals(beforeChild.stepsJson, migrated.stepsJson)
    assertEquals(beforeChild.workflowStatus, migrated.workflowStatus)
    assertEquals(beforeChild.currentStepId, migrated.currentStepId)
    val beforeArtifacts = JsonCodec.anyToStringAnyMap(JsonCodec.parseValue(beforeChild.artifactsJson)).orEmpty()
    val afterArtifacts = JsonCodec.anyToStringAnyMap(JsonCodec.parseValue(migrated.artifactsJson)).orEmpty()
    assertEquals(
      beforeArtifacts["feature_task_runtime_phase_ledger"],
      afterArtifacts["feature_task_runtime_phase_ledger"],
    )
    assertEquals("0.7", fixture.shared().provenance.phaseOutputContractVersion)
    assertNotEquals(beforeShared.payloadSha256, fixture.shared().payloadSha256)
    assertEquals(beforeShared.createdAt, fixture.shared().createdAt)
    assertEquals(beforePlan.createdAt, fixture.plan().createdAt)
    assertFalse(fixture.migrate())
    assertEquals(migrated, fixture.child())
    assertEquals(beforeParent, fixture.parent())
  }

  @Test
  fun `failure publishing sibling plan rolls back shared bytes and all child imports before repeat resume`() {
    val fixture = MigrationFixture()
    val beforeShared = fixture.shared()
    val beforePlan = fixture.plan()
    val beforeChild = fixture.child()
    fixture.sql(
      "CREATE TRIGGER reject_migration BEFORE UPDATE ON goal_subtask_plans " +
        "BEGIN SELECT RAISE(ABORT, 'injected'); END",
    )
    assertFailsWith<Exception> { fixture.migrate() }
    assertEquals(beforeShared, fixture.shared())
    assertEquals(beforePlan, fixture.plan())
    assertEquals(beforeChild, fixture.child())
    fixture.sql("DROP TRIGGER reject_migration")
    assertTrue(fixture.migrate())
  }

  @Test
  fun `interruption after publication rolls back every row and a new admission can resume`() {
    val fixture = MigrationFixture()
    val shared = fixture.shared()
    val plan = fixture.plan()
    val child = fixture.child()
    assertFailsWith<InterruptedException> { fixture.migrate(interruptBeforeCommit = true) }
    assertEquals(shared, fixture.shared())
    assertEquals(plan, fixture.plan())
    assertEquals(child, fixture.child())
    assertTrue(fixture.migrate())
  }

  @Test
  fun `invalid converted target refuses before publishing any historical bytes`() {
    val fixture = MigrationFixture()
    val shared = fixture.shared()
    val plan = fixture.plan()
    val child = fixture.child()
    val broken =
      object : FeatureTaskRuntimePhaseOutputMigration {
        override fun migrate(payload: String): FeatureTaskRuntimePhaseOutputMigrationResult =
          FeatureTaskRuntimePhaseOutputMigrationResult.Migrated("{}", "0.6", "0.7")
      }
    assertFailsWith<SkillBillRuntimeException> { fixture.migrate(outputs = broken) }
    assertEquals(shared, fixture.shared())
    assertEquals(plan, fixture.plan())
    assertEquals(child, fixture.child())
  }

  @Test
  fun `corrupt unsupported and conflicting import sources leave historical rows untouched`() {
    listOf(
      "UPDATE goal_shared_preplans SET phase_output_contract_version = '0.5'",
      "UPDATE goal_shared_preplans SET payload_sha256 = '${"a".repeat(64)}'",
      "UPDATE goal_subtask_plans SET phase_output_contract_version = '0.5'",
      "UPDATE feature_task_workflows SET artifacts_json = " +
        "replace(artifacts_json, 'imported_goal_planning', 'unsafe')",
    ).forEach { mutation ->
      val fixture = MigrationFixture()
      fixture.sql(mutation)
      val beforeShared = fixture.shared()
      val beforePlan = fixture.plan()
      val beforeChild = fixture.child()
      assertFailsWith<SkillBillRuntimeException> { fixture.migrate() }
      assertEquals(beforeShared, fixture.shared())
      assertEquals(beforePlan, fixture.plan())
      assertEquals(beforeChild, fixture.child())
    }
  }

  @Test
  fun `current planning import admits when the parent manifest stored an absolute subtask spec path`() {
    val fixture =
      MigrationFixture(
        historicalPhaseOutput = false,
        launchedSubtaskSpecPath = "/tmp/admission-repository/.feature-specs/SKILL-384/spec.md",
      )
    val beforeShared = fixture.shared()
    val beforePlan = fixture.plan()
    val beforeChild = fixture.child()
    assertFalse(fixture.migrate(requirePreparation = true))
    assertEquals(beforeShared, fixture.shared())
    assertEquals(beforePlan, fixture.plan())
    assertEquals(beforeChild, fixture.child())
  }
}

private const val RELATIVE_SPEC = ".feature-specs/SKILL-384/spec.md"

private class MigrationFixture(
  historicalPhaseOutput: Boolean = true,
  launchedSubtaskSpecPath: String = RELATIVE_SPEC,
) {
  private val home = Files.createTempDirectory("durable-planning-migration")
  private val path = home.resolve("state.db")
  val database = sqliteSessionFactoryForTests(home, path.toString(), emptyMap())
  private val identity =
    GoalPlanningIdentity(
      "wftr-migration-parent",
      "SKILL-384",
      "repo-root-realpath-v1:/tmp/admission-repository",
    )
  private val spec = RELATIVE_SPEC
  private val manifest =
    DecompositionManifest(
      issueKey = "SKILL-384",
      featureName = "migration",
      parentSpecPath = spec,
      baseBranch = "base",
      featureBranch = "feat/SKILL-384",
      currentSubtaskIntent = CurrentSubtaskIntent(2, "blocked"),
      status = "blocked",
      subtasks =
        listOf(
          DecompositionSubtask(
            1,
            "completed",
            specPath = launchedSubtaskSpecPath,
            status = "complete",
            commitSha = "f".repeat(40),
            workflowId = "wftr-migration-child",
          ),
          DecompositionSubtask(2, "unfinished", ".feature-specs/SKILL-384/spec_subtask_2.md", status = "blocked"),
          DecompositionSubtask(3, "skipped", ".feature-specs/SKILL-384/spec_subtask_3.md", status = "skipped"),
        ),
    )
  private val provenance =
    GoalPlanningContractProvenance(
      sha256HexUtf8("requirements"),
      "b".repeat(64),
      GOAL_PLANNING_PREPARATION_SCHEMA_ID,
    )
  private val packet =
    linkedMapOf<String, Any?>(
      "packet_version" to GoalPlanningSharedContextPacket.VERSION,
      "repository_identity" to identity.repositoryIdentity,
      "normalized_issue_key" to identity.normalizedIssueKey,
      "parent_spec_path" to spec, "parent_spec" to "requirements", "decomposition_manifest" to "manifest",
      "boundary_memory" to GoalPlanningSharedContextPacket.catalog(GoalPlanningContext(emptyList(), false, "")),
      "validation_guidance" to "",
      "ordered_subtasks" to
        GoalPlanningSharedContextPacket.orderedSubtasks(
          manifest.subtasks,
        ),
    ).let {
      it + (GoalPlanningSharedContextPacketPayloadKeys.INTEGRITY_SHA256 to GoalPlanningSharedContextPacket.digest(it))
    }
  private val currentPreplan =
    output(
      "preplan",
      "0.7",
      mapOf(
        "value" to "preplan",
        GoalPlanningSweepConstants.SHARED_CONTEXT_FIELD to packet,
      ),
    )
  private val currentPlan = output("plan", "0.7", mapOf("value" to "plan"))
  private val historicalPreplan =
    output(
      "preplan",
      "0.6",
      mapOf(
        "value" to "preplan",
        GoalPlanningSweepConstants.SHARED_CONTEXT_FIELD to packet,
      ),
    )
  private val historicalPlan = output("plan", "0.6", mapOf("value" to "plan"))

  init {
    database.transaction { unit ->
      val parent =
        WorkflowEngine().openRecord(
          WorkflowFamily.TASK_RUNTIME.definition,
          identity.parentGoalWorkflowId,
          "migration-session",
          "plan",
        )
      unit.workflowStates.saveRecord(
        WorkflowFamily.TASK_RUNTIME,
        parent.copy(
          artifacts =
            DurableWorkflowArtifacts.fromMap(
              mapOf(
                DurableWorkflowArtifactFamily.DECOMPOSITION_RUNTIME.entry(
                  DecompositionManifestSchemaValidator().encodeManifestWireMap(manifest, "migration"),
                ),
              ),
            ),
        ).toRecord()
          .copy(issueKey = identity.normalizedIssueKey),
      )
      ExecutionPlanAdmissionFixture(SkeletonDefinition.GOAL_CHILD, database = database).seed(
        unit.workflowStates,
        "wftr-migration-child",
      )
    }
    database.selfManagedWrite { unit ->
      unit.goalPlanningPreparations.checkpointSharedPreplan(
        SharedGoalPreplanCheckpoint(
          identity = identity,
          provenance = provenance,
          payloadSha256 = sha256HexUtf8(currentPreplan),
          preplanPayload = currentPreplan,
        ),
      )
      unit.goalPlanningPreparations.checkpointSubtaskPlan(
        GoalSubtaskPlanCheckpoint(
          identity,
          1,
          0,
          spec,
          "c".repeat(64),
          provenance = provenance,
          payloadSha256 = sha256HexUtf8(currentPlan),
          planPayload = currentPlan,
        ),
      )
    }
    if (historicalPhaseOutput) {
      ensureTestDatabase(path).use { connection ->
        connection.prepareStatement(
          "UPDATE goal_shared_preplans SET phase_output_contract_version = '0.6', " +
            "preplan_payload_json = ?, payload_sha256 = ?",
        ).use {
          it.setString(1, historicalPreplan)
          it.setString(2, sha256HexUtf8(historicalPreplan))
          it.executeUpdate()
        }
        connection.prepareStatement(
          "UPDATE goal_subtask_plans SET phase_output_contract_version = '0.6', " +
            "plan_payload_json = ?, payload_sha256 = ?",
        ).use {
          it.setString(1, historicalPlan)
          it.setString(2, sha256HexUtf8(historicalPlan))
          it.executeUpdate()
        }
      }
    }
    val importedPreplan = if (historicalPhaseOutput) historicalPreplan else currentPreplan
    val importedPlan = if (historicalPhaseOutput) historicalPlan else currentPlan
    val importedPhaseOutputVersion = if (historicalPhaseOutput) "0.6" else "0.7"
    database.transaction { unit ->
      val child = requireNotNull(unit.workflowStates.get(WorkflowFamily.TASK_RUNTIME, "wftr-migration-child"))
      val records =
        listOf("preplan" to importedPreplan, "plan" to importedPlan).associate { (phase, payload) ->
          phase to
            FeatureTaskRuntimePhaseRecord(
              phaseId = phase,
              status = "completed",
              attemptCount = 1,
              outputArtifact = payload,
              resolvedAgentId = "goal-planning-import",
              startedAt = Instant.EPOCH.toString(),
              finishedAt = Instant.EPOCH.toString(),
              executionOrigin = FeatureTaskRuntimePhaseExecutionOrigin.GOAL_PLANNING_HYDRATED,
            )
              .asWorkflowArtifactEntry()
        }
      val imported =
        FeatureTaskRuntimeGoalPlanningImport(
          identity.parentGoalWorkflowId, identity.normalizedIssueKey,
          identity.repositoryIdentity, provenance.parentSpecHash, provenance.decompositionManifestHash,
          provenance.planningContractId, provenance.planningContractVersion, provenance.phaseOutputContractId,
          importedPhaseOutputVersion,
          1, 0, spec, "c".repeat(64), sha256HexUtf8(importedPreplan), sha256HexUtf8(importedPlan),
        ).asWorkflowArtifactEntry()
      val ledger =
        listOf("preplan", "plan").mapIndexed { index, phase ->
          FeatureTaskRuntimePhaseLedgerEntry(
            FeatureTaskRuntimePhaseLedgerAction.COMPLETE,
            index,
            Instant.EPOCH,
            phase,
            1,
            executionOrigin = FeatureTaskRuntimePhaseExecutionOrigin.GOAL_PLANNING_HYDRATED,
          ).asWorkflowArtifactEntry()
        }
      unit.workflowStates.save(
        WorkflowFamily.TASK_RUNTIME,
        child.copy(
          steps = child.steps.map { if (it.stepId in records) it.copy(status = WorkflowStepStatus.COMPLETED) else it },
          artifacts =
            DurableWorkflowArtifacts.fromMap(
              child.artifacts +
                mapOf(
                  DurableWorkflowArtifactFamily.FEATURE_TASK_RUNTIME_PHASE_RECORDS.entry(records),
                  DurableWorkflowArtifactFamily.FEATURE_TASK_RUNTIME_PHASE_LEDGER.entry(ledger),
                  DurableWorkflowArtifactFamily.FEATURE_TASK_RUNTIME_GOAL_PLANNING_IMPORT.entry(imported),
                  DurableWorkflowArtifactFamily.FEATURE_TASK_RUNTIME_GOAL_CONTINUATION.entry(
                    FeatureTaskRuntimeGoalContinuationArtifact(
                      issueKey = identity.normalizedIssueKey,
                      subtaskId = 1,
                      suppressPr = true,
                      goalBranch = "feat/SKILL-384",
                      parentWorkflowId = identity.parentGoalWorkflowId,
                      codeReviewMode = CodeReviewExecutionMode.INLINE,
                    ).asWorkflowArtifactEntry(),
                  ),
                ),
            ),
        ),
      )
    }
  }

  fun migrate(
    interruptBeforeCommit: Boolean = false,
    outputs: FeatureTaskRuntimePhaseOutputMigration = ContractFeatureTaskRuntimePhaseOutputMigration(),
    requirePreparation: Boolean = false,
  ): Boolean =
    database.transaction {
      val changed =
        planningMigrationForTest(outputs = outputs).migrate(
          it,
          identity.parentGoalWorkflowId,
          identity.repositoryIdentity,
          identity.normalizedIssueKey,
          requirePreparation = requirePreparation,
        )
      if (interruptBeforeCommit) throw InterruptedException("injected before commit")
      changed.result == RuntimeMigrationReceipt.Result.CONVERTED
    }

  fun parent() =
    database.read {
      requireNotNull(
        it.workflowStates.getFeatureTaskWorkflow(identity.parentGoalWorkflowId),
      )
    }

  fun child() = database.read { requireNotNull(it.workflowStates.getFeatureTaskWorkflow("wftr-migration-child")) }

  fun shared() = database.read { requireNotNull(it.goalPlanningPreparations.findSharedPreplan(identity)) }

  fun plan() = database.read { requireNotNull(it.goalPlanningPreparations.findSubtaskPlan(identity, 1, spec)) }

  fun sql(statement: String) {
    ensureTestDatabase(path).use { it.createStatement().use { s -> s.execute(statement) } }
  }
}

private fun output(
  phase: String,
  version: String,
  produced: Map<String, Any?>,
): String =
  JsonCodec.mapToJsonString(
    mapOf(
      "contract_version" to version,
      "phase_id" to phase,
      "status" to "completed",
      "summary" to "planning",
      "produced_outputs" to produced,
    ),
  )
