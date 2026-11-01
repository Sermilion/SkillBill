package skillbill.engine.featuretask.runloop.durable

import skillbill.application.idestatus.AgentActivityStampWriter
import skillbill.engine.featuretask.lifecycle.branch.FeatureTaskRuntimeBranchSetupRunner
import skillbill.engine.featuretask.runner.TestFeatureTaskRuntimeRunLoopEntry
import skillbill.engine.featuretask.runner.WORKFLOW_ID
import skillbill.engine.featuretask.runner.runnerHarness
import skillbill.engine.featuretask.runner.testDecompositionPlanner
import skillbill.idestatus.model.AgentActivityLabel
import skillbill.ports.diagnostics.NoopRuntimeDiagnostics
import java.time.Clock
import java.time.Instant
import java.time.ZoneOffset
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.time.TestTimeSource

class DurablePlanningActivityTest {
  @Test
  fun `planning launch forwards observed output to its goal without a child continuation`() {
    val harness = runnerHarness()
    val clock = Clock.fixed(Instant.parse("2026-10-26T15:00:00Z"), ZoneOffset.UTC)
    val launch =
      FeatureTaskRuntimeRunLoopDurableLaunch(
        FeatureTaskRuntimeBranchSetupRunner(harness.recorder, harness.gitOperations),
        AgentActivityStampWriter(harness.io.database, clock, NoopRuntimeDiagnostics, TestTimeSource()),
        WorktreeEditJournalWriter(harness.io.database, clock, NoopRuntimeDiagnostics, harness.gitOperations),
        TestFeatureTaskRuntimeRunLoopEntry(),
        testDecompositionPlanner(),
      )
    val request =
      harness.request().copy(
        goalContinuation = null,
        activityParentWorkflowId = "wfgr-planning-parent",
      )

    launch.observation(request, "preplan").activityStampSink.stamp(AgentActivityLabel.STDOUT)

    val stamps = harness.io.database.activityStamps
    assertNull(request.goalContinuation)
    assertEquals(assertNotNull(stamps[WORKFLOW_ID]), stamps["wfgr-planning-parent"])
    assertEquals(clock.instant(), stamps["wfgr-planning-parent"]?.recordedAt)
  }
}
