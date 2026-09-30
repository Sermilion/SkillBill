package skillbill.engine.featuretask.slotbaseline

import skillbill.application.telemetry.lifecycle.LifecycleTelemetryService
import skillbill.application.telemetry.model.PrDescriptionGeneratedRequest
import skillbill.application.telemetry.model.QualityCheckFinishedRequest
import skillbill.application.telemetry.model.QualityCheckStartedRequest
import skillbill.contracts.telemetry.TelemetryOutboxEvent
import skillbill.engine.EnabledRuntimeTelemetrySettingsProvider
import skillbill.ports.diagnostics.NoopRuntimeDiagnostics

internal object SlotBaselineMcpLifecycleCapture {
  private const val ROUTED_SKILL = "bill-kotlin-code-check"
  private const val DETECTED_STACK = "kotlin"
  private const val SCOPE_TYPE = "branch_diff"

  fun encodedFiles(): Map<String, String> {
    val home = SlotBaselineNormalizer.newTempHome()
    try {
      val database = SlotBaselineFullRunCapture.sqliteDatabase(home)
      val service =
        LifecycleTelemetryService(
          database,
          EnabledRuntimeTelemetrySettingsProvider,
          SlotBaselineFullRunCapture.sqliteClock,
          NoopRuntimeDiagnostics,
        )
      val started =
        service.qualityCheckStarted(
          QualityCheckStartedRequest(
            routedSkill = ROUTED_SKILL,
            detectedStack = DETECTED_STACK,
            fallback = false,
            scopeType = SCOPE_TYPE,
            initialFailureCount = 1,
            orchestrated = false,
          ),
        ).toPayload()
      service.qualityCheckFinished(
        QualityCheckFinishedRequest(
          sessionId = started["session_id"] as String,
          finalFailureCount = 0,
          iterations = 2,
          result = "pass",
          failingCheckNames = emptyList(),
          unsupportedReason = "",
          orchestrated = false,
          routedSkill = ROUTED_SKILL,
          detectedStack = DETECTED_STACK,
          fallback = false,
          scopeType = SCOPE_TYPE,
          initialFailureCount = 1,
          durationSeconds = 0,
        ),
      )
      service.prDescriptionGenerated(
        PrDescriptionGeneratedRequest(
          commitCount = 3,
          filesChangedCount = 8,
          wasEditedByUser = false,
          prCreated = false,
          prTitle = "SKILL-380 slot baseline",
          orchestrated = false,
        ),
      )
      return mapOf(
        SlotBaselinePaths.QUALITY_CHECK_STARTED to TelemetryOutboxEvent.QUALITY_CHECK_STARTED,
        SlotBaselinePaths.QUALITY_CHECK_FINISHED to TelemetryOutboxEvent.QUALITY_CHECK_FINISHED,
        SlotBaselinePaths.PR_DESCRIPTION_GENERATED to TelemetryOutboxEvent.PR_DESCRIPTION_GENERATED,
      ).entries.associate { (fileName, event) ->
        "${SlotBaselinePaths.MCP_LIFECYCLE}/$fileName" to
          SlotBaselineJson.encode(SlotBaselineSqlite.latestOutboxPayload(database.resolveDbPath(), event.wireValue))
      }
    } finally {
      home.toFile().deleteRecursively()
    }
  }
}
