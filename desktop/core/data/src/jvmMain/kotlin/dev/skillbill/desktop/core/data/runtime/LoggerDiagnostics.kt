package dev.skillbill.desktop.core.data.runtime

import dev.skillbill.desktop.core.common.Diagnostics
import dev.skillbill.desktop.core.common.di.AppScope
import me.tatarka.inject.annotations.Inject
import software.amazon.lastmile.kotlin.inject.anvil.ContributesBinding
import software.amazon.lastmile.kotlin.inject.anvil.SingleIn

private const val MAX_FIELDS = 12
private const val MAX_FIELD_NAME = 32

/** Writes diagnostics to the platform logger with bounded fields, as `desktop-tray` does. */
@Inject
@SingleIn(AppScope::class)
@ContributesBinding(AppScope::class)
class LoggerDiagnostics : Diagnostics {
  private val logger = System.getLogger("dev.skillbill.desktop")

  override fun record(category: String) = logger.log(System.Logger.Level.WARNING, "desktop:$category")

  override fun recordRequest(category: String, fields: Map<String, Number>) {
    val bounded = fields.entries.take(MAX_FIELDS).joinToString(" ") { (name, value) -> "${name.take(MAX_FIELD_NAME)}=${value.toLong()}" }
    logger.log(System.Logger.Level.WARNING, "desktop:$category $bounded")
  }
}
