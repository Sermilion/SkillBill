package skillbill.ports.telemetry.lifecycle

import skillbill.telemetry.model.FeatureVerifyFinishedRecord
import skillbill.telemetry.model.FeatureVerifyStartedRecord

interface FeatureVerifyLifecycleTelemetryRepository {
  fun featureVerifyStarted(
    record: FeatureVerifyStartedRecord,
    level: String,
  )

  fun featureVerifyFinished(
    record: FeatureVerifyFinishedRecord,
    level: String,
  )

  /** Returns the stored stale reason for a Verify session, or null when there is none. */
  fun featureVerifyStaleReason(sessionId: String): String?
}
