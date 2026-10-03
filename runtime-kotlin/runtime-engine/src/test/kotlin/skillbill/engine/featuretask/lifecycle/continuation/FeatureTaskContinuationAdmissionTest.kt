package skillbill.engine.featuretask.lifecycle.continuation

import skillbill.application.testHarnessClock
import skillbill.application.testWorkflowSnapshotValidator
import skillbill.contracts.JsonCodec
import skillbill.engine.featuretask.lifecycle.core.AcceptingFeatureTaskRuntimeWireArtifactValidator
import skillbill.engine.featuretask.lifecycle.core.ownership
import skillbill.engine.featuretask.lifecycle.execution.ExecutionPlanAdmissionFixture
import skillbill.engine.featuretask.model.continuation.FeatureTaskContinuationLookupResult
import skillbill.engine.featuretask.model.phase.AppendCheckpointIdentityArgs
import skillbill.engine.featuretask.model.phase.FeatureTaskRuntimePhaseStateRequest
import skillbill.engine.featuretask.phase.record.featureTaskRuntimePhaseRecorder
import skillbill.engine.featuretask.phaserun.phaseRunDatabase
import skillbill.engine.featuretask.runner.NoopWorkflowSnapshotValidator
import skillbill.engine.featuretask.runner.WORKFLOW_ID
import skillbill.error.featuretask.CorruptFeatureTaskRuntimeExecutionPlanError
import skillbill.error.featuretask.FeatureTaskRuntimeExecutionPlanAdmissionError
import skillbill.error.featuretask.IncompatibleFeatureTaskRuntimeExecutionPlanError
import skillbill.error.featuretask.MissingFeatureTaskRuntimeExecutionPlanError
import skillbill.error.featuretask.UnsupportedFeatureTaskRuntimeExecutionPlanError
import skillbill.error.shellcontent.InvalidFeatureTaskExecutionIdentitySchemaError
import skillbill.ports.db.DatabaseSessionFactory
import skillbill.ports.diagnostics.NoopRuntimeDiagnostics
import skillbill.ports.diagnostics.RuntimeDiagnostics
import skillbill.ports.workflow.model.WorkflowFamily
import skillbill.ports.workflow.model.WorkflowStateRecord
import skillbill.ports.workflow.toRecord
import skillbill.workflow.engine.WorkflowEngine
import skillbill.workflow.engine.model.DurableWorkflowArtifactFamily
import skillbill.workflow.model.FeatureTaskExecutionIdentity
import skillbill.workflow.model.FeatureTaskRouteScope
import skillbill.workflow.model.FeatureTaskWorkflowMode
import skillbill.workflow.model.WorkflowStatus
import java.nio.file.Files
import java.sql.DriverManager
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlin.error as failDiagnosticSink
import skillbill.contracts.workflow.identity.task.FeatureTaskRuntimeExecutionPlanKeys as Keys

class FeatureTaskContinuationAdmissionTest {
  @Test
  fun `claim returns persisted immutable plan and retains outputs attribution attempts and checkpoints`() {
    withFixture { fixture ->
      val before = fixture.row()
      val identity = fixture.database.read { it.workflowStates.getFeatureTaskExecutionIdentity(WORKFLOW_ID) }
      val admitted = assertNotNull(fixture.lookup.claim(fixture.candidate(), fixture.execution.inputs))

      assertContentEquals(fixture.execution.encoded, fixture.execution.codec.encode(admitted))
      assertFailsWith<UnsupportedOperationException> {
        (admitted.effectivePolicies as MutableList<*>).clear()
      }
      assertEquals(before.artifactsJson, fixture.row().artifactsJson)
      assertEquals(before.stepsJson, fixture.row().stepsJson)
      assertEquals(before.finishedAt, fixture.row().finishedAt)
      assertEquals(WorkflowStatus.RUNNING.wireValue, fixture.row().workflowStatus)
      fixture.database.read { assertEquals(identity, it.workflowStates.getFeatureTaskExecutionIdentity(WORKFLOW_ID)) }
      assertEquals(0, fixture.execution.launches)
      assertNull(fixture.lookup.claim(fixture.candidateFrom(before), fixture.execution.inputs))
    }
  }

  @Test
  fun `missing corrupt unsupported and incompatible descriptors refuse before claim and retain raw evidence`() {
    withFixture { fixture ->
      val original = fixture.row()
      val descriptor = fixture.execution.descriptor()
      val revised =
        descriptor + (
          Keys.DEFINITION to (
            requireNotNull(JsonCodec.anyToStringAnyMap(descriptor[Keys.DEFINITION])) + (Keys.SEMANTIC_REVISION to 99)
          )
        )
      val changed =
        fixture.execution.validator.read(
          fixture.execution.codec.encodeExecution(
            fixture.execution.plan,
            fixture.execution.inputs.copy(phaseTimeoutMillis = 1),
          ),
          "changed settings",
        )
      val cases =
        listOf(
          null to MissingFeatureTaskRuntimeExecutionPlanError::class,
          "unreadable-descriptor" to CorruptFeatureTaskRuntimeExecutionPlanError::class,
          (descriptor + (Keys.CONTRACT_VERSION to "9.0")) to UnsupportedFeatureTaskRuntimeExecutionPlanError::class,
          revised to UnsupportedFeatureTaskRuntimeExecutionPlanError::class,
          changed to IncompatibleFeatureTaskRuntimeExecutionPlanError::class,
        )
      cases.forEach { (value, expectedType) ->
        fixture.replaceDescriptor(original, value)
        val before = fixture.row()
        val error =
          assertFailsWith<FeatureTaskRuntimeExecutionPlanAdmissionError> {
            fixture.lookup.claim(fixture.candidate(), fixture.execution.inputs)
          }
        assertEquals(expectedType, error::class)
        assertEquals(before, fixture.row())
        assertEquals(0, fixture.execution.launches)
        assertIs<FeatureTaskContinuationLookupResult.Resumable>(fixture.lookup.lookup("SKILL-384", REPOSITORY))
      }
      assertEquals(cases.size, fixture.warnings.size)
      assertTrue(fixture.warnings.all { it.length < 256 })
      assertTrue(fixture.warnings.none { it.contains("retained-output") || it.contains("unreadable-descriptor") })
    }
  }

  @Test
  fun `descriptor changed after lookup with identical timestamp cannot acquire current semantics`() {
    withFixture { fixture ->
      val candidate = fixture.candidate()
      val original = fixture.row()
      val changed =
        fixture.execution.validator.read(
          fixture.execution.codec.encodeExecution(
            fixture.execution.plan,
            fixture.execution.inputs.copy(packSlug = "different-pack"),
          ),
          "changed routing",
        )
      fixture.replaceDescriptor(original, changed)
      val before = fixture.row()
      assertEquals(candidate.updatedAt, before.updatedAt)

      assertFailsWith<IncompatibleFeatureTaskRuntimeExecutionPlanError> {
        fixture.lookup.claim(candidate, fixture.execution.inputs)
      }

      assertEquals(before, fixture.row())
      assertEquals(0, fixture.execution.launches)
    }
  }

  @Test
  fun `repository identity changed after lookup cannot claim a different repository with the same timestamp`() {
    withFixture { fixture ->
      val candidate = fixture.candidate()
      DriverManager.getConnection("jdbc:sqlite:${fixture.database.resolveDbPath()}").use { connection ->
        connection.prepareStatement(
          "UPDATE feature_task_execution_identities SET repository_identity = ? WHERE workflow_id = ?",
        ).use { statement ->
          statement.setString(1, "repo-root-realpath-v1:/tmp/another-repository")
          statement.setString(2, WORKFLOW_ID)
          assertEquals(1, statement.executeUpdate())
        }
      }
      val before = fixture.row()
      assertEquals(candidate.updatedAt, before.updatedAt)
      val identity = fixture.database.read { it.workflowStates.getFeatureTaskExecutionIdentity(WORKFLOW_ID) }

      assertFailsWith<InvalidFeatureTaskExecutionIdentitySchemaError> {
        fixture.lookup.claim(candidate, fixture.execution.inputs)
      }

      assertEquals(before, fixture.row())
      fixture.database.read { assertEquals(identity, it.workflowStates.getFeatureTaskExecutionIdentity(WORKFLOW_ID)) }
      assertEquals(0, fixture.execution.launches)
      assertEquals(1, fixture.warnings.size)
      assertTrue(fixture.warnings.single().contains("invalid_route_identity"))
      assertFalse(fixture.warnings.single().contains("another-repository"))
    }
  }

  @Test
  fun `stale worker token or generation cannot pass continuation admission`() {
    withFixture { fixture ->
      val owner = ownership().copy(workflowId = WORKFLOW_ID)
      fixture.database.selfManagedWrite { unit ->
        val row = assertNotNull(unit.workflowStates.getFeatureTaskWorkflow(WORKFLOW_ID))
        assertTrue(unit.workflowStates.acquireFeatureTaskRuntimeWorker(owner, row.updatedAt))
      }
      fixture.database.transaction { unit ->
        val row = assertNotNull(unit.workflowStates.getFeatureTaskWorkflow(WORKFLOW_ID))
        unit.workflowStates.saveFeatureTaskWorkflow(
          row.copy(workflowStatus = "blocked"),
          FeatureTaskWorkflowMode.RUNTIME,
        )
      }
      val candidate = fixture.candidate()
      val before = fixture.row()
      listOf(
        null,
        owner.copy(ownerToken = "stale-token"),
        owner.copy(generation = owner.generation + 1),
      ).forEach { expected ->
        assertNull(fixture.lookup.claim(candidate, fixture.execution.inputs, expected))
        assertEquals(before, fixture.row())
        fixture.database.read {
          assertEquals(
            owner,
            it.workflowStates.getFeatureTaskRuntimeWorkerOwnership(WORKFLOW_ID),
          )
        }
      }
      assertEquals(0, fixture.execution.launches)
    }
  }

  @Test
  fun `cosmetic descriptor ordering remains compatible and failed diagnostics preserve primary refusal`() {
    withFixture { fixture ->
      val descriptor = fixture.execution.descriptor().entries.reversed().associate { it.toPair() }
      fixture.replaceDescriptor(fixture.row(), descriptor)
      val before = fixture.row()
      assertNotNull(fixture.lookup.claim(fixture.candidate(), fixture.execution.inputs))
      assertEquals(before.artifactsJson, fixture.row().artifactsJson)
    }
    withFixture { fixture ->
      fixture.replaceDescriptor(fixture.row(), null)
      val lookup =
        FeatureTaskContinuationLookupService(
          fixture.database,
          testWorkflowSnapshotValidator,
          fixture.execution.compatibility,
          object : RuntimeDiagnostics {
            override fun warning(
              message: String,
              error: Throwable?,
            ) = failDiagnosticSink("diagnostic sink unavailable")

            override fun error(
              message: String,
              error: Throwable?,
            ) = failDiagnosticSink("diagnostic sink unavailable")
          },
        )
      val before = fixture.row()
      assertFailsWith<MissingFeatureTaskRuntimeExecutionPlanError> {
        lookup.claim(fixture.candidate(), fixture.execution.inputs)
      }
      assertEquals(before, fixture.row())
      assertEquals(0, fixture.execution.launches)
    }
  }

  private fun withFixture(block: (Fixture) -> Unit) {
    val home = Files.createTempDirectory("continuation-admission")
    try {
      val database = phaseRunDatabase(home, testHarnessClock)
      val execution = ExecutionPlanAdmissionFixture()
      val record =
        WorkflowEngine().openRecord(WorkflowFamily.TASK_RUNTIME.definition, WORKFLOW_ID, "session", "plan")
          .toRecord().copy(
            issueKey = "SKILL-384",
            artifactsJson = JsonCodec.mapToJsonString(mapOf(family.entry(execution.descriptor()))),
          )
      seedContinuationIdentity(database, record)
      val recorder =
        featureTaskRuntimePhaseRecorder(
          database,
          NoopWorkflowSnapshotValidator,
          AcceptingFeatureTaskRuntimeWireArtifactValidator,
          AcceptingFeatureTaskRuntimeWireArtifactValidator,
          testHarnessClock,
          NoopRuntimeDiagnostics,
        )
      assertTrue(
        recorder.recordPhaseState(
          FeatureTaskRuntimePhaseStateRequest(
            workflowId = WORKFLOW_ID,
            phaseId = "plan",
            status = "completed",
            attemptCount = 3,
            resolvedAgentId = "original-producer",
            finished = true,
            outputArtifact = "retained-output",
          ),
        ),
      )
      assertTrue(
        recorder.appendCheckpointIdentity(
          AppendCheckpointIdentityArgs(
            workflowId = WORKFLOW_ID, issueKey = "SKILL-384", subtaskId = "2", branch = "feat/SKILL-384",
            phaseId = "review", loopId = null, generation = 0, parentSha = "a".repeat(40),
            ownedPaths = listOf("src/Changed.kt"), commitSha = "b".repeat(40),
          ),
        ),
      )
      database.transaction { unit ->
        val stored = assertNotNull(unit.workflowStates.getFeatureTaskWorkflow(WORKFLOW_ID))
        unit.workflowStates.saveFeatureTaskWorkflow(
          stored.copy(workflowStatus = "blocked"),
          FeatureTaskWorkflowMode.RUNTIME,
        )
      }
      val storedPlan = assertNotNull(recorder.loadPhaseRecords(WORKFLOW_ID)?.get("plan"))
      assertEquals(3, storedPlan.attemptCount)
      assertEquals("retained-output", storedPlan.outputArtifact)
      assertEquals("original-producer", storedPlan.resolvedAgentId)
      assertFalse(recorder.loadCheckpointIdentities(WORKFLOW_ID).isNullOrEmpty())
      block(Fixture(database, execution))
    } finally {
      home.toFile().deleteRecursively()
    }
  }

  private class Fixture(val database: DatabaseSessionFactory, val execution: ExecutionPlanAdmissionFixture) {
    val warnings = mutableListOf<String>()
    val lookup =
      FeatureTaskContinuationLookupService(
        database,
        testWorkflowSnapshotValidator,
        execution.compatibility,
        object : RuntimeDiagnostics {
          override fun warning(
            message: String,
            error: Throwable?,
          ) {
            warnings.add(message)
          }

          override fun error(
            message: String,
            error: Throwable?,
          ) {
            warnings.add(message)
          }
        },
      )

    fun row(): WorkflowStateRecord =
      database.read {
        assertNotNull(
          it.workflowStates.getFeatureTaskWorkflow(WORKFLOW_ID),
        )
      }

    fun candidate() =
      assertIs<FeatureTaskContinuationLookupResult.Resumable>(
        lookup.lookup("SKILL-384", REPOSITORY),
      ).candidate

    fun candidateFrom(row: WorkflowStateRecord) =
      assertIs<FeatureTaskContinuationLookupResult.AlreadyRunning>(
        lookup.lookup("SKILL-384", REPOSITORY),
      ).candidate.copy(
        status = row.workflowStatus,
        currentStep = row.currentStepId,
        updatedAt = row.updatedAt,
      )

    fun replaceDescriptor(
      original: WorkflowStateRecord,
      descriptor: Any?,
    ) {
      val artifacts =
        requireNotNull(
          JsonCodec.anyToStringAnyMap(JsonCodec.parseValue(original.artifactsJson)),
        ).toMutableMap()
      if (descriptor == null) family.removeFrom(artifacts) else family.putInto(artifacts, descriptor)
      DriverManager.getConnection("jdbc:sqlite:${database.resolveDbPath()}").use { connection ->
        connection.prepareStatement(
          "UPDATE feature_task_workflows SET artifacts_json = ? WHERE workflow_id = ?",
        ).use { statement ->
          statement.setString(1, JsonCodec.mapToJsonString(artifacts))
          statement.setString(2, WORKFLOW_ID)
          assertEquals(1, statement.executeUpdate())
        }
      }
    }
  }

  private companion object {
    const val WORKFLOW_ID = "wftr-admission"
    const val REPOSITORY = "repo-root-realpath-v1:/tmp/admission-repository"
    const val SPEC = ".feature-specs/SKILL-384/spec.md"
    val family = DurableWorkflowArtifactFamily.FEATURE_TASK_RUNTIME_EXECUTION_PLAN
  }

  private fun seedContinuationIdentity(
    database: DatabaseSessionFactory,
    record: WorkflowStateRecord,
  ) {
    database.transaction { unit ->
      unit.workflowStates.saveFeatureTaskWorkflow(record, FeatureTaskWorkflowMode.RUNTIME)
      unit.workflowStates.saveFeatureTaskExecutionIdentity(
        FeatureTaskExecutionIdentity(
          WORKFLOW_ID,
          "SKILL-384",
          REPOSITORY,
          SPEC,
          FeatureTaskWorkflowMode.RUNTIME,
          FeatureTaskRouteScope.STANDALONE,
        ),
      )
    }
  }
}
