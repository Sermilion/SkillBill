package skillbill.engine.featuretask.runner

import skillbill.application.RecordingLifecycleTelemetryRepository
import skillbill.application.telemetry.lifecycle.LifecycleTelemetryService
import skillbill.engine.featuretask.lifecycle.core.blockedReasonOf
import skillbill.engine.featuretask.lifecycle.core.emitFeatureTaskRuntimeFinishedError
import skillbill.engine.featuretask.model.core.FeatureTaskRuntimeFinishedTelemetryContext
import skillbill.engine.featuretask.model.core.FeatureTaskRuntimeRunReport
import skillbill.ports.diagnostics.NoopRuntimeDiagnostics
import java.time.Clock
import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class FeatureTaskRuntimeTerminalFailureReasonTest {
  @Test
  fun `a paused run reports why it stopped instead of an empty reason`() {
    val reason =
      blockedReasonOf(
        FeatureTaskRuntimeRunReport.Paused(
          issueKey = RUNNER_TEST_ISSUE_KEY,
          workflowId = WORKFLOW_ID,
          featureSize = "MEDIUM",
          pausedPhase = "validate",
          pauseReason = "operator pause requested",
          resumableStep = "validate",
          completedPhaseIds = listOf("implement"),
          resolvedBranch = "codex/$RUNNER_TEST_ISSUE_KEY",
        ),
      )

    assertTrue(
      reason.contains("operator pause requested"),
      "every paused session looked alike while the pause reason was dropped: '$reason'",
    )
  }

  @Test
  fun `an unhandled terminal names the failure class and never the exception message`() {
    val lifecycle = RecordingLifecycleTelemetryRepository()
    val database = RuntimeFakeDatabaseSessionFactory(InMemoryRuntimeWorkflowRepository(), lifecycle)

    emitFeatureTaskRuntimeFinishedError(
      LifecycleTelemetryService(
        database,
        EnabledRuntimeTelemetrySettingsProvider,
        Clock.systemUTC(),
        NoopRuntimeDiagnostics,
      ),
      FeatureTaskRuntimeFinishedTelemetryContext(
        telemetrySessionId = SESSION_ID,
        phaseOutcomes = { mapOf("implement" to "completed") },
        reviewFixIterationCount = { 0 },
      ),
      mapOf("implement" to "completed"),
      IllegalStateException("/Users/someone/repo/SKILL-236 spec text leaked here"),
    )

    val reason = lifecycle.finishedRecords.single().blockedReason
    assertTrue(
      reason.contains("IllegalStateException"),
      "an operator cannot tell eleven distinct failures apart without the class: '$reason'",
    )
    assertFalse(reason.contains("/Users/"), "an exception message can carry paths and must not ride the wire")
    assertFalse(reason.contains("spec text leaked here"))
  }
}
