package skillbill.engine.goalrunner.execution.core

import skillbill.engine.goalrunner.execution.support.GoalRunnerValidationQualityPendingState
import skillbill.engine.goalrunner.manifest.GoalRunnerManifestStoreDefaults
import skillbill.engine.goalrunner.model.GoalRunnerManifestState
import skillbill.goalrunner.model.GoalRunnerControlState
import skillbill.goalrunner.model.GoalRunnerExecutionLease
import java.nio.file.Path
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.CyclicBarrier
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import kotlin.test.Test
import kotlin.test.assertEquals

class GoalRunnerSessionIsolationTest {
  @Test
  fun `validation quality pending state does not share retry counters across concurrent runs`() {
    val store = ConcurrentControlManifestStore()
    val runA = GoalRunnerValidationQualityPendingState(store)
    val runB = GoalRunnerValidationQualityPendingState(store)
    val start = CyclicBarrier(2)
    val executor = Executors.newFixedThreadPool(2)
    try {
      val first =
        executor.submit {
          start.await(5, TimeUnit.SECONDS)
          runA.bind("workflow-a")
          repeat(40) { runA.incrementValidationQualityRetry(1) }
        }
      val second =
        executor.submit {
          start.await(5, TimeUnit.SECONDS)
          runB.bind("workflow-b")
          repeat(40) { runB.incrementValidationQualityRetry(2) }
        }
      first.get(10, TimeUnit.SECONDS)
      second.get(10, TimeUnit.SECONDS)
      runA.bind("workflow-a")
      runB.bind("workflow-b")
      assertEquals(40, runA.validationQualityRetryCount(1))
      assertEquals(40, runB.validationQualityRetryCount(2))
      assertEquals(0, runA.validationQualityRetryCount(2))
      assertEquals(0, runB.validationQualityRetryCount(1))
    } finally {
      executor.shutdownNow()
    }
  }
}

private class ConcurrentControlManifestStore : GoalRunnerManifestStoreDefaults() {
  private val controls = ConcurrentHashMap<String, GoalRunnerControlState>()

  override fun controlState(parentWorkflowId: String): GoalRunnerControlState =
    controls.getOrPut(parentWorkflowId) { GoalRunnerControlState() }

  override fun persistControlState(
    parentWorkflowId: String,
    state: GoalRunnerControlState,
  ): GoalRunnerControlState {
    controls[parentWorkflowId] = state
    return state
  }

  override fun loadByIssueKey(
    issueKey: String,
    repoRoot: Path?,
  ): GoalRunnerManifestState? = null

  override fun save(state: GoalRunnerManifestState): GoalRunnerManifestState = state

  override fun acquireExecutionLease(
    parentWorkflowId: String,
    lease: GoalRunnerExecutionLease,
    expectedOwnerToken: String?,
  ): Boolean = false

  override fun heartbeatExecutionLease(
    parentWorkflowId: String,
    lease: GoalRunnerExecutionLease,
  ): Boolean = false

  override fun releaseExecutionLease(
    parentWorkflowId: String,
    ownerToken: String,
    generation: Long,
  ): Boolean = false
}
