package skillbill.engine.featuretask.slot.audit.planning

import kotlin.test.Test
import kotlin.test.assertContains
import kotlin.test.assertNotNull
import kotlin.test.assertNull

class AuditFixPlanCoverageTest {
  private val criteria = listOf("AC-001. Preserve committed work.", "AC-002. Guard recovery admission.")
  private val audit = "AC-001: missing commit preservation.\nAC-002: missing admission guard."

  @Test
  fun `each remaining criterion needs concrete changes and closure evidence`() {
    assertNull(AuditFixPlanCoverage.rejection(criteria, audit, item("AC-001") + item("AC-002")))
    assertContains(
      assertNotNull(AuditFixPlanCoverage.rejection(criteria, audit, item("AC-001"))),
      "AC-002",
    )
    assertContains(
      assertNotNull(
        AuditFixPlanCoverage.rejection(
          criteria,
          audit,
          item("AC-001") + item("AC-002").replace("Changes: Repair the transaction.", "Changes:"),
        ),
      ),
      "Changes:",
    )
    assertContains(
      assertNotNull(
        AuditFixPlanCoverage.rejection(
          criteria,
          audit,
          item("AC-001") + item("AC-002").replace("Closure evidence: Mutation follows admission.", ""),
        ),
      ),
      "Closure evidence:",
    )
  }

  @Test
  fun `independent gaps under one criterion remain separate plan items`() {
    assertNull(
      AuditFixPlanCoverage.rejection(criteria, "AC-002: two production gaps.", item("AC-002") + item("AC-002")),
    )
    assertContains(
      assertNotNull(AuditFixPlanCoverage.rejection(criteria, "AC-002: admission gap.", item("AC-001"))),
      "absent from the audit",
    )
  }

  private fun item(criterion: String): String =
    """
    ### $criterion
    Gap: Missing admission.
    Production path: Recovery.kt transaction owner.
    Changes: Repair the transaction.
    Closure evidence: Mutation follows admission.

    """.trimIndent() + "\n"
}
