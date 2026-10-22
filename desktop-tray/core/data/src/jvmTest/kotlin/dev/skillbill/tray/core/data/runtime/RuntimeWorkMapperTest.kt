package dev.skillbill.tray.core.data.runtime

import skillbill.idestatus.model.IdeStatusFreshness
import skillbill.idestatus.model.IdeStatusLifecycleState
import skillbill.ports.idestatus.model.IdeStatusCurrentPhaseExecution
import skillbill.ports.idestatus.model.IdeStatusCurrentPhaseExecutionKind
import skillbill.ports.idestatus.model.IdeStatusSnapshot
import skillbill.ports.idestatus.model.IdeStatusStep
import java.time.Instant
import kotlin.test.Test
import kotlin.test.assertEquals

class RuntimeWorkMapperTest {
  @Test
  fun `current criterion reaches the desktop independently of summary prose`() {
    val snapshot = IdeStatusSnapshot(
      repositoryIdentity = "repo-root-realpath-v1:/repo",
      workflowId = "workflow-1",
      lifecycleState = IdeStatusLifecycleState.ACTIVE,
      currentStep = IdeStatusStep("audit", "Audit"),
      updatedAt = Instant.parse("2026-10-22T12:00:00Z"),
      freshness = IdeStatusFreshness.FRESH,
      summary = "Acceptance audit is active.",
      currentPhaseExecution = IdeStatusCurrentPhaseExecution(
        phaseId = "audit", kind = IdeStatusCurrentPhaseExecutionKind.ATTEMPT,
        count = 2, criterionRef = "AC-005",
      ),
    )

    val execution = snapshot.toDomain("/repo", null, null).phaseExecution!!
    assertEquals("AC-005", execution.criterionRef)
    assertEquals(2, execution.count)
  }
}
