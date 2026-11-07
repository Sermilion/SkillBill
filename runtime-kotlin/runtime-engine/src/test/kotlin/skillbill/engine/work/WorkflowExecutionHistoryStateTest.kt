package skillbill.engine.work

import skillbill.idestatus.model.IdeStatusLifecycleState
import skillbill.ports.idestatus.model.IdeStatusWorkflowExecution
import java.time.Instant
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull

class WorkflowExecutionHistoryStateTest {
  @Test
  fun `the current execution reports the workflow's live lifecycle instead of its launch state`() {
    val instance =
      workflowExecutionInstance(
        execution("execution-current"),
        WorkflowExecutionCurrentState("execution-current", IdeStatusLifecycleState.BLOCKED, null),
      )

    assertEquals(IdeStatusLifecycleState.BLOCKED.wireValue, instance.lifecycleState)
    assertNull(instance.finishedAt)
    assertNull(instance.result)
  }

  @Test
  fun `a finished workflow's current execution carries its finish time`() {
    val instance =
      workflowExecutionInstance(
        execution("execution-current"),
        WorkflowExecutionCurrentState("execution-current", IdeStatusLifecycleState.TERMINAL, FINISHED),
      )

    assertEquals(IdeStatusLifecycleState.TERMINAL.wireValue, instance.lifecycleState)
    assertEquals(FINISHED, instance.finishedAt)
  }

  @Test
  fun `an earlier execution replaced by a later launch is reported as ended`() {
    val instance =
      workflowExecutionInstance(
        execution("execution-earlier"),
        WorkflowExecutionCurrentState("execution-current", IdeStatusLifecycleState.ACTIVE, null),
      )

    assertEquals(IdeStatusLifecycleState.TERMINAL.wireValue, instance.lifecycleState)
    assertNull(instance.finishedAt)
    assertNotNull(instance.result)
  }

  @Test
  fun `without a known current execution the stored state is kept`() {
    val instance =
      workflowExecutionInstance(
        execution("execution-earlier"),
        WorkflowExecutionCurrentState(null, IdeStatusLifecycleState.BLOCKED, null),
      )

    assertEquals("active", instance.lifecycleState)
    assertNull(instance.result)
  }

  private fun execution(executionId: String) =
    IdeStatusWorkflowExecution(
      repositoryIdentity = "repo-root-realpath-v1:/repository",
      branchCorrelation = "feat/SKILL-1",
      issueKey = "SKILL-1",
      workflowId = "wftr-goal",
      invocationId = "invocation-$executionId",
      executionId = executionId,
      statusStoreId = "store",
      runSequence = "1",
      statusRevision = "1",
      lifecycleState = "active",
      startedAt = STARTED,
      updatedAt = STARTED,
    )

  private companion object {
    val STARTED: Instant = Instant.parse("2026-11-07T11:53:58Z")
    val FINISHED: Instant = Instant.parse("2026-11-07T12:04:50Z")
  }
}
