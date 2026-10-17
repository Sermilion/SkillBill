package skillbill.infrastructure.launcher.process.launch

import me.tatarka.inject.annotations.Inject
import skillbill.ports.agentrun.AgentRunChildDrain
import java.time.Duration

@Inject
class JvmAgentRunChildDrain : AgentRunChildDrain {
  override fun drain(timeout: Duration): Boolean = JvmAgentRunProcessRunner.drainLiveProcesses(timeout.toMillis())
}
