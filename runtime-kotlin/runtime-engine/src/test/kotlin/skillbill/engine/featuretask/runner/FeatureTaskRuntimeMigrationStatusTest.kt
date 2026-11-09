package skillbill.engine.featuretask.runner

import skillbill.engine.featuretask.model.core.FeatureTaskRuntimeStatusRequest
import skillbill.engine.featuretask.phase.record.openTestWorkflow
import skillbill.error.core.SkillBillRuntimeException
import skillbill.error.migration.AcceptanceCriterionMigrationFailureCode
import skillbill.ports.db.RetainedWorkflowReader
import skillbill.ports.db.RuntimeDatabaseReadiness
import skillbill.ports.db.model.RetainedWorkflowRecord
import skillbill.ports.db.model.RetainedWorkflowSource
import skillbill.ports.db.model.RuntimeDatabaseReadinessResult
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class FeatureTaskRuntimeMigrationStatusTest {
  private val workflowId = "wftr-20260603-status-0001"

  @Test
  fun `status prepares before decoding and nested prepared reads never enter readiness`() {
    lateinit var harness: StatusHarness
    var preparations = 0
    harness =
      statusHarness(
        databaseReadiness =
          object : RuntimeDatabaseReadiness {
            override fun initializeRead(): RuntimeDatabaseReadinessResult = prepareExisting()

            override fun observeRead(): RuntimeDatabaseReadinessResult = RuntimeDatabaseReadinessResult.Ready(true)

            override fun prepareExisting(): RuntimeDatabaseReadinessResult {
              preparations += 1
              harness.recorder.openTestWorkflow(workflowId, "migration-status")
              harness.recordRunning("implement", 1)
              return RuntimeDatabaseReadinessResult.Ready(true)
            }
          },
      )
    val first = assertNotNull(harness.service.status(FeatureTaskRuntimeStatusRequest(workflowId)))
    assertTrue(first.phases.any { it.phaseId == "implement" && it.attemptCount == 1 })
    assertNotNull(harness.service.readPreparedStatus(FeatureTaskRuntimeStatusRequest(workflowId)))
    assertEquals(1, preparations)
  }

  @Test
  fun `direct status retries readiness after absence or refusal and notices a replaced incompatible store`() {
    val failure =
      SkillBillRuntimeException(
        AcceptanceCriterionMigrationFailureCode.ARTIFACT_MIGRATION_REFUSED,
        "unsupported replacement store",
      )
    var readiness: RuntimeDatabaseReadinessResult = RuntimeDatabaseReadinessResult.Ready(false)
    val harness =
      statusHarness(
        databaseReadiness =
          object : RuntimeDatabaseReadiness {
            override fun prepareExisting() = readiness

            override fun initializeRead() = readiness

            override fun observeRead() = readiness
          },
      )
    val request = FeatureTaskRuntimeStatusRequest(workflowId)
    assertNull(harness.service.status(request))
    harness.recorder.openTestWorkflow(workflowId, "recovered-status")
    harness.recordRunning("implement", 2)
    readiness = RuntimeDatabaseReadinessResult.Ready(true)
    assertEquals(
      2,
      assertNotNull(harness.service.status(request)).phases.single { it.phaseId == "implement" }.attemptCount,
    )
    readiness = RuntimeDatabaseReadinessResult.Refused(failure)
    assertNull(harness.service.status(request))
    readiness = RuntimeDatabaseReadinessResult.Ready(true)
    assertEquals(
      2,
      assertNotNull(harness.service.status(request)).phases.single { it.phaseId == "implement" }.attemptCount,
    )
  }

  @Test
  fun `failed readiness exposes the original run and raw source instead of returning absent or decoded defaults`() {
    val original = "  {invalid durable source}\n"
    val retained =
      RetainedWorkflowRecord(
        workflowId,
        "bill-feature-task",
        "0AC-103",
        "repo",
        "implement",
        "2026-11-09T00:00:00Z",
        RetainedWorkflowSource("retained-store#workflow/artifacts_json", original, "[]"),
      )
    val failure =
      SkillBillRuntimeException(
        AcceptanceCriterionMigrationFailureCode.ARTIFACT_MIGRATION_REFUSED,
        "unsupported repair 99.0",
      )
    val harness =
      statusHarness(
        databaseReadiness =
          object : RuntimeDatabaseReadiness {
            override fun prepareExisting() = RuntimeDatabaseReadinessResult.Refused(failure)

            override fun initializeRead() = prepareExisting()

            override fun observeRead() = RuntimeDatabaseReadinessResult.Refused(failure)
          },
        retainedReader =
          object : RetainedWorkflowReader {
            override fun read(workflowId: String?) = listOf(retained)
          },
      )
    val status = assertNotNull(harness.service.status(FeatureTaskRuntimeStatusRequest(workflowId)))
    assertEquals(workflowId, status.workflowId)
    assertEquals(original, assertNotNull(status.migrationDiagnostic).record.source.artifactsJson)
    assertEquals(failure.code, status.migrationDiagnostic.failureCode)
    assertEquals(1, status.blockedCount)
    assertTrue(status.criterionExplanation.orEmpty().contains("retry readiness"))
  }
}
