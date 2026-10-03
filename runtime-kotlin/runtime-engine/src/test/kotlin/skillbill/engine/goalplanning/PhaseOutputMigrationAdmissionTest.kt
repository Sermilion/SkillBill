package skillbill.engine.goalplanning

import skillbill.contracts.JsonCodec
import skillbill.contracts.SharedPayloadKeys
import skillbill.engine.featuretask.lifecycle.execution.ExecutionPlanAdmissionFixture
import skillbill.error.core.SkillBillRuntimeException
import skillbill.error.featuretask.FeatureTaskRuntimeMigrationFailureCode
import skillbill.infrastructure.sqlite.sqliteSessionFactoryForTests
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

class PhaseOutputMigrationAdmissionTest {
  @Test
  fun `admission converts only historical output bytes and leaves attempts and outer evidence unchanged on repeat`() {
    val home = Files.createTempDirectory("phase-output-migration")
    val database = sqliteSessionFactoryForTests(home, home.resolve("state.db").toString(), emptyMap())
    val execution = ExecutionPlanAdmissionFixture(database = database)
    val source = phaseOutput("0.6")
    val record =
      FeatureTaskRuntimePhaseRecord(
        phaseId = "preplan",
        status = WorkflowStepStatus.COMPLETED,
        startedAt = Instant.EPOCH,
        attemptCount = 4,
        outputArtifact = source,
        resolvedAgentId = "codex",
        blockedReason = "retained diagnostic",
      )
        .asWorkflowArtifactEntry().toMap()
    database.transaction { unit ->
      execution.seed(unit.workflowStates, "wftr-output-migration")
      val child = requireNotNull(unit.workflowStates.get(WorkflowFamily.TASK_RUNTIME, "wftr-output-migration"))
      unit.workflowStates.save(
        WorkflowFamily.TASK_RUNTIME,
        child.copy(
          artifacts =
            DurableWorkflowArtifacts.fromMap(
              child.artifacts +
                DurableWorkflowArtifactFamily.FEATURE_TASK_RUNTIME_PHASE_RECORDS.entry(
                  mapOf("preplan" to record),
                ),
            ),
        ),
      )
    }
    database.transaction {
      execution.admission.admit(it, "wftr-output-migration", execution.inputs)
    }
    val saved = database.read { requireNotNull(it.workflowStates.getFeatureTaskWorkflow("wftr-output-migration")) }
    val artifacts = requireNotNull(JsonCodec.anyToStringAnyMap(JsonCodec.parseValue(saved.artifactsJson)))
    val records = DurableWorkflowArtifactFamily.FEATURE_TASK_RUNTIME_PHASE_RECORDS.value(artifacts) as Map<*, *>
    val migrated = records["preplan"] as Map<*, *>
    assertEquals<Any>(record - SharedPayloadKeys.OUTPUT_ARTIFACT, migrated - SharedPayloadKeys.OUTPUT_ARTIFACT)
    assertEquals(
      "0.7",
      JsonCodec.parseObjectOrNull(migrated[SharedPayloadKeys.OUTPUT_ARTIFACT] as String)
        ?.get(SharedPayloadKeys.CONTRACT_VERSION)?.let(JsonCodec::jsonElementToValue),
    )
    database.transaction {
      execution.admission.admit(it, "wftr-output-migration", execution.inputs)
    }
    assertEquals(saved, database.read { it.workflowStates.getFeatureTaskWorkflow("wftr-output-migration") })
  }

  @Test
  fun `unsupported corrupt and nonconvertible phase outputs refuse without publishing`() {
    val sources =
      listOf(
        phaseOutput("0.5") to FeatureTaskRuntimeMigrationFailureCode.SOURCE_UNSUPPORTED,
        phaseOutput(
          "0.6",
        ).replace("\"completed\"", "\"broken\"") to FeatureTaskRuntimeMigrationFailureCode.SOURCE_CORRUPT,
        phaseOutput(
          "0.6",
        ).replace("\"completed\"", "\"blocked\"") to FeatureTaskRuntimeMigrationFailureCode.TARGET_NON_CONVERTIBLE,
      )
    sources.forEach { (payload, expected) ->
      val home = Files.createTempDirectory("refused-phase-migration")
      val database = sqliteSessionFactoryForTests(home, home.resolve("state.db").toString(), emptyMap())
      val execution = ExecutionPlanAdmissionFixture(database = database)
      database.transaction { unit ->
        execution.seed(unit.workflowStates, "wftr-refused-migration")
        val child = requireNotNull(unit.workflowStates.get(WorkflowFamily.TASK_RUNTIME, "wftr-refused-migration"))
        val record =
          FeatureTaskRuntimePhaseRecord(
            phaseId = "preplan",
            status = WorkflowStepStatus.COMPLETED,
            startedAt = Instant.EPOCH,
            attemptCount = 1,
            outputArtifact = payload,
            resolvedAgentId = "codex",
          ).asWorkflowArtifactEntry()
        unit.workflowStates.save(
          WorkflowFamily.TASK_RUNTIME,
          child.copy(
            artifacts =
              DurableWorkflowArtifacts.fromMap(
                child.artifacts +
                  DurableWorkflowArtifactFamily.FEATURE_TASK_RUNTIME_PHASE_RECORDS.entry(
                    mapOf("preplan" to record),
                  ),
              ),
          ),
        )
      }
      val before = database.read { it.workflowStates.getFeatureTaskWorkflow("wftr-refused-migration") }
      val error =
        assertFailsWith<SkillBillRuntimeException> {
          database.transaction {
            execution.admission.admit(it, "wftr-refused-migration", execution.inputs)
          }
        }
      assertEquals(expected, error.code)
      assertEquals(before, database.read { it.workflowStates.getFeatureTaskWorkflow("wftr-refused-migration") })
    }
  }
}

private fun phaseOutput(version: String): String =
  """{"contract_version":"$version","phase_id":"preplan","status":"completed","summary":"preplan",
    "produced_outputs":{"value":"evidence"}}"""
