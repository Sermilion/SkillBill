package skillbill.infrastructure.sqlite.core.migration.area.idestatus

import skillbill.contracts.JsonCodec
import skillbill.contracts.qa.QA_PANEL_HISTORY_CONTRACT_VERSION
import skillbill.error.core.SkillBillRuntimeException
import skillbill.error.shellcontent.WorkflowFailureCode
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

class QaDetailsCodecTest {
  @Test
  fun `unsupported and nontext history versions fail instead of being rewritten as current`() {
    listOf("unsupported", 1).forEach { version ->
      val stored = JsonCodec.valueToJsonString(mapOf(SqliteQaDetailsKeys.HISTORY_CONTRACT_VERSION to version))

      val failure = assertFailsWith<SkillBillRuntimeException> { decodeQaDetails(stored) }

      assertEquals(WorkflowFailureCode.INVALID_WORKFLOW_STATE_SCHEMA, failure.code)
    }
  }

  @Test
  fun `legacy details without a history version remain readable with unavailable history`() {
    val details = decodeQaDetails("{}")

    assertEquals("not_recorded", details.historyAvailability)
    assertEquals(emptyList(), details.occurrences)
    assertEquals(QA_PANEL_HISTORY_CONTRACT_VERSION, details.historyContractVersion)
  }
}
