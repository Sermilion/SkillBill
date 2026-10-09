package skillbill.scaffold.platformpack.substanceaudit

import skillbill.infrastructure.skills.scaffold.platformpack.substanceaudit.PLATFORM_PACK_SUBSTANCE_CONTRACT_VERSION
import skillbill.infrastructure.skills.scaffold.platformpack.substanceaudit.PlatformPackSubstanceAudit
import skillbill.scaffold.policy.APPROVED_CODE_REVIEW_AREAS
import skillbill.scaffold.policy.UNIVERSAL_CODE_REVIEW_AREAS
import skillbill.testing.repoRootFromTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class PlatformPackSubstanceAuditRepoTest {
  @Test
  fun `maintained repository audit is deterministic and violation free`() {
    val root = repoRootFromTest()
    val first = PlatformPackSubstanceAudit.audit(root)
    val second = PlatformPackSubstanceAudit.audit(root)
    assertEquals(first, second)
    assertEquals(PLATFORM_PACK_SUBSTANCE_CONTRACT_VERSION, first.contractVersion)
    assertTrue(first.packs.isNotEmpty())
    assertTrue(first.violations.isEmpty(), first.violations.joinToString("\n") { it.format() })
    assertTrue(first.packs.all { it.qualityCheckFile == null })
    assertEquals(
      setOf("go", "ios", "kotlin", "php", "python", "rust", "typescript"),
      first.packs.filter { it.physicalAreas.toSet() == APPROVED_CODE_REVIEW_AREAS }.map { it.pack }.toSet(),
    )
    assertEquals(
      APPROVED_CODE_REVIEW_AREAS + UNIVERSAL_CODE_REVIEW_AREAS,
      first.packs.single { it.pack == "generic" }.physicalAreas.toSet(),
    )
    val kmp = first.packs.single { it.pack == "kmp" }
    assertEquals(7, kmp.physicalAreas.size)
    assertEquals(3, kmp.inheritedAreas.size)
    assertEquals(APPROVED_CODE_REVIEW_AREAS, (kmp.physicalAreas + kmp.inheritedAreas).toSet())
  }
}
