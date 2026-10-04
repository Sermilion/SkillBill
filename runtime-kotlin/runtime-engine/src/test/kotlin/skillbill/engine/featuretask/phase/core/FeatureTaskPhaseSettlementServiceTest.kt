package skillbill.engine.featuretask.phase.core

import skillbill.application.testHarnessClock
import skillbill.contracts.JsonCodec
import skillbill.contracts.SharedPayloadKeys
import skillbill.contracts.workflow.identity.evidence.ValidationEvidencePayloadKeys
import skillbill.engine.featuretask.model.phase.FeatureTaskPhaseSettlementBlockRequest
import skillbill.engine.featuretask.model.phase.FeatureTaskPhaseSettlementCompleteRequest
import skillbill.ports.featuretask.model.FeatureTaskPhaseSettlement
import skillbill.workflow.taskruntime.artifact.asWorkflowArtifactEntry
import skillbill.workflow.taskruntime.model.handoff.task.NormalizedFeatureTaskRuntimePhaseOutput
import skillbill.workflow.taskruntime.model.validation.FeatureTaskRuntimeValidationCommandResult
import skillbill.workflow.taskruntime.model.validation.FeatureTaskRuntimeValidationEvidence
import java.time.Instant
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class FeatureTaskPhaseSettlementServiceTest {
  @Test
  fun `complete then findEnvelope returns stuffed value`() {
    val service = FeatureTaskPhaseSettlementService(InMemoryFeatureTaskPhaseSettlementRepository(), testHarnessClock)
    val acknowledgment =
      service.complete(
        FeatureTaskPhaseSettlementCompleteRequest(
          workflowId = "wftr-test",
          phaseId = "implement",
          attempt = 1,
          value = """{"projection_kind":"implementation_receipt","completed_task_ids":["task-1"]}""",
        ),
      )
    assertEquals("ok", acknowledgment.status)
    assertEquals("wftr-test", acknowledgment.workflowId)
    assertEquals("implement", acknowledgment.phaseId)
    assertEquals(1, acknowledgment.attempt)
    val envelope = assertNotNull(service.findEnvelope("wftr-test", "implement", 1)).envelope
    assertEquals("completed", envelope[SharedPayloadKeys.STATUS])
    val produced = assertNotNull(JsonCodec.anyToStringAnyMap(envelope[SharedPayloadKeys.PRODUCED_OUTPUTS]))
    assertTrue((produced[SharedPayloadKeys.VALUE] as String).contains("implementation_receipt"))
  }

  @Test
  fun `last write wins for the same attempt`() {
    val service = FeatureTaskPhaseSettlementService(InMemoryFeatureTaskPhaseSettlementRepository(), testHarnessClock)
    service.complete(
      FeatureTaskPhaseSettlementCompleteRequest(
        workflowId = "wftr-test",
        phaseId = "plan",
        attempt = 1,
        value = "first",
      ),
    )
    service.complete(
      FeatureTaskPhaseSettlementCompleteRequest(
        workflowId = "wftr-test",
        phaseId = "plan",
        attempt = 1,
        value = "second",
      ),
    )
    val envelope = assertNotNull(service.findEnvelope("wftr-test", "plan", 1)).envelope
    val produced = assertNotNull(JsonCodec.anyToStringAnyMap(envelope[SharedPayloadKeys.PRODUCED_OUTPUTS]))
    assertEquals("second", produced[SharedPayloadKeys.VALUE])
  }

  @Test
  fun `block stores blocked status`() {
    val service = FeatureTaskPhaseSettlementService(InMemoryFeatureTaskPhaseSettlementRepository(), testHarnessClock)
    service.block(
      FeatureTaskPhaseSettlementBlockRequest(
        workflowId = "wftr-test",
        phaseId = "preplan",
        attempt = 1,
        reason = "needs human",
      ),
    )
    val envelope = assertNotNull(service.findEnvelope("wftr-test", "preplan", 1)).envelope
    assertEquals("blocked", envelope[SharedPayloadKeys.STATUS])
  }

  @Test
  fun `blocked audit settlement omits verdict and stores failure disposition`() {
    val service = FeatureTaskPhaseSettlementService(InMemoryFeatureTaskPhaseSettlementRepository(), testHarnessClock)
    service.block(
      FeatureTaskPhaseSettlementBlockRequest(
        workflowId = "wftr-test",
        phaseId = "audit",
        attempt = 1,
        reason = "Planning criterion list unreadable.",
        failureDisposition = "needs_user_action",
      ),
    )
    val envelope = assertNotNull(service.findEnvelope("wftr-test", "audit", 1)).envelope
    assertEquals("blocked", envelope[SharedPayloadKeys.STATUS])
    assertNull(envelope[SharedPayloadKeys.VERDICT])
    assertEquals("needs_user_action", envelope[SharedPayloadKeys.FAILURE_DISPOSITION])
  }

  @Test
  fun `write_history settles with the uniform output and keeps the supplied verdict`() {
    val service = FeatureTaskPhaseSettlementService(InMemoryFeatureTaskPhaseSettlementRepository(), testHarnessClock)

    service.complete(
      FeatureTaskPhaseSettlementCompleteRequest(
        workflowId = "wftr-test",
        phaseId = "write_history",
        attempt = 1,
        value = "Recorded the boundary history entry for the run.",
        verdict = "recorded",
      ),
    )

    val envelope = assertNotNull(service.findEnvelope("wftr-test", "write_history", 1)).envelope
    assertEquals("write_history", envelope[SharedPayloadKeys.PHASE_ID])
    assertEquals("completed", envelope[SharedPayloadKeys.STATUS])
    assertEquals("recorded", envelope[SharedPayloadKeys.VERDICT])
    val produced = assertNotNull(JsonCodec.anyToStringAnyMap(envelope[SharedPayloadKeys.PRODUCED_OUTPUTS]))
    assertEquals("Recorded the boundary history entry for the run.", produced[SharedPayloadKeys.VALUE])
  }

  @Test
  fun `blocking rejects a blank failure disposition`() {
    val service = FeatureTaskPhaseSettlementService(InMemoryFeatureTaskPhaseSettlementRepository(), testHarnessClock)

    assertFailsWith<IllegalArgumentException> {
      service.block(
        FeatureTaskPhaseSettlementBlockRequest(
          workflowId = "wftr-test",
          phaseId = "audit",
          attempt = 1,
          reason = "external dependency unavailable",
          failureDisposition = " ",
        ),
      )
    }
  }

  @Test
  fun `clear removes a stored settlement so findEnvelope returns null`() {
    val repo = InMemoryFeatureTaskPhaseSettlementRepository()
    val service = FeatureTaskPhaseSettlementService(repo, testHarnessClock)
    repo.upsert(
      FeatureTaskPhaseSettlement(
        workflowId = "wftr-test",
        phaseId = "plan",
        attempt = 1,
        kind = FeatureTaskPhaseSettlementService.KIND_COMPLETE,
        envelopeJson = """{"status":"completed"}""",
        recordedAt = Instant.now().toString(),
      ),
    )
    assertNotNull(service.findEnvelope("wftr-test", "plan", 1))
    assertTrue(service.clear("wftr-test", "plan", 1))
    assertNull(service.findEnvelope("wftr-test", "plan", 1))
  }

  @Test
  fun `historical settled envelope reloads value and prompt with its stored bytes`() {
    val repo = InMemoryFeatureTaskPhaseSettlementRepository()
    val service = FeatureTaskPhaseSettlementService(repo, testHarnessClock)
    val stored =
      """{"phase_id":"write_history","contract_version":"1.0","status":"completed","summary":"done",""" +
        """"produced_outputs":{"value":"history written","prompt":"next","legacy_key":1}}"""
    repo.upsert(
      FeatureTaskPhaseSettlement(
        workflowId = "wftr-test",
        phaseId = "write_history",
        attempt = 2,
        kind = FeatureTaskPhaseSettlementService.KIND_COMPLETE,
        envelopeJson = stored,
        recordedAt = Instant.now().toString(),
      ),
    )
    val envelope = assertNotNull(service.findEnvelope("wftr-test", "write_history", 2)).envelope
    val normalized = NormalizedFeatureTaskRuntimePhaseOutput.fromRecordMap(envelope)
    assertEquals("history written", normalized.output.value)
    assertEquals("next", normalized.output.prompt)
    assertEquals("completed", normalized.status)
  }

  @Test
  fun `settlement round trip preserves multiple validation command results`() {
    val service = FeatureTaskPhaseSettlementService(InMemoryFeatureTaskPhaseSettlementRepository(), testHarnessClock)
    val evidence =
      FeatureTaskRuntimeValidationEvidence(
        listOf(
          FeatureTaskRuntimeValidationCommandResult("./gradlew check", 1),
          FeatureTaskRuntimeValidationCommandResult("./gradlew check --offline", 0),
        ),
      )
    service.complete(
      FeatureTaskPhaseSettlementCompleteRequest(
        workflowId = "wftr-test",
        phaseId = "implement",
        attempt = 1,
        value =
          JsonCodec.mapToJsonString(
            mapOf(ValidationEvidencePayloadKeys.VALIDATION_EVIDENCE to evidence.asWorkflowArtifactEntry()),
          ),
      ),
    )
    val envelope = assertNotNull(service.findEnvelope("wftr-test", "implement", 1)).envelope
    val produced = assertNotNull(JsonCodec.anyToStringAnyMap(envelope[SharedPayloadKeys.PRODUCED_OUTPUTS]))
    val value =
      JsonCodec.parseObjectOrNull(produced[SharedPayloadKeys.VALUE] as String)
        ?.let(JsonCodec::jsonElementToValue)
        ?.let(JsonCodec::anyToStringAnyMap)
    val results =
      JsonCodec.anyToStringAnyMap(value?.get(ValidationEvidencePayloadKeys.VALIDATION_EVIDENCE))
        ?.get(ValidationEvidencePayloadKeys.RESULTS) as? List<*>
    assertEquals(2, results?.size)
  }
}
