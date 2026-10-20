package dev.skillbill.tray.core.data.runtime

import dev.skillbill.tray.core.domain.model.RunOutcome
import dev.skillbill.tray.core.domain.model.outcome
import skillbill.idestatus.model.IdeStatusFreshness
import skillbill.idestatus.model.IdeStatusLifecycleState
import skillbill.idestatus.model.IdeStatusWorkflowFamily
import skillbill.ports.idestatus.model.IdeStatusSnapshot
import skillbill.ports.idestatus.model.IdeStatusStep
import java.time.Instant
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.time.toKotlinInstant

class RuntimeWorkMapperStallTest {
  private val now = Instant.parse("2026-10-20T11:35:00Z")

  private fun goalSnapshot(stalledSince: Instant?) = IdeStatusSnapshot(
    repositoryIdentity = "repo-root-realpath-v1:/repo",
    lifecycleState = IdeStatusLifecycleState.ACTIVE,
    currentStep = IdeStatusStep("qa", "QA"),
    updatedAt = now.minusSeconds(10),
    freshness = IdeStatusFreshness.FRESH,
    summary = "Goal SKILL-1 is active on QA.",
    issueKey = "SKILL-1",
    workflowId = "goal-1",
    workflowFamily = IdeStatusWorkflowFamily.FEATURE_GOAL,
    stalledSince = stalledSince,
  )

  @Test
  fun `active goal the runtime reports stalled keeps its stall instant and reads as stalled`() {
    val lastProgress = now.minusSeconds(106 * 60)
    val item = goalSnapshot(stalledSince = lastProgress).toDomain("/repo", null, null)

    assertEquals(lastProgress.toKotlinInstant(), item.stalledSince)
    assertEquals(RunOutcome.STALLED, item.outcome())
  }

  @Test
  fun `active goal without a stall verdict stays active`() {
    val item = goalSnapshot(stalledSince = null).toDomain("/repo", null, null)

    assertEquals(RunOutcome.ACTIVE, item.outcome())
  }
}
