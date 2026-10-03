package skillbill.engine.featuretask.lifecycle.remediation

import skillbill.contracts.JsonCodec
import skillbill.contracts.SharedPayloadKeys
import skillbill.engine.featuretask.lifecycle.core.AcceptingFeatureTaskRuntimeWireArtifactValidator
import skillbill.engine.featuretask.lifecycle.execution.ExecutionPlanAdmissionFixture
import skillbill.engine.featuretask.model.phase.AppendCheckpointIdentityArgs
import skillbill.engine.featuretask.model.phase.FeatureTaskRuntimePhaseStateRequest
import skillbill.engine.featuretask.phase.record.featureTaskRuntimePhaseRecorder
import skillbill.engine.featuretask.phase.record.openTestWorkflow
import skillbill.engine.featuretask.phaserun.phaseRunDatabase
import skillbill.engine.featuretask.runner.NoopWorkflowSnapshotValidator
import skillbill.error.shellcontent.InvalidFeatureTaskRuntimeCheckpointIdentityVersionError
import skillbill.ports.db.DatabaseSessionFactory
import skillbill.ports.diagnostics.NoopRuntimeDiagnostics
import skillbill.workflow.engine.model.DurableWorkflowArtifactFamily
import skillbill.workflow.model.FeatureTaskWorkflowMode
import skillbill.workflow.model.WorkflowStatus
import java.nio.file.Files
import java.time.Clock
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

class CheckpointHistoryRefusalTest {
  @Test
  fun `checkpoint append cannot replace unsupported history or change retained execution evidence`() {
    (listOf(WorkflowStatus.RUNNING) + WorkflowStatus.terminalStatuses).forEach { status ->
      listOf("pending", "running", "completed").forEach { finalizationStatus ->
        val home = Files.createTempDirectory("checkpoint-history-refusal")
        try {
          val database = phaseRunDatabase(home, Clock.systemUTC())
          val recorder =
            featureTaskRuntimePhaseRecorder(
              database,
              NoopWorkflowSnapshotValidator,
              AcceptingFeatureTaskRuntimeWireArtifactValidator,
              AcceptingFeatureTaskRuntimeWireArtifactValidator,
              Clock.systemUTC(),
              NoopRuntimeDiagnostics,
            )
          val workflowId = "wftr-checkpoint-refusal"
          val execution = ExecutionPlanAdmissionFixture()
          database.transaction { execution.seed(it.workflowStates, workflowId) }
          assertTrue(recorder.openTestWorkflow(workflowId, "checkpoint-refusal", "SKILL-384"))
          recorder.recordPhaseState(
            FeatureTaskRuntimePhaseStateRequest(
              workflowId = workflowId,
              phaseId = "commit_push",
              status = finalizationStatus,
              attemptCount = 2,
              resolvedAgentId = "original-agent",
              finished = finalizationStatus == "completed",
              outputArtifact = "retained-commit-push-evidence",
            ),
          )
          seedUnsupportedCheckpointHistory(database, workflowId, status)
          val before = database.read { assertNotNull(it.workflowStates.getFeatureTaskWorkflow(workflowId)) }

          assertFailsWith<InvalidFeatureTaskRuntimeCheckpointIdentityVersionError> {
            recorder.appendCheckpointIdentity(
              AppendCheckpointIdentityArgs(
                workflowId = workflowId,
                issueKey = "SKILL-384",
                subtaskId = "2",
                branch = "feat/SKILL-384",
                phaseId = "review",
                loopId = null,
                generation = 0,
                parentSha = "a".repeat(40),
                ownedPaths = listOf("src/Changed.kt"),
                commitSha = "b".repeat(40),
              ),
            )
          }

          database.read { assertEquals(before, it.workflowStates.getFeatureTaskWorkflow(workflowId)) }
        } finally {
          home.toFile().deleteRecursively()
        }
      }
    }
  }

  private fun seedUnsupportedCheckpointHistory(
    database: DatabaseSessionFactory,
    workflowId: String,
    status: WorkflowStatus,
  ) {
    database.transaction { unit ->
      val row = assertNotNull(unit.workflowStates.getFeatureTaskWorkflow(workflowId))
      val artifacts =
        assertNotNull(
          JsonCodec.anyToStringAnyMap(JsonCodec.parseValue(row.artifactsJson)),
        ).toMutableMap()
      artifacts.putAll(
        mapOf(
          DurableWorkflowArtifactFamily.FEATURE_TASK_RUNTIME_CHECKPOINT_IDENTITIES.entry(
            mapOf(
              SharedPayloadKeys.CONTRACT_VERSION to "0.1",
              "checkpoints" to listOf("retained-checkpoint-evidence"),
            ),
          ),
        ),
      )
      unit.workflowStates.saveFeatureTaskWorkflow(
        row.copy(
          workflowStatus = status.wireValue,
          artifactsJson = JsonCodec.mapToJsonString(artifacts),
          finishedAt = "2026-09-28T00:00:00Z".takeIf { status in WorkflowStatus.terminalStatuses },
        ),
        FeatureTaskWorkflowMode.RUNTIME,
      )
    }
  }
}
