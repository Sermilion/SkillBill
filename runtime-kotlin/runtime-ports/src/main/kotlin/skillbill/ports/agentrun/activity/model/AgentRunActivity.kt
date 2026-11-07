package skillbill.ports.agentrun.activity.model

enum class AgentRunActivityKind(val wireValue: String) {
  READ("read"),
  WRITE("write"),
  SEARCH("search"),
  COMMAND("command"),
  MCP("mcp"),
  NOTE("note"),
  TOOL("tool"),
  STREAM("stream"),
  ERROR("error"),
}

enum class AgentRunActivityState(val wireValue: String) {
  STARTED("started"),
  COMPLETED("completed"),
  FAILED("failed"),
}

data class AgentRunActivity(
  val id: String,
  val kind: AgentRunActivityKind,
  val text: String,
  val agentId: String,
  val path: String? = null,
  val state: AgentRunActivityState = AgentRunActivityState.COMPLETED,
  val durationMs: Long? = null,
  val exitCode: Int? = null,
  val subtaskId: Int? = null,
)
