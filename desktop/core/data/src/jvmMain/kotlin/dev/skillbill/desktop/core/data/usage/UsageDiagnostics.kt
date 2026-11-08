package dev.skillbill.desktop.core.data.usage

import dev.skillbill.desktop.core.common.Diagnostics

internal object NoopUsageDiagnostics : Diagnostics {
  override fun record(category: String) = Unit

  override fun recordRequest(category: String, fields: Map<String, Number>) = Unit
}

internal object UsageDiagnosticCategories {
  const val REQUEST_STARTED = "usage.coordinator.request_started"
  const val AUTH_SUSPENDED = "usage.coordinator.auth_suspended"
  const val AUTH_RELEASED = "usage.coordinator.auth_released"
  const val UNSUPPORTED = "usage.coordinator.unsupported"
  const val THROTTLED = "usage.coordinator.throttled"
  const val RETRY_DEFERRED = "usage.coordinator.retry_deferred"
  const val RETAINED_STALE = "usage.coordinator.retained_stale"
  const val RETAINED_RECOVERED = "usage.coordinator.retained_recovered"
  const val DISPOSED = "usage.coordinator.disposed"
  const val PARSE_REJECTED = "usage.codex.parse_rejected"
  const val INPUT_REJECTED = "usage.codex.input_rejected"
  const val SOURCE_UNSUPPORTED = "usage.codex.unsupported"
  const val SOURCE_CLEANUP_FAILED = "usage.codex.cleanup_failed"
}

internal object UsageDiagnosticFields {
  const val FAILURE_COUNT = "failure_count"
  const val RETRY_DELAY_MS = "retry_delay_ms"
  const val COOLDOWN_REMAINING_MS = "cooldown_remaining_ms"
  const val SOURCE_FLOOR_MS = "source_floor_ms"
  const val REASON_CODE = "reason_code"
  const val RETAINED_METER_COUNT = "retained_meter_count"
}
