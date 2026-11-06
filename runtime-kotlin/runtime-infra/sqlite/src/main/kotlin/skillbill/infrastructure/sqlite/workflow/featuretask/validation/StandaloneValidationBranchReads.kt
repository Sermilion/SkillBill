package skillbill.infrastructure.sqlite.workflow.featuretask.validation

import skillbill.error.shellcontent.invalidIdeStatusSchemaError
import skillbill.infrastructure.sqlite.core.ops.bindAll
import skillbill.infrastructure.sqlite.worklist.fractionalSecondsSql
import skillbill.ports.idestatus.StandaloneValidationBranchRepository
import skillbill.ports.idestatus.model.VALIDATION_BRANCH_RUN_LIMIT
import skillbill.ports.idestatus.model.WorkValidationBranchRun
import skillbill.workflow.time.parsePersistedInstantOrNull
import java.sql.Connection
import java.sql.ResultSet
import java.time.Instant

internal class StandaloneValidationBranchReads(
  private val connection: Connection,
) : StandaloneValidationBranchRepository {
  override fun readValidationBranchRuns(invocationId: String): List<WorkValidationBranchRun> =
    connection.prepareStatement(
      """
      SELECT peer.invocation_id, peer.run_sequence, peer.lifecycle_state, peer.started_at, peer.finished_at,
             peer.active_duration_ms, peer.launch_agent_id, peer.current_activity, peer.terminal_result
      FROM standalone_phase_status AS self
      JOIN standalone_phase_status AS peer
        ON peer.repository_identity = self.repository_identity
       AND peer.branch_correlation = self.branch_correlation
      WHERE self.invocation_id = ? AND peer.phase_id = ?
        AND self.branch_correlation IS NOT NULL AND self.branch_correlation <> 'HEAD'
      ORDER BY unixepoch(peer.started_at) DESC, ${fractionalSecondsSql("peer.started_at")} DESC,
               length(peer.run_sequence) DESC, peer.run_sequence DESC, peer.invocation_id DESC
      LIMIT ?
      """.trimIndent(),
    ).use { statement ->
      statement.bindAll(invocationId, VALIDATION_PHASE_ID, VALIDATION_BRANCH_RUN_LIMIT)
      statement.executeQuery().use { rows -> buildList { while (rows.next()) add(rows.toValidationBranchRun()) } }
    }

  private fun ResultSet.toValidationBranchRun(): WorkValidationBranchRun =
    WorkValidationBranchRun(
      invocationId = getString("invocation_id"),
      runSequence = getString("run_sequence"),
      lifecycleState = getString("lifecycle_state"),
      startedAt = validationInstant("started_at"),
      finishedAt = validationInstantOrNull("finished_at"),
      activeDurationMs = getLong("active_duration_ms").takeUnless { wasNull() },
      launchAgentId = getString("launch_agent_id"),
      currentActivity = getString("current_activity"),
      result = getString("terminal_result"),
    )

  private fun ResultSet.validationInstant(column: String): Instant =
    validationInstantOrNull(column)
      ?: throw invalidIdeStatusSchemaError("standalone validation branch run", column, "Invalid persisted timestamp.")

  private fun ResultSet.validationInstantOrNull(column: String): Instant? {
    val raw = getString(column) ?: return null
    return parsePersistedInstantOrNull(raw)
      ?: throw invalidIdeStatusSchemaError("standalone validation branch run", column, "Invalid persisted timestamp.")
  }

  private companion object {
    const val VALIDATION_PHASE_ID = "validation"
  }
}
