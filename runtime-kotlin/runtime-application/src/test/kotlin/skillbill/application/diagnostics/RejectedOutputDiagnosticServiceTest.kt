package skillbill.application.diagnostics

import skillbill.application.diagnostics.model.RejectedOutputDiagnosticConfig
import skillbill.application.diagnostics.model.RejectedOutputDiagnosticRawRead
import skillbill.application.diagnostics.model.RejectedOutputDiagnosticRecording
import skillbill.application.diagnostics.model.RejectedOutputDiagnosticRequest
import skillbill.application.diagnostics.model.RejectedOutputDiagnosticSelection
import skillbill.error.core.RejectedOutputDiagnosticFailureCode
import skillbill.error.core.SkillBillRuntimeException
import skillbill.error.core.rejectedOutputDiagnosticConflictMessage
import skillbill.error.shellcontent.InvalidRejectedOutputDiagnosticSchemaError
import skillbill.ports.diagnostics.RejectedOutputDiagnosticMetadataValidator
import skillbill.ports.diagnostics.RejectedOutputDiagnosticRepository
import skillbill.ports.diagnostics.model.ProducerOutputEvidence
import skillbill.ports.diagnostics.model.RejectedOutputDiagnostic
import skillbill.ports.diagnostics.model.RejectedOutputDiagnosticInsert
import skillbill.ports.diagnostics.model.RejectedOutputDiagnosticRead
import skillbill.ports.diagnostics.model.RejectedOutputDiagnosticRecord
import skillbill.ports.diagnostics.model.RejectedOutputDiagnosticSelector
import skillbill.ports.diagnostics.model.RejectedOutputLifecycle
import java.time.Clock
import java.time.Instant
import java.time.ZoneOffset
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertIs
import kotlin.test.assertNotEquals

class RejectedOutputDiagnosticServiceTest {
  private val now = Instant.parse("2026-07-28T10:00:00Z")

  @Test
  fun `records exact bytes and deduplicates one attempt`() {
    val repository = MemoryRepository()
    val service = service(repository)
    val bytes = byteArrayOf(0, 13, 10, -1, 42)
    val request = request(bytes)

    val first = service.recorded(request)
    val second = service.recorded(request)

    assertEquals(first.identity, second.identity)
    assertEquals(bytes.size.toLong(), first.byteSize)
    val raw = assertIs<RejectedOutputDiagnosticRawRead.Payload>(service.readRaw(first.identity))
    assertContentEquals(bytes, raw.bytes)
    assertEquals(1, repository.records.size)
  }

  @Test
  fun `different attempts have distinct stable identities`() {
    val service = service(MemoryRepository())
    assertNotEquals(
      service.recorded(request(byteArrayOf(1))).identity,
      service.recorded(request(byteArrayOf(1), 2)).identity,
    )
  }

  @Test
  fun `two consecutively rejected repair turns of one attempt each record a diagnostic`() {
    val repository = MemoryRepository()
    val service = service(repository)

    val first = service.recorded(request(byteArrayOf(1)).copy(repairTurn = 1))
    val second = service.recorded(request(byteArrayOf(2)).copy(repairTurn = 2))

    assertNotEquals(first.identity, second.identity)
    assertEquals(2, repository.records.size)
    assertEquals(listOf(1, 2), repository.records.values.map { it.metadata.repairTurn })
  }

  @Test
  fun `an ordinary attempt keeps the identity it was persisted under before repair turns existed`() {
    assertEquals(
      "rod_20d2d05b2eb34fe4ceff7ebda0df27707b6efdd817b212e94399b33c4babfc6a",
      RejectedOutputDiagnosticService.stableIdentity("workflow-1", "validate", 1),
    )
    assertEquals(
      RejectedOutputDiagnosticService.stableIdentity("workflow-1", "validate", 1),
      RejectedOutputDiagnosticService.stableIdentity("workflow-1", "validate", 1, repairTurn = 0),
    )
  }

  @Test
  fun `ceiling stores tombstone and read reports oversized`() {
    val service = service(MemoryRepository(), maximumPayloadBytes = 1)
    val metadata = service.recorded(request(byteArrayOf(1, 2)))

    assertEquals(RejectedOutputLifecycle.OVERSIZED, metadata.lifecycle)
    assertIs<RejectedOutputDiagnosticRawRead.Oversized>(service.readRaw(metadata.identity))
  }

  @Test
  fun `truncated capture stores deterministic full stream oversized evidence`() {
    val service = service(MemoryRepository())
    val metadata =
      service.recorded(
        request(byteArrayOf(1)).copy(
          observedByteSize = 1_048_577,
          observedSha256 = "a".repeat(64),
          truncated = true,
        ),
      )

    assertEquals(RejectedOutputLifecycle.OVERSIZED, metadata.lifecycle)
    assertEquals(1_048_577, metadata.byteSize)
    assertEquals("a".repeat(64), metadata.sha256)
  }

  @Test
  fun `record executes configured retention before inserting new evidence`() {
    val repository = MemoryRepository()
    service(repository).recorded(request(byteArrayOf(1)))

    assertEquals(1, repository.expiryCalls)
  }

  @Test
  fun `re-recording an expired attempt is an idempotent tombstone lookup`() {
    val repository = MemoryRepository()
    val service = service(repository)
    val first = service.recorded(request(byteArrayOf(1, 2)))
    repository.records[first.identity] =
      repository.records.getValue(first.identity).copy(
        metadata = first.copy(lifecycle = RejectedOutputLifecycle.EXPIRED),
        payload = null,
      )

    val replay = service.recorded(request(byteArrayOf(1, 2)))

    assertEquals(RejectedOutputLifecycle.EXPIRED, replay.lifecycle)
    assertEquals(1, repository.records.size)
  }

  @Test
  fun `producer evidence applies permissions and retention before insertion`() {
    val repository = MemoryRepository()
    var permissionCalls = 0
    val service =
      RejectedOutputDiagnosticService(
        repository,
        permissions = { permissionCalls += 1 },
        metadataValidator = RejectedOutputDiagnosticMetadataValidator { },
        clock = Clock.fixed(now, ZoneOffset.UTC),
      )
    service.retainProducerOutput(
      ProducerOutputEvidence(
        "workflow-1", "plan", 1, "codex", "gpt", now, 1, "a".repeat(64), byteArrayOf(1),
      ),
    )

    assertEquals(1, permissionCalls)
    assertEquals(1, repository.expiryCalls)
    assertEquals(1, repository.producerOutputs)
  }

  @Test
  fun `corrupt payload fails without returning content`() {
    val repository = MemoryRepository()
    val service = service(repository)
    val metadata = service.recorded(request(byteArrayOf(1, 2)))
    repository.records[metadata.identity] =
      repository.records.getValue(
        metadata.identity,
      ).copy(payload = byteArrayOf(9))

    val failure = assertFailsWith<SkillBillRuntimeException> { service.readRaw(metadata.identity) }
    assertEquals(RejectedOutputDiagnosticFailureCode.CORRUPT, failure.code)
  }

  @Test
  fun `record and read seams reject metadata outside the canonical schema with typed error`() {
    val repository = MemoryRepository()
    val service = service(repository)
    val metadata = service.recorded(request(byteArrayOf(1, 2)))
    repository.records[metadata.identity] =
      repository.records.getValue(metadata.identity).copy(
        metadata = metadata.copy(sha256 = "not-a-digest"),
      )

    assertFailsWith<InvalidRejectedOutputDiagnosticSchemaError> { service.readRaw(metadata.identity) }
  }

  @Test
  fun `invalid request and configuration failures are typed`() {
    val configurationFailure =
      assertFailsWith<SkillBillRuntimeException> {
        RejectedOutputDiagnosticConfig(maximumPayloadBytes = -1)
      }
    assertEquals(RejectedOutputDiagnosticFailureCode.INVALID_CONFIGURATION, configurationFailure.code)
    assertIs<RejectedOutputDiagnosticRecording.InvalidRequest>(
      service(MemoryRepository()).record(request(byteArrayOf(1)).copy(workflowId = "")),
    )
    assertIs<RejectedOutputDiagnosticSelection.InvalidRequest>(
      service(MemoryRepository()).inspect(RejectedOutputDiagnosticSelector("")),
    )
  }

  private fun RejectedOutputDiagnosticService.recorded(request: RejectedOutputDiagnosticRequest) =
    assertIs<RejectedOutputDiagnosticRecording.Recorded>(record(request)).metadata

  private fun service(
    repository: MemoryRepository,
    maximumPayloadBytes: Long = 100,
  ) = RejectedOutputDiagnosticService(
    repository,
    permissions = { },
    metadataValidator =
      RejectedOutputDiagnosticMetadataValidator { metadata ->
        if (!Regex("[0-9a-f]{64}").matches(metadata.sha256)) {
          throw InvalidRejectedOutputDiagnosticSchemaError("sha256 is invalid")
        }
      },
    config = RejectedOutputDiagnosticConfig(maximumPayloadBytes = maximumPayloadBytes),
    clock = Clock.fixed(now, ZoneOffset.UTC),
  )

  private fun request(
    bytes: ByteArray,
    attempt: Int = 1,
  ) = RejectedOutputDiagnosticRequest(
    workflowId = "workflow-1",
    phaseId = "plan",
    attempt = attempt,
    rule = "phase-output-schema",
    path = "/produced_outputs",
    reason = "required property missing",
    agentId = "codex",
    model = "gpt",
    rawResponse = bytes,
  )
}

private class MemoryRepository : RejectedOutputDiagnosticRepository {
  val records = linkedMapOf<String, RejectedOutputDiagnosticRecord>()
  val producerEvidence = linkedMapOf<List<Any>, ProducerOutputEvidence>()
  var expiryCalls: Int = 0
  var producerOutputs: Int = 0

  override fun insert(record: RejectedOutputDiagnosticRecord): RejectedOutputDiagnosticInsert =
    RejectedOutputDiagnosticInsert.Inserted(records.getOrPut(record.metadata.identity) { record })

  override fun select(selector: RejectedOutputDiagnosticSelector): List<RejectedOutputDiagnostic> =
    records.values.map { it.metadata }.filter {
      it.workflowId == selector.workflowId &&
        (selector.phaseId == null || it.phaseId == selector.phaseId) &&
        (selector.attempt == null || it.attempt == selector.attempt)
    }

  override fun read(identity: String): RejectedOutputDiagnosticRead =
    records[identity]?.let(RejectedOutputDiagnosticRead::Found) ?: RejectedOutputDiagnosticRead.Absent(identity)

  override fun markExpired(before: Instant): Int {
    expiryCalls += 1
    return 0
  }

  override fun deleteProducerOutputsBefore(before: Instant): Int = 0

  override fun delete(selector: RejectedOutputDiagnosticSelector): Int = 0

  override fun retainProducerOutput(evidence: ProducerOutputEvidence) {
    producerOutputs += 1
    val key =
      listOf(
        evidence.workflowId,
        evidence.phaseId,
        evidence.generation,
        evidence.attempt,
        evidence.repairTurn,
        evidence.agentId,
      )
    producerEvidence.putIfAbsent(key, evidence)
    val retained = producerEvidence.getValue(key)
    if (retained.sha256 != evidence.sha256 || retained.byteSize != evidence.byteSize) {
      throw SkillBillRuntimeException(
        RejectedOutputDiagnosticFailureCode.CONFLICT,
        rejectedOutputDiagnosticConflictMessage(
          "${evidence.workflowId}:${evidence.phaseId}:${evidence.generation}:${evidence.attempt}:" +
            "${evidence.repairTurn}:${evidence.agentId}",
        ),
      )
    }
  }

  override fun readProducerOutput(
    workflowId: String,
    phaseId: String,
    attempt: Int,
    agentId: String,
    generation: Int,
  ): ProducerOutputEvidence? =
    producerEvidence.values
      .filter {
        it.workflowId == workflowId && it.phaseId == phaseId &&
          it.attempt == attempt && it.agentId == agentId && it.generation <= generation
      }
      .maxWithOrNull(compareBy({ it.generation }, { it.repairTurn }))
}
