package skillbill.engine.work

import skillbill.ports.idestatus.model.IdeStatusFreshness
import java.time.Instant
import kotlin.test.Test
import kotlin.test.assertEquals

class IdeStatusFreshnessTest {
  @Test
  fun `freshness is fresh inside the thirty minute window`() {
    val updatedAt = Instant.parse("2026-08-06T10:00:00Z")
    val observedAt = Instant.parse("2026-08-06T10:30:00Z")
    assertEquals(
      IdeStatusFreshness.FRESH,
      IdeStatusFreshnessClassifier.classify(updatedAt, observedAt),
    )
  }

  @Test
  fun `freshness becomes stale just after the thirty minute window`() {
    val updatedAt = Instant.parse("2026-08-06T10:00:00Z")
    val observedAt = Instant.parse("2026-08-06T10:30:00.001Z")
    assertEquals(
      IdeStatusFreshness.STALE,
      IdeStatusFreshnessClassifier.classify(updatedAt, observedAt),
    )
  }

  @Test
  fun `freshness is unknown when updated_at is absent`() {
    assertEquals(
      IdeStatusFreshness.UNKNOWN,
      IdeStatusFreshnessClassifier.classify(null, Instant.parse("2026-08-06T10:00:00Z")),
    )
  }

  @Test
  fun `freshness is unknown when observation precedes updated_at`() {
    val updatedAt = Instant.parse("2026-08-06T10:00:00Z")
    val observedAt = Instant.parse("2026-08-06T09:59:59Z")
    assertEquals(
      IdeStatusFreshness.UNKNOWN,
      IdeStatusFreshnessClassifier.classify(updatedAt, observedAt),
    )
  }
}
