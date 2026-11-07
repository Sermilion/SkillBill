package skillbill.ports.agentrun.model

import skillbill.ports.agentrun.activity.model.AgentRunActivity

interface AgentRunActivityOutputSink : AgentRunOutputSink {
  val capturesActivity: Boolean

  fun activity(event: AgentRunActivity)

  fun raw(
    stream: AgentRunOutputStream,
    text: String,
    subtaskId: Int? = null,
  )
}
