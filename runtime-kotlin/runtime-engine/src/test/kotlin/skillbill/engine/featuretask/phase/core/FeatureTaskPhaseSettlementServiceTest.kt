package skillbill.engine.featuretask.phase.core

import skillbill.application.testHarnessClock
import skillbill.contracts.JsonCodec
import skillbill.contracts.SharedPayloadKeys
import skillbill.contracts.workflow.identity.evidence.ValidationEvidencePayloadKeys
import skillbill.engine.featuretask.model.phase.FeatureTaskPhaseSettlementAckStatus
import skillbill.engine.featuretask.model.phase.FeatureTaskPhaseSettlementBlockRequest
import skillbill.engine.featuretask.model.phase.FeatureTaskPhaseSettlementCompleteRequest
import skillbill.error.shellcontent.FeatureTaskRuntimeFailureCode
import skillbill.infrastructure.sqlite.SqliteFeatureTaskPhaseSettlementRepository
import skillbill.infrastructure.sqlite.sqliteSessionFactoryForTests
import skillbill.ports.featuretask.model.FeatureTaskPhaseSettlement
import skillbill.ports.workflow.model.WorkflowFamily
import skillbill.workflow.engine.WorkflowEngine
import skillbill.workflow.taskruntime.artifact.asWorkflowArtifactEntry
import skillbill.workflow.taskruntime.model.handoff.task.NormalizedFeatureTaskRuntimePhaseOutput
import skillbill.workflow.taskruntime.model.validation.FeatureTaskRuntimeValidationCommandResult
import skillbill.workflow.taskruntime.model.validation.FeatureTaskRuntimeValidationEvidence
import java.nio.file.Files
import java.time.Instant
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class FeatureTaskPhaseSettlementServiceTest {
  @Test
  fun `accepted and replayed block settlement share one immutable incident`() {
    val home = Files.createTempDirectory("phase-settlement-block-incident")
    val database = sqliteSessionFactoryForTests(home, environment = emptyMap(), clock = testHarnessClock)
    database.transaction {
      it.workflowStates.save(
        WorkflowFamily.TASK_RUNTIME,
        WorkflowEngine().openRecord(WorkflowFamily.TASK_RUNTIME.definition, "wftr-test", "session", "preplan"),
      )
    }
    val repository = SqliteFeatureTaskPhaseSettlementRepository(database)
    val service = testPhaseSettlementService(repository, testHarnessClock, database)
    val request =
      FeatureTaskPhaseSettlementBlockRequest(
        workflowId = "wftr-test",
        phaseId = "preplan",
        attempt = 1,
        reason = "planning input is missing",
      )

    assertEquals(FeatureTaskPhaseSettlementAckStatus.OK, service.block(request).status)
    assertEquals(FeatureTaskPhaseSettlementAckStatus.OK, service.block(request).status)
    assertEquals(
      FeatureTaskPhaseSettlementAckStatus.ERROR,
      service.block(request.copy(reason = "a different failure")).status,
    )
    val incidents = database.transaction { it.blockIncidents.pending(8) }

    assertEquals(1, incidents.size)
    assertEquals("planning input is missing", incidents.single().occurrence.reason)
  }

  @Test
  fun `complete then findEnvelope returns stuffed value`() {
    val service = testPhaseSettlementService(InMemoryFeatureTaskPhaseSettlementRepository(), testHarnessClock)
    val acknowledgment =
      service.complete(
        FeatureTaskPhaseSettlementCompleteRequest(
          workflowId = "wftr-test",
          phaseId = "implement",
          attempt = 1,
          value = """{"projection_kind":"implementation_receipt","completed_task_ids":["task-1"]}""",
        ),
      )
    assertEquals(FeatureTaskPhaseSettlementAckStatus.OK, acknowledgment.status)
    assertEquals("wftr-test", acknowledgment.workflowId)
    assertEquals("implement", acknowledgment.phaseId)
    assertEquals(1, acknowledgment.attempt)
    val envelope = assertNotNull(service.findEnvelope("wftr-test", "implement", 1)).envelope
    assertEquals("completed", envelope[SharedPayloadKeys.STATUS])
    val produced = assertNotNull(JsonCodec.anyToStringAnyMap(envelope[SharedPayloadKeys.PRODUCED_OUTPUTS]))
    assertTrue((produced[SharedPayloadKeys.VALUE] as String).contains("implementation_receipt"))
  }

  @Test
  fun `unfinished audit repair cannot settle and can finish in the same attempt`() {
    listOf("progress", "no_progress").forEach { verdict ->
      val service = testPhaseSettlementService(InMemoryFeatureTaskPhaseSettlementRepository(), testHarnessClock)
      val request =
        FeatureTaskPhaseSettlementCompleteRequest(
          workflowId = "wftr-test",
          phaseId = "audit_implement_fix",
          attempt = 1,
          value = "One guard repaired. The remaining production paths still need edits.",
          verdict = verdict,
        )

      val refused = service.completeFromAgent(request)

      assertEquals(FeatureTaskPhaseSettlementAckStatus.ERROR, refused.status)
      assertEquals(
        FeatureTaskRuntimeFailureCode.AUDIT_REPAIR_UNFINISHED_CONTINUE_IMPLEMENTING.name,
        refused.diagnosticCode,
      )
      assertNull(refused.envelope)
      assertNull(service.findEnvelope(request.workflowId, request.phaseId, request.attempt))
      assertEquals(
        FeatureTaskPhaseSettlementAckStatus.OK,
        service.completeFromAgent(request.copy(phaseId = "validate")).status,
      )
      val finished =
        service.completeFromAgent(
          request.copy(value = "Every planned production path repaired.", verdict = "satisfied"),
        )
      assertEquals(FeatureTaskPhaseSettlementAckStatus.OK, finished.status)
      assertNotNull(service.findEnvelope(request.workflowId, request.phaseId, request.attempt))
    }
  }

  @Test
  fun `contradictory complete for the same attempt keeps the first accepted value`() {
    val service = testPhaseSettlementService(InMemoryFeatureTaskPhaseSettlementRepository(), testHarnessClock)
    service.complete(
      FeatureTaskPhaseSettlementCompleteRequest(
        workflowId = "wftr-test",
        phaseId = "plan",
        attempt = 1,
        value = "first",
      ),
    )
    val second =
      service.complete(
        FeatureTaskPhaseSettlementCompleteRequest(
          workflowId = "wftr-test",
          phaseId = "plan",
          attempt = 1,
          value = "second",
        ),
      )
    assertEquals(FeatureTaskPhaseSettlementAckStatus.ERROR, second.status)
    assertEquals(FeatureTaskRuntimeFailureCode.PHASE_SETTLEMENT_CONFLICT.name, second.diagnosticCode)
    val envelope = assertNotNull(service.findEnvelope("wftr-test", "plan", 1)).envelope
    val produced = assertNotNull(JsonCodec.anyToStringAnyMap(envelope[SharedPayloadKeys.PRODUCED_OUTPUTS]))
    assertEquals("first", produced[SharedPayloadKeys.VALUE])
  }

  @Test
  fun `block stores blocked status`() {
    val service = testPhaseSettlementService(InMemoryFeatureTaskPhaseSettlementRepository(), testHarnessClock)
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
    val service = testPhaseSettlementService(InMemoryFeatureTaskPhaseSettlementRepository(), testHarnessClock)
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
    val service = testPhaseSettlementService(InMemoryFeatureTaskPhaseSettlementRepository(), testHarnessClock)

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
    val service = testPhaseSettlementService(InMemoryFeatureTaskPhaseSettlementRepository(), testHarnessClock)

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
    val service = testPhaseSettlementService(repo, testHarnessClock)
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
    val service = testPhaseSettlementService(repo, testHarnessClock)
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
    val service = testPhaseSettlementService(InMemoryFeatureTaskPhaseSettlementRepository(), testHarnessClock)
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
