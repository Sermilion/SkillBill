package skillbill.engine.featuretask.lifecycle.execution

import skillbill.contracts.JsonCodec
import skillbill.contracts.SharedPayloadKeys
import skillbill.error.core.SkillBillRuntimeException
import skillbill.error.featuretask.FeatureTaskRuntimeMigrationFailureCode
import skillbill.infrastructure.sqlite.sqliteSessionFactoryForTests
import skillbill.ports.featuretask.model.FeatureTaskRuntimeWorkerLeaseState
import skillbill.ports.featuretask.model.FeatureTaskRuntimeWorkerOwnership
import skillbill.ports.taskruntime.FeatureTaskRuntimeWorkerSupervisor
import skillbill.ports.taskruntime.NoopFeatureTaskRuntimeWorkerSupervisor
import skillbill.ports.taskruntime.model.FeatureTaskRuntimeProcessInspection
import skillbill.ports.workflow.model.WorkflowFamily
import skillbill.workflow.engine.model.DurableWorkflowArtifactFamily
import skillbill.workflow.engine.model.DurableWorkflowArtifacts
import skillbill.workflow.model.WorkflowStepStatus
import skillbill.workflow.taskruntime.artifact.asWorkflowArtifactEntry
import skillbill.workflow.taskruntime.model.phase.FeatureTaskRuntimePhaseRecord
import java.nio.file.Files
import java.time.Instant
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

class PhaseOutputMigrationWorkerOwnershipTest {
  @Test
  fun `historical output conversion waits for the recorded worker to stop without changing its lease`() {
    val home = Files.createTempDirectory("owned-phase-output-migration")
    val database = sqliteSessionFactoryForTests(home, home.resolve("state.db").toString(), emptyMap())
    var inspection: FeatureTaskRuntimeProcessInspection = FeatureTaskRuntimeProcessInspection.ExactLive
    val supervisor =
      object : FeatureTaskRuntimeWorkerSupervisor by NoopFeatureTaskRuntimeWorkerSupervisor {
        override fun inspect(ownership: FeatureTaskRuntimeWorkerOwnership) = inspection
      }
    val execution = ExecutionPlanAdmissionFixture(database = database, supervisor = supervisor)
    val workflowId = "wftr-owned-migration"
    val ownership = migrationWorker(workflowId)
    database.transaction { unit ->
      execution.seed(unit.workflowStates, workflowId)
      val child = requireNotNull(unit.workflowStates.get(WorkflowFamily.TASK_RUNTIME, workflowId))
      val record =
        FeatureTaskRuntimePhaseRecord(
          phaseId = "preplan",
          status = WorkflowStepStatus.COMPLETED,
          attemptCount = 1,
          startedAt = Instant.EPOCH,
          resolvedAgentId = "codex",
          outputArtifact = phaseOutput("0.6"),
        ).asWorkflowArtifactEntry()
      unit.workflowStates.save(
        WorkflowFamily.TASK_RUNTIME,
        child.copy(
          artifacts =
            DurableWorkflowArtifacts.fromMap(
              child.artifacts +
                DurableWorkflowArtifactFamily.FEATURE_TASK_RUNTIME_PHASE_RECORDS.entry(mapOf("preplan" to record)),
            ),
        ),
      )
      val saved = requireNotNull(unit.workflowStates.getFeatureTaskWorkflow(workflowId))
      assertTrue(unit.workflowStates.acquireFeatureTaskRuntimeWorker(ownership, saved.updatedAt))
    }
    val before = database.read { it.workflowStates.getFeatureTaskWorkflow(workflowId) }
    val error =
      assertFailsWith<SkillBillRuntimeException> {
        database.transaction { execution.admission.admit(it, workflowId, execution.inputs) }
      }
    assertEquals(FeatureTaskRuntimeMigrationFailureCode.STALE_SOURCE, error.code, error.message)
    assertEquals(before, database.read { it.workflowStates.getFeatureTaskWorkflow(workflowId) })
    assertEquals(ownership, database.read { it.workflowStates.getFeatureTaskRuntimeWorkerOwnership(workflowId) })
    inspection = FeatureTaskRuntimeProcessInspection.NotRunning
    database.transaction { execution.admission.admit(it, workflowId, execution.inputs) }
    val saved = requireNotNull(database.read { it.workflowStates.getFeatureTaskWorkflow(workflowId) })
    val artifacts = requireNotNull(JsonCodec.anyToStringAnyMap(JsonCodec.parseValue(saved.artifactsJson)))
    val records = DurableWorkflowArtifactFamily.FEATURE_TASK_RUNTIME_PHASE_RECORDS.value(artifacts) as Map<*, *>
    val migrated = records["preplan"] as Map<*, *>
    assertEquals(
      "0.7",
      JsonCodec.parseObjectOrNull(migrated[SharedPayloadKeys.OUTPUT_ARTIFACT] as String)
        ?.get(SharedPayloadKeys.CONTRACT_VERSION)?.let(JsonCodec::jsonElementToValue),
    )
    assertEquals(ownership, database.read { it.workflowStates.getFeatureTaskRuntimeWorkerOwnership(workflowId) })
  }
}

private fun migrationWorker(workflowId: String): FeatureTaskRuntimeWorkerOwnership =
  FeatureTaskRuntimeWorkerOwnership(
    workflowId = workflowId,
    generation = 1,
    ownerToken = "migration-owner-token",
    hostIdentity = "migration-host",
    bootIdentity = "migration-boot",
    pid = 7,
    processBirthToken = "migration-process",
    leaseState = FeatureTaskRuntimeWorkerLeaseState.ACTIVE,
    heartbeatAt = Instant.EPOCH.toString(),
    expiresAt = Instant.EPOCH.plusSeconds(30).toString(),
    phaseId = "preplan",
    phaseAttempt = 1,
  )

private fun phaseOutput(version: String): String =
  """{"contract_version":"$version","phase_id":"preplan","status":"completed","summary":"preplan",
    "produced_outputs":{"value":"evidence"}}"""
