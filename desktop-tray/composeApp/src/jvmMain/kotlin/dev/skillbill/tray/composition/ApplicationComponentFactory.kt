package dev.skillbill.tray.composition

import dev.skillbill.tray.core.common.HostEnvironment
import dev.skillbill.tray.core.datastore.LegacyPreferencesSource
import dev.skillbill.tray.core.datastore.processLegacyPreferences

/** The real process: its environment, home directory, the app resources Compose points it at, and no working directory of its own. */
fun processHostEnvironment(): HostEnvironment = HostEnvironment(
  System.getenv(),
  System.getProperty("user.home"),
  workingDirectory = "",
  appResourcesDirectory = System.getProperty("compose.application.resources.dir"),
)

/** Creates the application's component; `main` calls it once and closes it through [ApplicationComponent.shutdown]. */
fun createApplicationComponent(
  host: HostEnvironment = processHostEnvironment(),
  legacySettings: LegacyPreferencesSource = processLegacyPreferences(),
): ApplicationComponent = ApplicationComponent::class.create(host, legacySettings)
