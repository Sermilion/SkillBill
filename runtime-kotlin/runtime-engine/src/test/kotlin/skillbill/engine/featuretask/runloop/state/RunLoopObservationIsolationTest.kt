package skillbill.engine.featuretask.runloop.state

import skillbill.engine.featuretask.model.core.FeatureTaskRuntimeRunReport
import skillbill.engine.featuretask.runloop.core.FeatureTaskRuntimeRunLoopSession
import skillbill.engine.featuretask.slot.state.PhaseResumeRules
import skillbill.workflow.taskruntime.model.handoff.assembly.FeatureTaskRuntimePhaseOutput
import skillbill.workflow.taskruntime.model.phase.FeatureTaskRuntimeTransitionDeclaration
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertSame
import kotlin.test.assertTrue

class RunLoopObservationIsolationTest {
  @Test
  fun `observations cannot recover mutable owners or change after owner transitions`() {
    val state = progress()
    val session = FeatureTaskRuntimeRunLoopSession(null, null)
    val owner = coupledRunTransitionOwner(state, session)
    val observedProgress = state.progressSnapshot
    val observedSession = session.sessionSnapshot()

    owner.recordSyntheticUpstreamCompletion(FeatureTaskRuntimePhaseOutput("implement", 1, "{}"))
    owner.observeResolvedBranchForCheckpoint("feature/owned")

    assertNull(observedProgress as? FeatureTaskRuntimeRunState)
    assertNull(observedSession as? FeatureTaskRuntimeRunLoopSession)
    assertFalse(observedProgress.phase("implement").completed)
    assertEquals(emptyList(), observedProgress.outputs())
    assertNull(observedSession.resolvedBranch)
    assertTrue(state.progressSnapshot.phase("implement").completed)
    assertEquals("feature/owned", session.sessionSnapshot().resolvedBranch)
  }

  @Test
  fun `terminal report collections cannot mutate the owned session`() {
    val state = progress()
    val session = FeatureTaskRuntimeRunLoopSession(null, null)
    val owner = coupledRunTransitionOwner(state, session)
    val completed = mutableListOf("preplan", "implement")
    owner.transitionTerminalBlocked(
      FeatureTaskRuntimeRunReport.Blocked("SKILL-384", "workflow", "SMALL", "audit", "gap", completed, null),
    )
    val observed = session.sessionSnapshot()
    completed.clear()
    val report = requireNotNull(observed.blocked)
    (report.completedPhaseIds as MutableList<String>).clear()

    assertEquals(listOf("preplan", "implement"), requireNotNull(session.blocked).completedPhaseIds)
    assertEquals(listOf("preplan", "implement"), requireNotNull(observed.blocked).completedPhaseIds)
  }

  @Test
  fun `one progress owner cannot be rebound to another session`() {
    val state = progress()
    val session = FeatureTaskRuntimeRunLoopSession(null, null)
    val owner = coupledRunTransitionOwner(state, session)
    assertSame(owner, coupledRunTransitionOwner(state, session))
    assertFailsWith<IllegalStateException> {
      coupledRunTransitionOwner(state, FeatureTaskRuntimeRunLoopSession(null, null))
    }
  }

  private fun progress() =
    FeatureTaskRuntimeRunState(
      initialRecords = emptyMap(),
      transitions = FeatureTaskRuntimeTransitionDeclaration(listOf("implement", "audit")),
      resumeRulesFn = { PhaseResumeRules.None },
    )
}
