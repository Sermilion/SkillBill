package skillbill.ports.agentrun

import java.time.Duration

/**
 * Stops the agent-run child processes this JVM started, so cleanup that inspects an owned worktree is not racing a
 * repair child that can still commit into it.
 */
fun interface AgentRunChildDrain {
  /** Returns true when no agent-run child started by this JVM is still alive after at most [timeout]. */
  fun drain(timeout: Duration): Boolean
}
