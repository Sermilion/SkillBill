package dev.skillbill.desktop.app.tray

import dev.skillbill.desktop.core.common.Diagnostics

internal object TrayDiagnostics {
  @Volatile private var sink: Diagnostics? = null
  fun bind(diagnostics: Diagnostics) { sink = diagnostics }
  fun record(category: String) { sink?.record(category) }
}
