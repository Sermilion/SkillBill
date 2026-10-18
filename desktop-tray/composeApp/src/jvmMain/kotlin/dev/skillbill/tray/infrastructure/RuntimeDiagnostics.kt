package dev.skillbill.tray.infrastructure

object RuntimeDiagnostics {
  private val logger = System.getLogger("dev.skillbill.tray")
  fun record(category: String) { logger.log(System.Logger.Level.WARNING, "desktop:$category") }
  fun recordRequest(category: String, fields: Map<String, Number>) {
    val bounded = fields.entries.take(12).joinToString(" ") { (name, value) ->
      "${name.take(32)}=${value.toLong().coerceAtLeast(0)}"
    }
    logger.log(System.Logger.Level.WARNING, "desktop:$category $bounded")
  }
}
