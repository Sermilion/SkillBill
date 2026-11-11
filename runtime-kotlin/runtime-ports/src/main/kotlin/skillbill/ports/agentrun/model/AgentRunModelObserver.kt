package skillbill.ports.agentrun.model

/** Receives provider-reported model identities. A null report clears the previous launch's identity. */
fun interface AgentRunModelObserver {
  fun reported(model: String?)
}
