package skillbill.infrastructure.sqlite.core.schema

internal object AgentBuildRunsKeys {
  const val TABLE: String = "agent_build_runs"
  const val BUILD_ID: String = "build_id"
  const val RUN_ID: String = "run_id"
  const val PARENT_RUN_ID: String = "parent_run_id"
  const val REPOSITORY_IDENTITY: String = "repository_identity"
  const val COMMAND: String = "command"
  const val SHORT_COMMAND: String = "short_command"
  const val TOOL: String = "tool"
  const val PROJECT_KIND: String = "project_kind"
  const val PID: String = "pid"
  const val STARTED_AT: String = "started_at"
  const val ENDED_AT: String = "ended_at"
  const val OUTCOME: String = "outcome"
  const val EXIT_CODE: String = "exit_code"
  const val RUN_INDEX: String = "idx_agent_build_runs_run"
  const val PARENT_INDEX: String = "idx_agent_build_runs_parent"
  const val HISTORY_INDEX: String = "idx_agent_build_runs_history"
}
