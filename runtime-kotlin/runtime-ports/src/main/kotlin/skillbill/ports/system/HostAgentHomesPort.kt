package skillbill.ports.system

/**
 * Agent home variables a host chose for the runs it launches, keyed by
 * [skillbill.contracts.system.AgentHomeEnvironmentKeys]. A choice wins over the agent environment a run recorded; an
 * empty value removes the variable so the agent uses its default home.
 */
fun interface HostAgentHomesPort {
  /** The host's choice right now; read at each launch so a changed setting applies to the next run. */
  fun current(): Map<String, String>
}
