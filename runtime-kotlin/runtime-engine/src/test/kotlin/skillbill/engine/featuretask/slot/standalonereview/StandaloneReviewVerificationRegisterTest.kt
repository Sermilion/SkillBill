package skillbill.engine.featuretask.slot.standalonereview

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class StandaloneReviewVerificationRegisterTest {
  @Test
  fun `a register surrounded by the verifier's reasoning is accepted`() {
    val output =
      "I checked each finding against the source at HEAD (5a698a4a2). I did not run Gradle and changed nothing.\n\n" +
        "F-001: confirmed\n" +
        "F-002: rejected\n" +
        "W-001: complete\n\n" +
        "- **F-001:** In `CapmoAssistantRepository.kt:137`, the failure branch passes the error through.\n" +
        "- **F-002:** This is a summary, not a defect.\n" +
        "- **W-001:** Not running the tests limits execution evidence, not what was reviewed."

    val register = parseVerificationRegister(output, setOf("F-001", "F-002"), setOf("W-001"))

    assertEquals(mapOf("F-001" to "confirmed", "F-002" to "rejected"), register?.findingDispositions)
    assertEquals(mapOf("W-001" to "complete"), register?.warningClassifications)
  }

  @Test
  fun `markdown and register-style entries are read`() {
    val output =
      "- **F-001**: Confirmed\n" +
        "2. `F-002` — unresolved, the branch is unreachable from tests\n" +
        "[W-001] incomplete | source=delegated coverage | lane skipped"

    val register = parseVerificationRegister(output, setOf("F-001", "F-002"), setOf("W-001"))

    assertEquals(mapOf("F-001" to "confirmed", "F-002" to "unresolved"), register?.findingDispositions)
    assertEquals(mapOf("W-001" to "incomplete"), register?.warningClassifications)
  }

  @Test
  fun `identities that were not asked about are ignored`() {
    val output = "F-001: confirmed\nF-007: rejected\nW-004: complete"

    val register = parseVerificationRegister(output, setOf("F-001"), emptySet())

    assertEquals(mapOf("F-001" to "confirmed"), register?.findingDispositions)
  }

  @Test
  fun `a repeated identity with the same status is one entry`() {
    val output = "F-001: confirmed\n- **F-001:** Confirmed, the stub never fails."

    val register = parseVerificationRegister(output, setOf("F-001"), emptySet())

    assertEquals(mapOf("F-001" to "confirmed"), register?.findingDispositions)
  }

  @Test
  fun `a register missing an identity is rejected`() {
    assertNull(parseVerificationRegister("F-001: confirmed", setOf("F-001", "F-002"), emptySet()))
    assertNull(parseVerificationRegister("F-001: confirmed", setOf("F-001"), setOf("W-001")))
  }

  @Test
  fun `conflicting statuses for one identity are rejected`() {
    assertNull(parseVerificationRegister("F-001: confirmed\nF-001: rejected", setOf("F-001"), emptySet()))
  }

  @Test
  fun `a status from the other register does not count`() {
    assertNull(parseVerificationRegister("F-001: complete\nW-001: confirmed", setOf("F-001"), setOf("W-001")))
  }
}
