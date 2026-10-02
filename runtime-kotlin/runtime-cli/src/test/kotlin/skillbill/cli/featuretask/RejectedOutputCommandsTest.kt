package skillbill.cli.featuretask

import skillbill.application.diagnostics.RejectedOutputDiagnosticService
import skillbill.application.diagnostics.model.RejectedOutputDiagnosticRawRead
import skillbill.application.diagnostics.model.RejectedOutputDiagnosticRecording
import skillbill.application.diagnostics.model.RejectedOutputDiagnosticRequest
import skillbill.application.diagnostics.model.RejectedOutputDiagnosticSelection
import skillbill.ports.diagnostics.RejectedOutputDiagnosticRepository
import skillbill.ports.diagnostics.model.ProducerOutputEvidence
import skillbill.ports.diagnostics.model.RejectedOutputDiagnostic
import skillbill.ports.diagnostics.model.RejectedOutputDiagnosticInsert
import skillbill.ports.diagnostics.model.RejectedOutputDiagnosticRead
import skillbill.ports.diagnostics.model.RejectedOutputDiagnosticRecord
import skillbill.ports.diagnostics.model.RejectedOutputDiagnosticSelector
import java.time.Clock
import java.time.Instant
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertTrue

class RejectedOutputCommandsTest {
  @Test
  fun `diagnostic service returns byte exact raw bodies`() {
    val repository = CliDiagnosticRepository()
    val service = RejectedOutputDiagnosticService(repository, { }, { }, clock = Clock.systemUTC())
    val raw = byteArrayOf(0, -1, 10, 13, 0, 42)
    val metadata = assertIs<RejectedOutputDiagnosticRecording.Recorded>(service.record(request(raw))).metadata

    assertTrue(metadata.byteSize == raw.size.toLong())
    val payload = assertIs<RejectedOutputDiagnosticRawRead.Payload>(service.readRaw(metadata.identity))
    assertContentEquals(raw, payload.bytes)
  }

  @Test
  fun `workflow selector without attempt returns every stored diagnostic`() {
    val repository = CliDiagnosticRepository()
    val service = RejectedOutputDiagnosticService(repository, { }, { }, clock = Clock.systemUTC())
    service.record(request(byteArrayOf(1), attempt = 1))
    service.record(request(byteArrayOf(2), attempt = 2))

    val selection = service.inspect(RejectedOutputDiagnosticSelector("workflow-1"))
    assertEquals(2, assertIs<RejectedOutputDiagnosticSelection.Selected>(selection).diagnostics.size)
  }

  private fun request(
    raw: ByteArray,
    attempt: Int = 1,
  ) = RejectedOutputDiagnosticRequest(
    workflowId = "workflow-1",
    phaseId = "implement",
    attempt = attempt,
    rule = "schema",
    path = "$.status",
    reason = "invalid",
    agentId = "codex",
    model = "gpt",
    rawResponse = raw,
  )
}

private class CliDiagnosticRepository : RejectedOutputDiagnosticRepository {
  private val records = linkedMapOf<String, RejectedOutputDiagnosticRecord>()

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

  override fun markExpired(before: Instant): Int = 0

  override fun delete(selector: RejectedOutputDiagnosticSelector): Int {
    val identities = select(selector).map { it.identity }
    identities.forEach(records::remove)
    return identities.size
  }

  override fun retainProducerOutput(evidence: ProducerOutputEvidence) = Unit

  override fun readProducerOutput(
    workflowId: String,
    phaseId: String,
    attempt: Int,
    agentId: String,
    generation: Int,
  ): ProducerOutputEvidence? = null

  override fun deleteProducerOutputsBefore(before: Instant): Int = 0
}
