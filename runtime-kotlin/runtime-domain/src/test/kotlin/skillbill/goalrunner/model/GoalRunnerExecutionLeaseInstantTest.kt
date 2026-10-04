package skillbill.goalrunner.model

import java.time.Instant
import skillbill.error.core.SkillBillRuntimeException
import skillbill.error.shellcontent.WorkflowFailureCode
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

class GoalRunnerExecutionLeaseInstantTest {
  @Test
  fun `malformed execution lease timestamp fails typed at construction`() {
    assertFailsWith<SkillBillRuntimeException> {
      GoalRunnerExecutionLease(
        generation = 1,
        ownerToken = "owner",
        hostIdentity = "host",
        bootIdentity = "boot",
        pid = 1,
        processBirthToken = "birth",
        heartbeatAt = "not-a-timestamp",
        expiresAt = "2026-01-01T00:00:01Z",
      )
    }.also { assertEquals(WorkflowFailureCode.INVALID_WORKFLOW_STATE_SCHEMA, it.code) }
  }

  @Test
  fun `supported execution lease timestamps parse once for liveness comparisons`() {
    val lease =
      GoalRunnerExecutionLease(
        generation = 1,
        ownerToken = "owner",
        hostIdentity = "host",
        bootIdentity = "boot",
        pid = 1,
        processBirthToken = "birth",
        heartbeatAt = "2026-01-01T00:00:00Z",
        expiresAt = "2026-01-01T00:00:30Z",
      )
    assertEquals("2026-01-01T00:00:30Z", lease.expiresAtInstant.toString())
    assertEquals(Instant.parse("2026-01-01T00:00:00Z"), lease.heartbeatAtInstant)
  }
}
