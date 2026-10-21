package dev.skillbill.tray.core.common

/**
 * The process facts data and preference adapters resolve paths from. [workingDirectory] may be empty, which means the
 * process working directory. [appResourcesDirectory] is where the packaged app's own resources live, among them its build
 * identity record; null when the process was not started with one.
 */
data class HostEnvironment(
  val variables: Map<String, String>,
  val userHome: String,
  val workingDirectory: String,
  val appResourcesDirectory: String? = null,
)
