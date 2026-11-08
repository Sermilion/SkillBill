package skillbill.ports.work

fun interface LaunchAgentHomesPort {
  /** The agent-home values a launch started now would receive, keyed by `AgentHomeEnvironmentKeys`; "" means unset. */
  fun agentHomes(): Map<String, String>
}
