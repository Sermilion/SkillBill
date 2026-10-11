package dev.skillbill.tray.infrastructure.cli

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNull
import dev.skillbill.tray.domain.WorkIdentity

class WorkPayloadDecoderTest {
  @Test
  fun `rejects incompatible envelopes and separates absent database from healthy empty`() {
    assertFailsWith<IncompatibleContractException> { WorkPayloadDecoder.board("""{"contract_version":"2.0"}""") }
    val empty = WorkPayloadDecoder.board("""{"contract_version":"1.0","items":[],"observed_at":"2026-10-11T10:00:00Z","finished_within":"24h","diagnostics":[]}""")
    assertEquals(emptyList(), empty.items)
    assertNull(empty.problem)
    val absent = WorkPayloadDecoder.board("""{"contract_version":"1.0","items":[],"observed_at":"2026-10-11T10:00:00Z","finished_within":"24h","diagnostics":[],"problem":{"code":"absent_database","message":"missing"}}""")
    assertEquals("absent_database", absent.problem?.code)
    assertFailsWith<IncompatibleContractException> { WorkPayloadDecoder.detail("""{"contract_version":"0.9"}""") }
    assertFailsWith<MalformedPayloadException> { WorkPayloadDecoder.board("""{"contract_version":"1.0","items":[],"finished_within":"24h","diagnostics":[]}""") }
    assertFailsWith<MalformedPayloadException> { WorkPayloadDecoder.detail("""{"contract_version":"1.0","observed_at":"2026-10-11T10:00:00Z"}""") }
  }

  @Test
  fun `detail lookup refuses an id returned for a different repository`() {
    val payload = """{"contract_version":"1.0","observed_at":"2026-10-11T10:00:00Z","item":{"repository_root":"/other","snapshot":{"contract_version":"0.3","repository_identity":"repo-other","workflow_id":"same-id","lifecycle_state":"active","freshness":"fresh","summary":"other work","current_step":{"id":"implement","label":"Implement"},"updated_at":"2026-10-11T10:00:00Z"},"step_pipeline":[]}}"""
    val result = WorkPayloadDecoder.detail(payload, WorkIdentity("same-id", "repo-requested"))
    assertNull(result.item)
    assertEquals("unknown_work_item", result.problem?.code)
  }
}
