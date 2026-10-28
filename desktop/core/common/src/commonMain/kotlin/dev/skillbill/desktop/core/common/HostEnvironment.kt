package dev.skillbill.desktop.core.common

/** The process facts data and preference adapters resolve paths from. [workingDirectory] may be empty for the process directory. */
data class HostEnvironment(
  val variables: Map<String, String>,
  val userHome: String,
  val workingDirectory: String,
)
