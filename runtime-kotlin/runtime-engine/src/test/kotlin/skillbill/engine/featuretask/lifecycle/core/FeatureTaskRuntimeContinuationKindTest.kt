package skillbill.engine.featuretask.lifecycle.core

import skillbill.engine.featuretask.runloop.observability.FeatureTaskRuntimeContinuationKind
import skillbill.engine.featuretask.runloop.observability.featureTaskRuntimeStartContinuationKind
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

class FeatureTaskRuntimeContinuationKindTest {
  @Test
  fun `the continuation kinds are distinguishable on the wire`() {
    val wireValues = FeatureTaskRuntimeContinuationKind.entries.map { it.wireValue }

    assertEquals(11, wireValues.size)
    assertEquals(wireValues.size, wireValues.distinct().size, "continuation kinds must not collide on the wire")
    assertEquals(
      listOf(
        "implementation_continuation",
        "schema_correction",
        "process_retry",
        "crash_resume",
        "verifier_reentry",
        "item_coverage",
        "verification_body_delivery",
        "audit_ac_retry",
        "validate_repair",
        "audit_missing_baseline",
        "audit_non_shrinking_round",
      ),
      wireValues,
    )
  }

  @Test
  fun `every kind round-trips through the ledger detail encoding`() {
    FeatureTaskRuntimeContinuationKind.entries.forEach { kind ->
      val detail = "${FeatureTaskRuntimeContinuationKind.LEDGER_DETAIL_PREFIX}${kind.wireValue}"

      assertEquals(kind, FeatureTaskRuntimeContinuationKind.fromLedgerDetail(detail))
    }
  }

  @Test
  fun `ledger detail keeps the documented category prefix`() {
    FeatureTaskRuntimeContinuationKind.entries.forEach { kind ->
      assertTrue(
        "${FeatureTaskRuntimeContinuationKind.LEDGER_DETAIL_PREFIX}${kind.wireValue}"
          .startsWith(FeatureTaskRuntimeContinuationKind.LEDGER_DETAIL_PREFIX),
      )
    }
  }

  @Test
  fun `a loop-edge detail carrying trailing attributes still resolves its kind`() {
    val detail =
      "${FeatureTaskRuntimeContinuationKind.LEDGER_DETAIL_PREFIX}" +
        "${FeatureTaskRuntimeContinuationKind.VERIFIER_REENTRY.wireValue} driving_verdict=gaps_found"

    assertEquals(
      FeatureTaskRuntimeContinuationKind.VERIFIER_REENTRY,
      FeatureTaskRuntimeContinuationKind.fromLedgerDetail(detail),
    )
  }

  @Test
  fun `a crash resume inside a reopened verifier span still claims its own start kind`() {
    assertEquals(
      FeatureTaskRuntimeContinuationKind.CRASH_RESUME,
      featureTaskRuntimeStartContinuationKind(crashResumed = true, verifierReentry = true, attemptCount = 2),
    )
  }

  @Test
  fun `a verifier re-entry that is not a crash resume defers to the loop-edge entry`() {
    assertNull(featureTaskRuntimeStartContinuationKind(crashResumed = false, verifierReentry = true, attemptCount = 3))
  }

  @Test
  fun `a repeat attempt outside a verifier span reports a process retry`() {
    assertEquals(
      FeatureTaskRuntimeContinuationKind.PROCESS_RETRY,
      featureTaskRuntimeStartContinuationKind(crashResumed = false, verifierReentry = false, attemptCount = 2),
    )
    assertNull(featureTaskRuntimeStartContinuationKind(crashResumed = false, verifierReentry = false, attemptCount = 1))
  }

  @Test
  fun `an unrelated blocked reason does not decode as a continuation kind`() {
    assertNull(FeatureTaskRuntimeContinuationKind.fromLedgerDetail(null))
    assertNull(FeatureTaskRuntimeContinuationKind.fromLedgerDetail("needs_human: operator decision required"))
    assertNull(FeatureTaskRuntimeContinuationKind.fromLedgerDetail("continuation:not_a_kind"))
  }
}
