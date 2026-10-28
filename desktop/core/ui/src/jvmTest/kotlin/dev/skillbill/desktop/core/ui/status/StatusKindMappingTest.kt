package dev.skillbill.desktop.core.ui.status

import dev.skillbill.desktop.core.designsystem.status.StatusKind
import dev.skillbill.desktop.core.domain.model.Lifecycle
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.time.Instant

class StatusKindMappingTest {
  @Test
  fun activeLifecycleUsesPlanningBeforeStalledStatus() {
    val cases = listOf(
      Triple(false, null, StatusKind.Planning),
      Triple(true, Instant.fromEpochMilliseconds(0), StatusKind.Stalled),
      Triple(true, null, StatusKind.Active),
      Triple(false, Instant.fromEpochMilliseconds(0), StatusKind.Planning),
    )

    cases.forEach { (planningPrepared, stalledSince, expected) ->
      assertEquals(expected, statusKind(Lifecycle.Active, planningPrepared, stalledSince))
    }
  }
}
