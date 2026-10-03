package skillbill.engine.featuretask.lifecycle.core

import skillbill.application.RecordingLifecycleTelemetryRepository
import skillbill.application.diagnostics.RejectedOutputDiagnosticService
import skillbill.application.diagnostics.model.RejectedOutputDiagnosticRequest
import skillbill.application.testHarnessClock
import skillbill.contracts.JsonCodec
import skillbill.engine.featuretask.model.phase.FeatureTaskRuntimeProducerOutputRead
import skillbill.engine.featuretask.model.phase.ProducerOutputQueryArgs
import skillbill.engine.featuretask.model.review.FeatureTaskRuntimeRejectedOutputWrite
import skillbill.engine.featuretask.phase.record.featureTaskRuntimePhaseRecorder
import skillbill.engine.featuretask.phase.record.openTestWorkflow
import skillbill.engine.featuretask.runner.InMemoryRuntimeWorkflowRepository
import skillbill.engine.featuretask.runner.RuntimeFakeDatabaseSessionFactory
import skillbill.error.core.RejectedOutputDiagnosticFailureCode
import skillbill.error.core.SkillBillRuntimeException
import skillbill.error.core.rejectedOutputDiagnosticPersistenceMessage
import skillbill.ports.diagnostics.NoopRuntimeDiagnostics
import skillbill.ports.diagnostics.model.ProducerOutputEvidence
import skillbill.ports.workflow.WorkflowSnapshotValidator
import skillbill.workflow.engine.model.WorkflowStateSnapshot
import skillbill.workflow.taskruntime.artifact.asTelemetryPayload
import skillbill.workflow.taskruntime.model.core.FeatureTaskRuntimeDiagnosticFailureClass
import java.time.Instant
import kotlin.test.Test
import kotlin.test.assertContains
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertIs
import kotlin.test.assertTrue

class FeatureTaskRuntimeDiagnosticDegradationTest {
  @Test
  fun `three repair turns of one attempt each retain their own evidence row`() {
    val database = database()
    val recorder = recorder(database)
    recorder.openTestWorkflow(WORKFLOW_ID, "session-1")

    (1..3).forEach { turn ->
      recorder.retainProducerOutput(evidence("turn-$turn".encodeToByteArray(), repairTurn = turn))
    }

    assertEquals(
      listOf(1, 2, 3),
      database.retainedProducerEvidence().filter { it.phaseId == "validate" }.map { it.repairTurn },
    )
    val found =
      recorder.producerOutput(
        ProducerOutputQueryArgs(WORKFLOW_ID, "validate", 1, "cursor", generation = 0),
      )
    assertIs<FeatureTaskRuntimeProducerOutputRead.Found>(found)
    assertContentEquals("turn-3".encodeToByteArray(), found.evidence.payload)
    val absent =
      recorder.producerOutput(
        ProducerOutputQueryArgs(WORKFLOW_ID, "validate", 99, "cursor", generation = 0),
      )
    assertIs<FeatureTaskRuntimeProducerOutputRead.Absent>(absent)
    assertTrue(recorder.loadDiagnosticSignals(WORKFLOW_ID).isEmpty())
  }

  @Test
  fun `two consecutively rejected repair turns each record a diagnostic`() {
    val database = database()
    val recorder = recorder(database)
    recorder.openTestWorkflow(WORKFLOW_ID, "session-1")

    recorder.recordRejectedOutput(rejection("turn-1".encodeToByteArray(), repairTurn = 1))
    recorder.recordRejectedOutput(rejection("turn-2".encodeToByteArray(), repairTurn = 2))

    assertEquals(listOf(1, 2), database.rejectedDiagnostics().map { it.metadata.repairTurn })
    assertEquals(2, database.rejectedDiagnostics().map { it.metadata.identity }.distinct().size)
    assertTrue(recorder.loadDiagnosticSignals(WORKFLOW_ID).isEmpty())
  }

  @Test
  fun `a forced evidence conflict degrades to a durable payload-free signal instead of failing the run`() {
    val lifecycle = RecordingLifecycleTelemetryRepository()
    val database = database(lifecycle)
    val recorder = recorder(database)
    recorder.openTestWorkflow(WORKFLOW_ID, "session-1")
    val retained = "first-bytes".encodeToByteArray()
    recorder.retainProducerOutput(evidence(retained, repairTurn = 1))

    recorder.retainProducerOutput(evidence("divergent-bytes".encodeToByteArray(), repairTurn = 1))

    val signal = recorder.loadDiagnosticSignals(WORKFLOW_ID).single()
    assertEquals(FeatureTaskRuntimeDiagnosticFailureClass.CONFLICT, signal.failureClass)
    assertEquals("validate", signal.phaseId)
    assertEquals(1, signal.attempt)
    assertEquals(1, signal.repairTurn)
    assertContains(signal.conflictingKey, "$WORKFLOW_ID:validate:0:1:1:cursor")
    val measurement = lifecycle.diagnosticDegradationMeasurements.single()
    assertEquals(signal.phaseId, measurement.phaseId)
    assertEquals(signal.attempt, measurement.attempt)
    assertEquals(signal.repairTurn, measurement.repairTurn)
    assertEquals(signal.generation, measurement.generation)
    assertEquals("retain-producer-output", measurement.operation)
    assertEquals(signal.failureClass, measurement.failureClass)
    assertEquals(signal.conflictingKey, measurement.conflictingKey)
    assertTrue(
      requireNotNull(JsonCodec.anyToStringAnyMap(measurement.asTelemetryPayload())).values
        .none { it is String && it.contains("divergent-bytes") },
      "the measurement must not carry the divergent agent bytes",
    )
    val found =
      recorder.producerOutput(
        ProducerOutputQueryArgs(WORKFLOW_ID, "validate", 1, "cursor", generation = 0),
      )
    assertIs<FeatureTaskRuntimeProducerOutputRead.Found>(found)
    assertContentEquals(retained, found.evidence.payload)
  }

  @Test
  fun `a throwing telemetry sink leaves the durable signal and the null return intact`() {
    val lifecycle = RecordingLifecycleTelemetryRepository(throwOnDiagnosticDegradation = true)
    val database = database(lifecycle)
    val recorder = recorder(database)
    recorder.openTestWorkflow(WORKFLOW_ID, "session-1")
    recorder.retainProducerOutput(evidence("first-bytes".encodeToByteArray(), repairTurn = 1))

    recorder.retainProducerOutput(evidence("divergent-bytes".encodeToByteArray(), repairTurn = 1))

    val signal = recorder.loadDiagnosticSignals(WORKFLOW_ID).single()
    assertEquals(FeatureTaskRuntimeDiagnosticFailureClass.CONFLICT, signal.failureClass)
    assertTrue(lifecycle.diagnosticDegradationMeasurements.isEmpty())
  }

  @Test
  fun `a failing producer-output read is Unreadable persistence rather than Absent`() {
    val lifecycle = RecordingLifecycleTelemetryRepository()
    val database = database(lifecycle)
    database.producerOutputReadError =
      SkillBillRuntimeException(
        RejectedOutputDiagnosticFailureCode.PERSISTENCE,
        rejectedOutputDiagnosticPersistenceMessage("read-producer-output"),
      )
    val recorder = recorder(database)
    recorder.openTestWorkflow(WORKFLOW_ID, "session-1")

    val read =
      recorder.producerOutput(
        ProducerOutputQueryArgs(WORKFLOW_ID, "validate", 1, "cursor", generation = 0),
      )

    val unreadable = assertIs<FeatureTaskRuntimeProducerOutputRead.Unreadable>(read)
    assertEquals(FeatureTaskRuntimeDiagnosticFailureClass.PERSISTENCE, unreadable.failureClass)
    val signal = recorder.loadDiagnosticSignals(WORKFLOW_ID).single()
    assertEquals(FeatureTaskRuntimeDiagnosticFailureClass.PERSISTENCE, signal.failureClass)
    assertEquals(1, lifecycle.diagnosticDegradationMeasurements.size)
  }

  @Test
  fun `a same-identity divergent recordRejectedOutput returns Degraded and no rod token`() {
    val database = database()
    val recorder = recorder(database)
    recorder.openTestWorkflow(WORKFLOW_ID, "session-1")
    val first = recorder.recordRejectedOutput(rejection("first-bytes".encodeToByteArray(), repairTurn = 1))
    val written = assertIs<FeatureTaskRuntimeRejectedOutputWrite.Written>(first)
    assertTrue(written.identity.startsWith("rod_"))
    val committedIdentity = database.rejectedDiagnostics().single().metadata.identity

    val second = recorder.recordRejectedOutput(rejection("divergent-bytes".encodeToByteArray(), repairTurn = 1))
    val degraded = assertIs<FeatureTaskRuntimeRejectedOutputWrite.Degraded>(second)
    assertEquals(FeatureTaskRuntimeDiagnosticFailureClass.CONFLICT, degraded.failureClass)
    assertEquals(1, database.rejectedDiagnostics().size)
    assertEquals(committedIdentity, database.rejectedDiagnostics().single().metadata.identity)

    val newGeneration =
      recorder.recordRejectedOutput(
        rejection("divergent-bytes".encodeToByteArray(), repairTurn = 1),
        producerGeneration = 1,
      )
    val degradedAtNewGeneration = assertIs<FeatureTaskRuntimeRejectedOutputWrite.Degraded>(newGeneration)
    assertEquals(FeatureTaskRuntimeDiagnosticFailureClass.CONFLICT, degradedAtNewGeneration.failureClass)
    assertEquals(1, database.rejectedDiagnostics().size)
    assertTrue(
      database.retainedProducerEvidence().none { it.generation == 1 },
      "producer evidence retained before the diagnostic conflict must roll back with the transaction",
    )
  }

  @Test
  fun `a caller-construction defect still fails loudly instead of degrading`() {
    val database = database()
    val recorder = recorder(database)
    recorder.openTestWorkflow(WORKFLOW_ID, "session-1")

    val failure =
      assertFailsWith<SkillBillRuntimeException> {
        recorder.recordRejectedOutput(rejection(byteArrayOf(1), repairTurn = 1).copy(agentId = ""))
      }
    assertEquals(RejectedOutputDiagnosticFailureCode.INVALID_REQUEST, failure.code)
    assertTrue(recorder.loadDiagnosticSignals(WORKFLOW_ID).isEmpty())
  }

  @Test
  fun `a degraded signal never carries agent bytes`() {
    val database = database()
    val recorder = recorder(database)
    recorder.openTestWorkflow(WORKFLOW_ID, "session-1")
    recorder.retainProducerOutput(evidence("secret-agent-output".encodeToByteArray(), repairTurn = 1))

    recorder.retainProducerOutput(evidence("other-secret-output".encodeToByteArray(), repairTurn = 1))

    val summary = recorder.loadDiagnosticSignals(WORKFLOW_ID).single().operatorSummary()
    assertTrue("secret-agent-output" !in summary)
    assertTrue("other-secret-output" !in summary)
    assertContains(summary, "attempt 1")
    assertContains(summary, "repair turn 1")
  }

  @Test
  fun `rejected audit sentinel stays private when diagnostic persistence degrades`() {
    val lifecycle = RecordingLifecycleTelemetryRepository()
    val database = database(lifecycle)
    val recorder = recorder(database)
    recorder.openTestWorkflow(WORKFLOW_ID, "session-1")
    val sentinel = "SKILL187-DEGRADE-SENTINEL".encodeToByteArray()
    recorder.recordRejectedOutput(
      rejection(sentinel, repairTurn = 1).copy(
        phaseId = "audit",
        reason = "observation: does not have a value in the enumeration — offending value: blast_radius_inspected",
      ),
    )

    recorder.retainProducerOutput(evidence(sentinel, repairTurn = 1).copy(phaseId = "audit"))
    recorder.retainProducerOutput(
      evidence("divergent-bytes".encodeToByteArray(), repairTurn = 1).copy(phaseId = "audit"),
    )

    val diagnostic = database.rejectedDiagnostics().single { it.metadata.phaseId == "audit" }
    assertContentEquals(sentinel, diagnostic.payload)
    assertContains(diagnostic.metadata.reason, "blast_radius_inspected")
    val summary = recorder.loadDiagnosticSignals(WORKFLOW_ID).single().operatorSummary()
    assertTrue("SKILL187-DEGRADE-SENTINEL" !in summary)
    assertTrue("blast_radius_inspected" !in summary)
    assertContains(summary, "audit")
    val measurement =
      requireNotNull(
        JsonCodec.anyToStringAnyMap(lifecycle.diagnosticDegradationMeasurements.single().asTelemetryPayload()),
      )
    assertTrue(measurement.values.none { it is String && "SKILL187-DEGRADE-SENTINEL" in it })
    assertTrue(measurement.values.none { it is String && "blast_radius_inspected" in it })
  }

  private fun database(lifecycle: RecordingLifecycleTelemetryRepository = RecordingLifecycleTelemetryRepository()) =
    RuntimeFakeDatabaseSessionFactory(
      InMemoryRuntimeWorkflowRepository(),
      lifecycle,
    )

  private fun recorder(database: RuntimeFakeDatabaseSessionFactory) =
    featureTaskRuntimePhaseRecorder(
      database,
      NoopSnapshotValidator,
      AcceptingFeatureTaskRuntimeWireArtifactValidator,
      AcceptingFeatureTaskRuntimeWireArtifactValidator,
      testHarnessClock,
      NoopRuntimeDiagnostics,
    )

  private fun evidence(
    payload: ByteArray,
    repairTurn: Int,
  ) = ProducerOutputEvidence(
    workflowId = WORKFLOW_ID,
    phaseId = "validate",
    attempt = 1,
    agentId = "cursor",
    model = "gpt",
    recordedAt = Instant.parse("2026-08-11T21:07:48Z"),
    byteSize = payload.size.toLong(),
    sha256 = RejectedOutputDiagnosticService.sha256(payload),
    payload = payload,
    repairTurn = repairTurn,
  )

  private fun rejection(
    payload: ByteArray,
    repairTurn: Int,
  ) = RejectedOutputDiagnosticRequest(
    workflowId = WORKFLOW_ID,
    phaseId = "validate",
    attempt = 1,
    rule = "phase-output-schema",
    path = "/status",
    reason = "rejected",
    agentId = "cursor",
    model = "gpt",
    rawResponse = payload,
    repairTurn = repairTurn,
  )

  private object NoopSnapshotValidator : WorkflowSnapshotValidator {
    override fun validate(
      snapshot: WorkflowStateSnapshot,
      slug: String,
    ) = Unit
  }

  private companion object {
    const val WORKFLOW_ID: String = "wftr-skill-185"
  }
}
