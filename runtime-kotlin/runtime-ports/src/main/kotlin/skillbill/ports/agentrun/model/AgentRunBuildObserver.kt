package skillbill.ports.agentrun.model

import java.time.Instant

/** Observes build processes and the agent commands that start them during one launch. */
interface AgentRunBuildObserver {
  /** Reports the current build process samples at [observedAt]. */
  fun processes(
    observedAt: Instant,
    samples: List<AgentRunProcessSample>,
  )

  /** Reports a command event emitted by the agent. */
  fun command(event: AgentRunCommandEvent)

  /** Ends observation for the current agent process at [observedAt]. */
  fun finished(observedAt: Instant)

  companion object {
    val NONE: AgentRunBuildObserver =
      object : AgentRunBuildObserver {
        override fun processes(
          observedAt: Instant,
          samples: List<AgentRunProcessSample>,
        ) = Unit

        override fun command(event: AgentRunCommandEvent) = Unit

        override fun finished(observedAt: Instant) = Unit
      }
  }
}
