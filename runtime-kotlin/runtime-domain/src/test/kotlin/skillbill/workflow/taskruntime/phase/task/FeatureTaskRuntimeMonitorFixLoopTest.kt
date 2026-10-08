package skillbill.workflow.taskruntime.phase.task

import skillbill.workflow.model.validation.FeatureTaskRuntimeVerdict
import skillbill.workflow.taskruntime.model.phase.FeatureTaskRuntimeNextPhase
import skillbill.workflow.taskruntime.model.phase.FeatureTaskRuntimeTransitionResult
import skillbill.workflow.taskruntime.model.skeleton.SkeletonDefinition
import skillbill.workflow.taskruntime.validation.FeatureTaskRuntimeTransitionFunction
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertTrue

class FeatureTaskRuntimeMonitorFixLoopTest {
  private val declaration = SkeletonDefinition.STANDALONE.declaration()

  @Test
  fun `a monitor failure routes to monitor_fix and counts each traversal`() {
    assertEquals(
      FeatureTaskRuntimeNextPhase.Next(phaseId = MONITOR_FIX, loopId = MONITOR_FIX_LOOP, edgeIteration = 1),
      resolved(MONITOR, FeatureTaskRuntimeVerdict.CI_FAILED, edgeIterationCount = 0),
    )
    assertEquals(
      FeatureTaskRuntimeNextPhase.Next(phaseId = MONITOR_FIX, loopId = MONITOR_FIX_LOOP, edgeIteration = 3),
      resolved(MONITOR, FeatureTaskRuntimeVerdict.CI_FAILED, edgeIterationCount = 2),
    )
  }

  @Test
  fun `exactly three monitor_fix traversals run and the fourth CI failure blocks`() {
    val traversals =
      (0 until MAX_ATTEMPTS).map { count -> resolved(MONITOR, FeatureTaskRuntimeVerdict.CI_FAILED, count) }

    assertTrue(traversals.all { it is FeatureTaskRuntimeNextPhase.Next && it.loopId == MONITOR_FIX_LOOP })
    assertEquals(
      FeatureTaskRuntimeNextPhase.TerminalBlock(
        loopId = MONITOR_FIX_LOOP,
        edgeIteration = MAX_ATTEMPTS,
        unresolvedVerdict = FeatureTaskRuntimeVerdict.CI_FAILED,
      ),
      resolved(MONITOR, FeatureTaskRuntimeVerdict.CI_FAILED, edgeIterationCount = MAX_ATTEMPTS),
    )
  }

  @Test
  fun `a completed fix goes back through commit_push before the monitor re-checks`() {
    assertEquals(
      FeatureTaskRuntimeNextPhase.Next(phaseId = COMMIT_PUSH, loopId = MONITOR_FIX_COMMIT_LOOP, edgeIteration = 1),
      resolved(MONITOR_FIX, FeatureTaskRuntimeVerdict.ADVANCE, edgeIterationCount = 0),
    )
  }

  @Test
  fun `a passing monitor advances past the end of the pipeline`() {
    assertEquals(
      FeatureTaskRuntimeNextPhase.TerminalAdvance,
      resolved(MONITOR, FeatureTaskRuntimeVerdict.ADVANCE, edgeIterationCount = 0),
    )
  }

  private fun resolved(
    phaseId: String,
    verdict: FeatureTaskRuntimeVerdict,
    edgeIterationCount: Int,
  ): FeatureTaskRuntimeNextPhase {
    val result =
      FeatureTaskRuntimeTransitionFunction.nextTransition(
        declaration = declaration,
        currentPhaseId = phaseId,
        verdict = verdict,
        edgeIterationCount = edgeIterationCount,
      )
    return assertIs<FeatureTaskRuntimeTransitionResult.Resolved>(result).next
  }

  private companion object {
    const val MONITOR = "monitor"
    const val MONITOR_FIX = "monitor_fix"
    const val COMMIT_PUSH = "commit_push"
    const val MONITOR_FIX_LOOP = "monitor_fix"
    const val MONITOR_FIX_COMMIT_LOOP = "monitor_fix_commit"
    const val MAX_ATTEMPTS = 3
  }
}
