package dev.skillbill.desktop.feature.detail

import dev.skillbill.desktop.core.domain.model.StandalonePlanStage
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class PlanBriefingTest {
  @Test
  fun `runtime briefing yields setup digest counts and long fence contents`() {
    val setup = """## Run invariants (layer 1, unconditional)
spec_reference: specs/example.md
feature_size: MEDIUM
ceremony_scaling:
  preplan_ceremony: full (full preplan covering unknowns)
  review_scope: branch_diff (branch-diff review scope)
  audit_ceremony: full_per_criterion (full audit)
acceptance_criteria:
  AC-001. Parser reads saved setup
  AC-002. Digest keeps headings
mandates_and_overrides:
  (none)
"""
    val digestBlock = """### from: preplan
value:
````markdown
CODE VERIFIED
- Reads `Plan.kt`
```
- Keeps café after the inner fence
RISK
- A malformed briefing can truncate the digest.
NOT READ
- External links
````
"""
    val planText = setup + "## Upstream projections (layer 2, declared and validated)\n" + digestBlock
    val result = planBriefing(listOf(stage("plan", planText)), setOf("preplan", "plan"))

    assertEquals(listOf("preplan full", "review branch diff", "audit per criterion"), result.setup?.ceremony)
    assertEquals("specs/example.md", result.setup?.specPath)
    assertEquals(listOf("AC-001", "AC-002"), result.setup?.criteria?.map { it.id })
    assertEquals(emptyList<String>(), result.setup?.mandates)
    assertEquals(listOf("CODE VERIFIED", "RISK", "NOT READ"), result.digest?.map { it.title })
    assertEquals("Reads `Plan.kt`", result.digest?.firstOrNull()?.items?.firstOrNull())
    assertEquals(listOf("Reads `Plan.kt`", "```", "Keeps café after the inner fence"), result.digest?.firstOrNull()?.items)
    assertEquals(digestBlock.encodeToByteArray().size, result.stages[1].digestBytes)
    assertEquals(setup.encodeToByteArray().size, result.stages[1].setupBytes)
    assertEquals(planText.encodeToByteArray().size, result.stages[1].bytes)
    assertEquals("run setup + preplan digest", result.stages[1].layers)
  }

  @Test
  fun `briefing without run invariants leaves setup absent and exposes missing stages`() {
    val result = planBriefing(
      listOf(stage("preplan", "A saved briefing without governed setup.")),
      setOf("preplan", "plan"),
    )

    assertNull(result.setup)
    assertEquals("the runtime did not save one", result.stages[1].layers)
    assertEquals("not started", result.stages[2].layers)
    assertEquals("40 B", result.stages[0].sizeLabel)
    assertNull(result.stages[1].sizeLabel)
  }

  private fun stage(phaseId: String, text: String) = StandalonePlanStage(phaseId, null, null, text)
}
