package skillbill.infrastructure.sqlite

import skillbill.error.core.RejectedOutputDiagnosticFailureCode
import skillbill.error.core.SkillBillRuntimeException
import skillbill.infrastructure.sqlite.core.schema.DatabaseRuntime
import skillbill.ports.diagnostics.model.ProducerOutputEvidence
import skillbill.ports.diagnostics.model.RejectedOutputDiagnostic
import skillbill.ports.diagnostics.model.RejectedOutputDiagnosticInsert
import skillbill.ports.diagnostics.model.RejectedOutputDiagnosticRead
import skillbill.ports.diagnostics.model.RejectedOutputDiagnosticRecord
import skillbill.ports.diagnostics.model.RejectedOutputDiagnosticSelector
import skillbill.ports.diagnostics.model.RejectedOutputLifecycle
import java.nio.file.Files
import java.security.MessageDigest
import java.time.Instant
import kotlin.test.Test
import kotlin.test.assertContains
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertIs

class SqliteRejectedOutputDiagnosticRepositoryTest {
  @Test
  fun `round trip preserves binary response and metadata`() {
    val directory = Files.createTempDirectory("rejected-output-test")
    DatabaseRuntime.ensureDatabase(directory.resolve("runtime.db")).use { connection ->
      val repository = SqliteRejectedOutputDiagnosticRepository(connection)
      val payload = byteArrayOf(0, -1, 13, 10, 0, 42)
      val record = record(payload)

      repository.insert(record)

      assertContentEquals(payload, found(repository, record.metadata.identity).payload)
      assertEquals(record.metadata, found(repository, record.metadata.identity).metadata)
      assertEquals(listOf(record.metadata), repository.select(RejectedOutputDiagnosticSelector("workflow-1")))
    }
  }

  @Test
  fun `read returns absent for an unknown identity and expired with its record for an expired row`() {
    withRepository("rejected-output-read-lifecycle") { repository ->
      val record = record(byteArrayOf(1, 2))
      assertEquals(
        RejectedOutputDiagnosticRead.Absent(record.metadata.identity),
        repository.read(record.metadata.identity),
      )
      repository.insert(record)

      repository.markExpired(Instant.parse("2026-07-29T10:00:00Z"))

      val expired = assertIs<RejectedOutputDiagnosticRead.Expired>(repository.read(record.metadata.identity))
      assertEquals(RejectedOutputLifecycle.EXPIRED, expired.record.metadata.lifecycle)
      assertEquals(null, expired.record.payload)
    }
  }

  @Test
  fun `conflicting duplicate cannot replace immutable evidence`() {
    val directory = Files.createTempDirectory("rejected-output-conflict-test")
    DatabaseRuntime.ensureDatabase(directory.resolve("runtime.db")).use { connection ->
      val repository = SqliteRejectedOutputDiagnosticRepository(connection)
      val record = record(byteArrayOf(1))
      repository.insert(record)

      assertEquals(
        RejectedOutputDiagnosticInsert.Conflict(record.metadata.identity),
        repository.insert(record.copy(payload = byteArrayOf(2))),
      )
      assertContentEquals(byteArrayOf(1), found(repository, record.metadata.identity).payload)
    }
  }

  @Test
  fun `re-retaining byte-identical producer evidence at the same key is a no-op`() {
    withRepository("producer-evidence-idempotent") { repository ->
      val evidence = evidence(byteArrayOf(1, 2, 3))

      repository.retainProducerOutput(evidence)
      repository.retainProducerOutput(evidence)

      assertContentEquals(
        byteArrayOf(1, 2, 3),
        repository.readProducerOutput("workflow-1", "review", 1, "codex", 0)?.payload,
      )
    }
  }

  @Test
  fun `same-agent differing producer evidence at a reused key conflicts`() {
    withRepository("producer-evidence-conflict") { repository ->
      repository.retainProducerOutput(evidence(byteArrayOf(1)))

      val failure =
        assertFailsWith<SkillBillRuntimeException> {
          repository.retainProducerOutput(evidence(byteArrayOf(2)))
        }

      assertEquals(RejectedOutputDiagnosticFailureCode.CONFLICT, failure.code)
      assertContains(failure.message.orEmpty(), "workflow-1:review:0:1:0:codex")
      assertContentEquals(
        byteArrayOf(1),
        repository.readProducerOutput("workflow-1", "review", 1, "codex", 0)?.payload,
      )
    }
  }

  @Test
  fun `cross-agent producer evidence at review generation 0 attempt 2 retains both rows`() {
    withRepository("producer-evidence-cross-agent") { repository ->
      val claudePayload = "claude-review-0-2".encodeToByteArray()
      val cursorPayload = "cursor-review-0-2".encodeToByteArray()
      repository.retainProducerOutput(
        evidence(claudePayload, attempt = 2, agentId = "claude"),
      )

      repository.retainProducerOutput(
        evidence(cursorPayload, attempt = 2, agentId = "cursor"),
      )

      assertContentEquals(
        claudePayload,
        repository.readProducerOutput("workflow-1", "review", 2, "claude", 0)?.payload,
      )
      assertContentEquals(
        cursorPayload,
        repository.readProducerOutput("workflow-1", "review", 2, "cursor", 0)?.payload,
      )
    }
  }

  @Test
  fun `the same attempt at a higher generation coexists with the prior generation`() {
    withRepository("producer-evidence-generation") { repository ->
      repository.retainProducerOutput(evidence(byteArrayOf(1)))

      repository.retainProducerOutput(evidence(byteArrayOf(2), generation = 1))

      assertContentEquals(
        byteArrayOf(1),
        repository.readProducerOutput("workflow-1", "review", 1, "codex", 0)?.payload,
      )
      assertContentEquals(
        byteArrayOf(2),
        repository.readProducerOutput("workflow-1", "review", 1, "codex", 1)?.payload,
      )
    }
  }

  @Test
  fun `reading producer evidence falls back to the highest generation at or below the request`() {
    withRepository("producer-evidence-fallback") { repository ->
      repository.retainProducerOutput(evidence(byteArrayOf(1)))

      val read = repository.readProducerOutput("workflow-1", "review", 1, "codex", 5)

      assertEquals(0, read?.generation)
      assertContentEquals(byteArrayOf(1), read?.payload)
    }
  }

  @Test
  fun `differing repair turns within one attempt retain distinct rows instead of conflicting`() {
    withRepository("producer-evidence-repair-turns") { repository ->
      val first = "gate-repair-turn-1".encodeToByteArray()
      val second = "gate-repair-turn-2".encodeToByteArray()
      val third = "gate-repair-turn-3".encodeToByteArray()

      repository.retainProducerOutput(evidence(first, phaseId = "validate", repairTurn = 1))
      repository.retainProducerOutput(evidence(second, phaseId = "validate", repairTurn = 2))
      repository.retainProducerOutput(evidence(third, phaseId = "validate", repairTurn = 3))

      val read = repository.readProducerOutput("workflow-1", "validate", 1, "codex", 0)
      assertEquals(3, read?.repairTurn)
      assertContentEquals(third, read?.payload)
    }
  }

  @Test
  fun `re-retaining a repair turn with different bytes still conflicts on its own key`() {
    withRepository("producer-evidence-repair-turn-conflict") { repository ->
      repository.retainProducerOutput(evidence(byteArrayOf(1), phaseId = "validate", repairTurn = 2))

      val failure =
        assertFailsWith<SkillBillRuntimeException> {
          repository.retainProducerOutput(evidence(byteArrayOf(2), phaseId = "validate", repairTurn = 2))
        }

      assertEquals(RejectedOutputDiagnosticFailureCode.CONFLICT, failure.code)
      assertContains(failure.message.orEmpty(), "workflow-1:validate:0:1:2:codex")
    }
  }

  @Test
  fun `diagnostics for two repair turns of one attempt both persist`() {
    withRepository("diagnostic-repair-turns") { repository ->
      val turnOne = record(byteArrayOf(1), identity = "rod_${"a".repeat(64)}", repairTurn = 1)
      val turnTwo = record(byteArrayOf(2), identity = "rod_${"c".repeat(64)}", repairTurn = 2)

      repository.insert(turnOne)
      repository.insert(turnTwo)

      assertEquals(
        listOf(1, 2),
        repository.select(RejectedOutputDiagnosticSelector("workflow-1")).map { it.repairTurn }.sorted(),
      )

      assertEquals(
        listOf(turnTwo.metadata.identity),
        repository.select(RejectedOutputDiagnosticSelector("workflow-1", "plan", 1, repairTurn = 2))
          .map { it.identity },
      )
    }
  }

  private fun found(
    repository: SqliteRejectedOutputDiagnosticRepository,
    identity: String,
  ): RejectedOutputDiagnosticRecord = assertIs<RejectedOutputDiagnosticRead.Found>(repository.read(identity)).record

  private fun withRepository(
    label: String,
    block: (SqliteRejectedOutputDiagnosticRepository) -> Unit,
  ) {
    val directory = Files.createTempDirectory(label)
    DatabaseRuntime.ensureDatabase(directory.resolve("runtime.db")).use { connection ->
      block(SqliteRejectedOutputDiagnosticRepository(connection))
    }
  }

  private data class ProducerEvidenceFixture(
    val payload: ByteArray,
    val attempt: Int = 1,
    val generation: Int = 0,
    val agentId: String = "codex",
    val phaseId: String = "review",
    val repairTurn: Int = 0,
  )

  private fun evidence(
    payload: ByteArray,
    attempt: Int = 1,
    generation: Int = 0,
    agentId: String = "codex",
  ) =
    evidence(ProducerEvidenceFixture(payload = payload, attempt = attempt, generation = generation, agentId = agentId))

  private fun evidence(
    payload: ByteArray,
    phaseId: String,
    repairTurn: Int,
  ) = evidence(ProducerEvidenceFixture(payload = payload, phaseId = phaseId, repairTurn = repairTurn))

  private fun evidence(
    payload: ByteArray,
    attempt: Int,
    agentId: String,
  ) = evidence(ProducerEvidenceFixture(payload = payload, attempt = attempt, agentId = agentId))

  private fun evidence(fixture: ProducerEvidenceFixture) =
    ProducerOutputEvidence(
      workflowId = "workflow-1",
      phaseId = fixture.phaseId,
      attempt = fixture.attempt,
      agentId = fixture.agentId,
      model = "gpt",
      recordedAt = Instant.parse("2026-07-28T10:00:00Z"),
      byteSize = fixture.payload.size.toLong(),
      sha256 = MessageDigest.getInstance("SHA-256").digest(fixture.payload).joinToString("") { "%02x".format(it) },
      payload = fixture.payload,
      generation = fixture.generation,
      repairTurn = fixture.repairTurn,
    )

  private fun record(
    payload: ByteArray,
    identity: String = "rod_${"a".repeat(64)}",
    repairTurn: Int = 0,
  ): RejectedOutputDiagnosticRecord =
    RejectedOutputDiagnosticRecord(
      RejectedOutputDiagnostic(
        identity = identity,
        workflowId = "workflow-1",
        phaseId = "plan",
        attempt = 1,
        rule = "schema",
        path = "/status",
        reason = "invalid",
        agentId = "codex",
        model = "gpt",
        recordedAt = Instant.parse("2026-07-28T10:00:00Z"),
        byteSize = payload.size.toLong(),
        sha256 = "b".repeat(64),
        lifecycle = RejectedOutputLifecycle.STORED,
        repairTurn = repairTurn,
      ),
      payload,
    )
}
