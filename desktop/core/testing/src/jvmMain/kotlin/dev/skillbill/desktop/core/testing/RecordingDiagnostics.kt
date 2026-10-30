package dev.skillbill.desktop.core.testing

import dev.skillbill.desktop.core.common.Diagnostics
import java.util.Collections

/** Records diagnostic categories in order. */
class RecordingDiagnostics : Diagnostics {
  val categories: MutableList<String> = Collections.synchronizedList(mutableListOf())

  override fun record(category: String) {
    categories += category
  }

  override fun recordRequest(category: String, fields: Map<String, Number>) {
    categories += category
  }
}
