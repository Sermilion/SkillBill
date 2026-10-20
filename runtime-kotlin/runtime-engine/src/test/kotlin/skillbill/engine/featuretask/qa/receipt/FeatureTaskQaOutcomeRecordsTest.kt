package skillbill.engine.featuretask.qa.receipt

import skillbill.ports.idestatus.model.StandaloneQaCriterionStatus
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull

class FeatureTaskQaOutcomeRecordsTest {
  @Test
  fun `a findings receipt reports the failed criterion from its assertions`() {
    val report = assertNotNull(FeatureTaskQaOutcomeRecords.criteriaReport(qaTestReceipt(QaVerdict.FINDINGS_OPEN)))
    assertEquals(listOf("AC-1"), report.criteria.map { it.id })
    assertEquals(StandaloneQaCriterionStatus.FAILED, report.criteria.single().status)
    assertEquals("The home screen shows the signed-in user.", report.criteria.single().checks.single().description)
  }

  @Test
  fun `an empty receipt keeps no report so settlement can carry the previous one`() {
    assertNull(FeatureTaskQaOutcomeRecords.criteriaReport(emptyMap()))
  }

  @Test
  fun `an incomplete reason includes the receipt detail after the enum token`() {
    val receipt =
      qaTestReceipt(QaVerdict.INCOMPLETE) +
        mapOf("reason_detail" to "The login flow crashed on launch.")
    assertEquals(
      "device_lost · The login flow crashed on launch.",
      qaOutcomeReason(QaVerdict.INCOMPLETE, receipt, emptyMap()),
    )
  }
}
