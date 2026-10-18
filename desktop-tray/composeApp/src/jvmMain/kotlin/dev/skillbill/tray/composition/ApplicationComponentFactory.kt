package dev.skillbill.tray.composition

import dev.skillbill.tray.core.common.HostEnvironment
import dev.skillbill.tray.core.datastore.LegacyPreferencesSource
import dev.skillbill.tray.core.datastore.processLegacyPreferences

/** The real process: its environment, home directory and no working directory of its own. */
fun processHostEnvironment(): HostEnvironment = HostEnvironment(System.getenv(), System.getProperty("user.home"), workingDirectory = "")

/** Creates the application's component; `main` calls it once and closes it through [ApplicationComponent.shutdown]. */
fun createApplicationComponent(
  host: HostEnvironment = processHostEnvironment(),
  legacySettings: LegacyPreferencesSource = processLegacyPreferences(),
): ApplicationComponent = ApplicationComponent::class.create(host, legacySettings)
