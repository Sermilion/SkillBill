package skillbill.contracts.system

object AgentHomeEnvironmentKeys {
  const val CLAUDE_CONFIG_DIR: String = "CLAUDE_CONFIG_DIR"
  const val CODEX_HOME: String = "CODEX_HOME"
  val ALL: Set<String> = setOf(CLAUDE_CONFIG_DIR, CODEX_HOME)
}
