package skillbill.engine.featuretask.lifecycle.core

import skillbill.application.RecordingLifecycleTelemetryRepository
import skillbill.application.testHarnessClock
import skillbill.engine.featuretask.model.phase.FeatureTaskRuntimePhaseLaunchBriefing
import skillbill.engine.featuretask.model.phase.FeatureTaskRuntimePhaseStateRequest
import skillbill.engine.featuretask.phase.record.featureTaskRuntimePhaseRecorder
import skillbill.engine.featuretask.phase.record.openTestWorkflow
import skillbill.engine.featuretask.runner.InMemoryRuntimeWorkflowRepository
import skillbill.engine.featuretask.runner.NoopWorkflowSnapshotValidator
import skillbill.engine.featuretask.runner.RuntimeFakeDatabaseSessionFactory
import skillbill.engine.featuretask.slot.state.RequiredPhaseWrite
import skillbill.engine.featuretask.slot.state.RequiredPhaseWriteKind
import skillbill.ports.diagnostics.NoopRuntimeDiagnostics
import skillbill.ports.workflow.WorkflowSnapshotValidator
import skillbill.workflow.engine.model.WorkflowStateSnapshot
import skillbill.workflow.taskruntime.model.handoff.task.FeatureTaskRuntimeHandoffEnvelope
import skillbill.workflow.taskruntime.model.handoff.task.FeatureTaskRuntimeSharedEvidenceMeasurement
import skillbill.workflow.taskruntime.model.handoff.task.FeatureTaskRuntimeSharedEvidenceOutcome
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs

class FeatureTaskRuntimeSharedEvidenceRecorderTest {
  @Test
  fun `required start write rejects a missing workflow with attempt attribution`() {
    val recorder = recorder(RecordingLifecycleTelemetryRepository())

    val rejection =
      assertIs<RequiredPhaseWrite.Rejected>(
        recorder.recordRequiredPhaseStart(
          FeatureTaskRuntimePhaseStateRequest(
            workflowId = "wf-missing",
            phaseId = "validate",
            status = "running",
            attemptCount = 4,
            resolvedAgentId = "claude",
            finished = false,
          ),
        ),
      )

    assertEquals(RequiredPhaseWriteKind.START, rejection.writeKind)
    assertEquals("validate", rejection.phaseId)
    assertEquals(4, rejection.attempt)
  }

  @Test
  fun `required briefing write rejects a missing workflow with attempt attribution`() {
    val recorder = recorder(RecordingLifecycleTelemetryRepository())

    val rejection =
      assertIs<RequiredPhaseWrite.Rejected>(
        recorder.recordPhaseBriefing(
          workflowId = "wf-missing",
          briefing = emptyBriefing("validate"),
          attempt = 6,
        ),
      )

    assertEquals(RequiredPhaseWriteKind.BRIEFING, rejection.writeKind)
    assertEquals("validate", rejection.phaseId)
    assertEquals(6, rejection.attempt)
  }

  @Test
  fun `exactly one derivation and N-1 reuse events are emitted for N consumers at an unchanged fingerprint`() {
    val lifecycle = RecordingLifecycleTelemetryRepository()
    val recorder = recorder(lifecycle)
    recorder.openTestWorkflow("wf-shared", "session-1")
    val fingerprint = "fp-stable"
    val consumers = listOf("audit", "review", "review_lane_architecture", "review_lane_testing")

    consumers.forEachIndexed { index, phaseId ->
      val outcome =
        if (index == 0) {
          FeatureTaskRuntimeSharedEvidenceOutcome.DERIVATION
        } else {
          FeatureTaskRuntimeSharedEvidenceOutcome.REUSE
        }
      recorder.recordPhaseBriefing(
        workflowId = "wf-shared",
        briefing = emptyBriefing(phaseId),
        sharedEvidenceMeasurement = measurement(phaseId, fingerprint, outcome),
      )
    }

    val outcomes = lifecycle.sharedEvidenceMeasurements.map { it.outcome }
    assertEquals(1, outcomes.count { it == FeatureTaskRuntimeSharedEvidenceOutcome.DERIVATION })
    assertEquals(consumers.size - 1, outcomes.count { it == FeatureTaskRuntimeSharedEvidenceOutcome.REUSE })
    assertEquals(consumers, lifecycle.sharedEvidenceMeasurements.map { it.consumerPhaseId })
  }

  @Test
  fun `a changed checkpoint fingerprint emits a re-derivation event with checkpoint-change attribution`() {
    val lifecycle = RecordingLifecycleTelemetryRepository()
    val recorder = recorder(lifecycle)
    recorder.openTestWorkflow("wf-shared", "session-1")

    recorder.recordPhaseBriefing(
      workflowId = "wf-shared",
      briefing = emptyBriefing("audit"),
      sharedEvidenceMeasurement =
        measurement(
          "audit",
          "fp-before",
          FeatureTaskRuntimeSharedEvidenceOutcome.DERIVATION,
        ),
    )
    recorder.recordPhaseBriefing(
      workflowId = "wf-shared",
      briefing = emptyBriefing("audit"),
      sharedEvidenceMeasurement =
        measurement(
          "audit",
          "fp-after",
          FeatureTaskRuntimeSharedEvidenceOutcome.CHECKPOINT_CHANGE_REDERIVATION,
        ),
    )

    assertEquals(
      listOf(
        FeatureTaskRuntimeSharedEvidenceOutcome.DERIVATION,
        FeatureTaskRuntimeSharedEvidenceOutcome.CHECKPOINT_CHANGE_REDERIVATION,
      ),
      lifecycle.sharedEvidenceMeasurements.map { it.outcome },
    )
    assertEquals(
      listOf("fp-before", "fp-after"),
      lifecycle.sharedEvidenceMeasurements.map { it.checkpointFingerprint },
    )
  }

  private fun recorder(lifecycle: RecordingLifecycleTelemetryRepository) =
    featureTaskRuntimePhaseRecorder(
      RuntimeFakeDatabaseSessionFactory(InMemoryRuntimeWorkflowRepository(), lifecycle),
      NoopWorkflowSnapshotValidator,
      AcceptingFeatureTaskRuntimeWireArtifactValidator,
      AcceptingFeatureTaskRuntimeWireArtifactValidator,
      testHarnessClock,
      NoopRuntimeDiagnostics,
    )

  private fun emptyBriefing(phaseId: String) =
    FeatureTaskRuntimePhaseLaunchBriefing(
      phaseId = phaseId,
      specReference = "spec.md",
      featureSize = "MEDIUM",
      acceptanceCriteria = listOf("AC-001"),
      mandatesAndOverrides = emptyList(),
      handoffEnvelope =
        FeatureTaskRuntimeHandoffEnvelope(
          consumerPhaseId = phaseId,
          projections = emptyList(),
        ),
      derivedContextKeys = emptyList(),
      briefingText = "briefing",
    )

  private fun measurement(
    phaseId: String,
    fingerprint: String,
    outcome: FeatureTaskRuntimeSharedEvidenceOutcome,
  ) = FeatureTaskRuntimeSharedEvidenceMeasurement(
    workflowId = "wf-shared",
    checkpointFingerprint = fingerprint,
    consumerPhaseId = phaseId,
    outcome = outcome,
    fileIndexCount = 1,
    hunkIndexCount = 1,
  )

  private object NoopWorkflowSnapshotValidator : WorkflowSnapshotValidator {
    override fun validate(
      snapshot: WorkflowStateSnapshot,
      slug: String,
    ) = Unit
  }
}
