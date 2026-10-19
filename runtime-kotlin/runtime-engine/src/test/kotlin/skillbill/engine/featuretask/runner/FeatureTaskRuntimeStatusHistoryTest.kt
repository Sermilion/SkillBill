package skillbill.engine.featuretask.runner

import skillbill.contracts.JsonCodec
import skillbill.engine.featuretask.model.core.FeatureTaskRuntimeStatusRequest
import skillbill.engine.featuretask.phase.record.openTestWorkflow
import skillbill.ports.workflow.model.toSnapshot
import skillbill.workflow.engine.model.DurableWorkflowArtifactFamily
import skillbill.workflow.model.FeatureTaskWorkflowMode
import skillbill.workflow.taskruntime.artifact.asWorkflowArtifactEntry
import skillbill.workflow.taskruntime.model.phase.FeatureTaskRuntimePhaseLedgerAction
import skillbill.workflow.taskruntime.model.phase.FeatureTaskRuntimePhaseLedgerEntry
import skillbill.workflow.taskruntime.phase.task.FeatureTaskRuntimePhaseWorkflowDefinition
import java.time.Instant
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

class FeatureTaskRuntimeStatusHistoryTest {
  @Test
  fun `old attempts retain continuation and loop status with bounded history`() {
    val harness = statusHarness()
    val id = "wftr-20260603-status-0001"
    harness.recorder.openTestWorkflow(id, "ftr-status-001")
    harness.recordCompleted("preplan", 1)
    harness.recordCompleted("plan", 1)
    harness.recordRunning("implement", 4)
    val ledger =
      (0..5000).map { sequence ->
        FeatureTaskRuntimePhaseLedgerEntry(
          FeatureTaskRuntimePhaseLedgerAction.START,
          sequence,
          Instant.EPOCH.plusSeconds(sequence.toLong()),
          "implement",
          4,
          resolvedAgentId = "codex",
          blockedReason = if (sequence == 4999) "continuation:process_retry" else "unrecognized detail",
        )
      } +
        listOf(
          FeatureTaskRuntimePhaseLedgerEntry(
            FeatureTaskRuntimePhaseLedgerAction.LOOP_EDGE,
            5001,
            Instant.EPOCH.plusSeconds(5001),
            FeatureTaskRuntimePhaseWorkflowDefinition.PHASE_REVIEW,
            1,
            loopId = FeatureTaskRuntimePhaseWorkflowDefinition.REVIEW_FIX_LOOP_ID,
            edgeIteration = 2,
          ),
        )
    val original = assertNotNull(harness.repository.getFeatureTaskWorkflowAsMode(id, FeatureTaskWorkflowMode.RUNTIME))
    val artifacts =
      original.toSnapshot().artifacts +
        DurableWorkflowArtifactFamily.FEATURE_TASK_RUNTIME_PHASE_LEDGER.entry(
          ledger.map { it.asWorkflowArtifactEntry() },
        )
    harness.repository.saveFeatureTaskWorkflow(
      original.copy(artifactsJson = JsonCodec.mapToJsonString(artifacts)),
      FeatureTaskWorkflowMode.RUNTIME,
    )
    val records = assertNotNull(harness.recorder.loadPhaseRecords(id))
    val compact = statusPhaseHistory(harness.recorder.phaseQuery, id, records, harness.service.history)
    assertTrue(compact.size <= 4, "Retained ${compact.size} entries for one phase and one loop.")
    val request = FeatureTaskRuntimeStatusRequest(id)
    val expected =
      harness.service.buildStatusProjection(
        request,
        records,
        null,
        ledger,
        harness.recorder.phaseQuery.currentStepId(id),
      )
    assertEquals(expected, harness.service.status(request))
    assertEquals("process_retry", expected.phases.single { it.phaseId == "implement" }.continuationKind)
  }
}
