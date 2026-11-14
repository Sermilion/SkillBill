package skillbill.engine.featuretask.phase.planning

import kotlin.test.Test
import kotlin.test.assertEquals

class PreplanEvidenceTest {
  @Test
  fun `references read only the last evidence section and ignore prose`() {
    val digest =
      """
      Evidence is gathered from core/Ignored.kt:1-9 in prose.

      EVIDENCE
      - stale/Earlier.kt:1-2

      ## Risks
      - core/NotEvidence.kt:3-4

      ## Evidence
      - `core/data/Mapper.kt:12-40`
      - feature/ViewModel.kt:7 the shown-mark combine
      * feature/Whole.kt
      2. core/Reversed.kt:30-10
      - The schema file needs a download first
      - core/Mapper.kt: missing digits
      ## Next steps
      - core/After.kt:1-2
      """.trimIndent()

    assertEquals(
      listOf(
        "core/data/Mapper.kt:12-40",
        "feature/ViewModel.kt:7",
        "feature/Whole.kt",
        "core/Reversed.kt:30",
      ),
      PreplanEvidence.references(digest).map { it.label },
    )
  }

  @Test
  fun `a digest without an evidence section has no references`() {
    assertEquals(emptyList(), PreplanEvidence.references("MODE: single. core/Mapper.kt:1-5 is the seam."))
  }
}
