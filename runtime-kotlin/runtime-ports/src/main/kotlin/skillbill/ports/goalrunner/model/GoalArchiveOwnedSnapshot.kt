package skillbill.ports.goalrunner.model

import skillbill.ports.workflow.model.WorkflowStateRecord

data class GoalArchiveEnvelope(
  val contractVersion: String,
  val issueKey: String,
  val featureBranch: String,
  val commitSha: String,
  val exportedAt: String,
)

data class GoalArchiveOwnedSnapshot(
  val workflows: List<WorkflowStateRecord>,
  val sharedPreplans: List<SharedGoalPreplanCheckpoint>,
  val subtaskPlans: List<GoalSubtaskPlanCheckpoint>,
  val legacyPreparations: List<GoalPlanningPreparationRecord>,
  val ownedTables: GoalArchiveOwnedTables = GoalArchiveOwnedTables(),
)

data class GoalArchiveOwnedTables(
  private val tables: Map<String, List<GoalArchiveTableRow>> = emptyMap(),
) {
  fun tableNames(): Set<String> = tables.keys

  fun rows(tableName: String): List<GoalArchiveTableRow>? = tables[tableName]

  fun orderedTables(): List<Pair<String, List<GoalArchiveTableRow>>> =
    tables.entries.map { entry -> entry.key to entry.value }

  companion object {
    fun of(tables: Map<String, List<GoalArchiveTableRow>>): GoalArchiveOwnedTables =
      GoalArchiveOwnedTables(LinkedHashMap(tables))
  }
}

data class GoalArchiveTableRow(
  private val columns: Map<String, Any?>,
) {
  fun orderedEntries(): List<Pair<String, Any?>> = columns.entries.map { entry -> entry.key to entry.value }
}
