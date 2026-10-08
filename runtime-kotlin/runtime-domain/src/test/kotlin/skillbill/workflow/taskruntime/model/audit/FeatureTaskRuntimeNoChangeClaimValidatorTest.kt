package skillbill.workflow.taskruntime.model.audit

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertTrue

class FeatureTaskRuntimeNoChangeClaimValidatorTest {
  @Test
  fun `each structural defect is rejected with its specific reason`() {
    val rows =
      listOf(
        Row("unknown reason", "reason is missing or not one of") { it["reason"] = "maybe_later" },
        Row("missing reason", "reason is missing or not one of") { it.remove("reason") },
        Row("criterion without entry", "no criterion entry for AC-002") {
          it["criteria"] = listOf(criterion("AC-001"))
        },
        Row("blank evidence", "evidence for AC-001 is blank") {
          it["criteria"] = listOf(criterion("AC-001", evidence = " "), criterion("AC-002"))
        },
        Row("blank verdict", "verdict for AC-001 is blank") {
          it["criteria"] = listOf(criterion("AC-001", verdict = ""), criterion("AC-002"))
        },
        Row("no path:line citation", "path:line or path:start-end citation") {
          it["citations"] = listOf("web/report.ts")
        },
        Row("blank boundary trace", "boundary_trace is blank") { it["boundary_trace"] = "  " },
        Row("changed files", "this step changed files: web/report.ts", changedFiles = listOf("web/report.ts")) {},
        Row("unknown field", "unsupported fields: extra") { it["extra"] = "x" },
      )
    rows.forEach { row ->
      val raw = validClaim().also(row.mutate)
      val validation = FeatureTaskRuntimeNoChangeClaimValidator.validate(raw, CATALOG, row.changedFiles)
      val rejected = assertIs<FeatureTaskRuntimeNoChangeClaimValidation.Rejected>(validation, row.name)
      assertTrue(
        rejected.reasons.any { it.contains(row.expectedFragment) },
        "${row.name}: expected a reason containing '${row.expectedFragment}', was ${rejected.reasons}",
      )
    }
  }

  @Test
  fun `a valid claim with a mixed-case reason derives its handoff from the owning system`() {
    val raw =
      validClaim().also {
        it["reason"] = "Out-Of-Repo"
        it["owning_system"] = "ticket-service"
      }
    val validation = FeatureTaskRuntimeNoChangeClaimValidator.validate(raw, CATALOG, emptyList())
    val claim = assertIs<FeatureTaskRuntimeNoChangeClaimValidation.Valid>(validation).claim
    assertEquals(FeatureTaskRuntimeNoChangeReason.OUT_OF_REPO, claim.reason)
    assertEquals("ticket-service", claim.owningSystem)
    assertEquals("Hand off to ticket-service", claim.suggestedHandoff)
  }

  @Test
  fun `handoff falls back to the unidentified owner, or to the reason's fixed handoff`() {
    val outOfRepo = validClaim().also { it["reason"] = "out_of_repo" }
    val alreadySatisfied = validClaim().also { it["reason"] = "already_satisfied" }
    val notReproducible = validClaim().also { it["reason"] = "not_reproducible" }
    val handoffs =
      listOf(outOfRepo, alreadySatisfied, notReproducible).map { raw ->
        assertIs<FeatureTaskRuntimeNoChangeClaimValidation.Valid>(
          FeatureTaskRuntimeNoChangeClaimValidator.validate(raw, CATALOG, emptyList()),
        ).claim.suggestedHandoff
      }
    assertEquals(
      listOf(
        "Hand off to the owning system (not identified)",
        "Close the issue as already satisfied",
        "Return the issue to the reporter for reproduction steps",
      ),
      handoffs,
    )
  }

  private data class Row(
    val name: String,
    val expectedFragment: String,
    val changedFiles: List<String> = emptyList(),
    val mutate: (MutableMap<String, Any?>) -> Unit,
  )

  private fun validClaim(): MutableMap<String, Any?> =
    linkedMapOf(
      "reason" to "already_satisfied",
      "criteria" to listOf(criterion("AC-001"), criterion("AC-002")),
      "citations" to listOf("web/report.ts:40"),
      "boundary_trace" to "the webapp sends only ticket IDs; the server renders the report",
    )

  private fun criterion(
    id: String,
    verdict: String = "already_satisfied",
    evidence: String = "web/report.ts:40 renders the report",
  ): Map<String, String> = mapOf("criterion_id" to id, "verdict" to verdict, "evidence" to evidence)

  private companion object {
    val CATALOG = listOf("AC-001", "AC-002")
  }
}
