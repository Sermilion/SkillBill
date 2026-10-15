package dev.skillbill.tray.core.common

/** Sink for bounded, attributed observability records; adapters decide where they go. */
interface Diagnostics {
  fun record(category: String)
  fun recordRequest(category: String, fields: Map<String, Number>)
}
