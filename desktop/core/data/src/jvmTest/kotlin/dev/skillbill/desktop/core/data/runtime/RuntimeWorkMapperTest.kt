package dev.skillbill.desktop.core.data.runtime

import skillbill.idestatus.model.IdeStatusExecutionScope
import skillbill.idestatus.model.IdeStatusFreshness
import skillbill.idestatus.model.IdeStatusLifecycleState
import skillbill.ports.idestatus.model.IdeStatusExecutionIdentity
import skillbill.ports.idestatus.model.IdeStatusSnapshot
import skillbill.ports.idestatus.model.IdeStatusStep
import skillbill.ports.idestatus.model.WorkBoardItem
import java.time.Instant
import kotlin.test.Test
import kotlin.test.assertEquals

class RuntimeWorkMapperTest {
  @Test
  fun `a standalone phase opens by its invocation and a workflow by its workflow id`() {
    val standalone = IdeStatusExecutionIdentity(
      IdeStatusExecutionScope.STANDALONE_PHASE, "exec-1", "store-1", "1", "1", invocationId = "inv-1", phaseId = "plan",
    )
    val workflow = IdeStatusExecutionIdentity(IdeStatusExecutionScope.WORKFLOW, "exec-2", "store-1", "1", "1")

    assertEquals("inv-1", board(workflowId = "wf-1", standalone).toWorkItem(1L).id)
    assertEquals("wf-2", board(workflowId = "wf-2", workflow).toWorkItem(1L).id)
  }

  private fun board(workflowId: String, execution: IdeStatusExecutionIdentity) = WorkBoardItem(
    snapshot = IdeStatusSnapshot(
      repositoryIdentity = "repo",
      lifecycleState = IdeStatusLifecycleState.ACTIVE,
      currentStep = IdeStatusStep("implement", "Implement"),
      updatedAt = Instant.EPOCH,
      freshness = IdeStatusFreshness.FRESH,
      summary = "Work",
      workflowId = workflowId,
      execution = execution,
    ),
    repositoryRoot = "/repo",
  )
}
