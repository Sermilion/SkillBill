package dev.skillbill.desktop.core.common

/** Sink for bounded, attributed observability records; adapters decide where they go. */
interface Diagnostics {
  fun record(category: String)

  fun recordRequest(category: String, fields: Map<String, Number>)
}

/**
 * Whether the app measures read timings and signal latency. `:app` computes it once from `SKILL_BILL_DESKTOP_DIAGNOSTICS=1` or
 * `-Dskillbill.desktop.diagnostics=true`, so no other module reads process globals.
 */
data class DiagnosticsSwitch(val enabled: Boolean)
