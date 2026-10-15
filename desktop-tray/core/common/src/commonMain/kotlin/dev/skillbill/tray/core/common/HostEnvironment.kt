package dev.skillbill.tray.core.common

/**
 * The process facts data and preference adapters resolve paths from. [workingDirectory] may be empty, which means the
 * process working directory.
 */
data class HostEnvironment(
  val variables: Map<String, String>,
  val userHome: String,
  val workingDirectory: String,
)
