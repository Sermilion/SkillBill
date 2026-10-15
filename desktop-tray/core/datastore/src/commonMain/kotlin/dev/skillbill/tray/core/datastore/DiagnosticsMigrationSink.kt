package dev.skillbill.tray.core.datastore

import dev.skillbill.tray.core.common.Diagnostics
import dev.skillbill.tray.core.common.di.AppScope
import me.tatarka.inject.annotations.Inject
import software.amazon.lastmile.kotlin.inject.anvil.ContributesBinding
import software.amazon.lastmile.kotlin.inject.anvil.SingleIn

/** Turns every discarded legacy preference into one attributed diagnostics record. */
@Inject
@SingleIn(AppScope::class)
@ContributesBinding(AppScope::class)
class DiagnosticsMigrationSink(private val diagnostics: Diagnostics) : SettingsMigrationSink {
  override fun report(report: SettingsMigrationReport) {
    diagnostics.record(
      when (report.key) {
        LEGACY_CLI_OVERRIDE -> "legacy_cli_preference_discarded"
        LEGACY_POLL_SECONDS -> "legacy_poll_preference_discarded"
        else -> "legacy_preference_discarded"
      },
    )
  }
}
