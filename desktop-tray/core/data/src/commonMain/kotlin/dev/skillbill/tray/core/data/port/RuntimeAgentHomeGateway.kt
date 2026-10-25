package dev.skillbill.tray.core.data.port

/** The runtime's discovery of agent account directories; blocking, so callers move it off the UI thread. */
interface RuntimeAgentHomeGateway {
  fun claudeConfigDirectories(): List<String>
}
