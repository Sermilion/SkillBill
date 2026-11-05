package skillbill.infrastructure.sqlite.operation

import skillbill.infrastructure.sqlite.core.ops.bindAll
import skillbill.ports.operation.model.ConfirmationExecution
import skillbill.ports.operation.model.ConfirmationExecutionState
import skillbill.ports.taskruntime.model.FeatureTaskRuntimeProcessIdentity
import java.sql.Connection
import java.sql.ResultSet
import java.sql.Statement

internal class SqliteConfirmationExecutions(
  private val connection: Connection,
) {
  fun insertAdmitted(
    token: String,
    launchId: String,
    owner: FeatureTaskRuntimeProcessIdentity,
  ): ConfirmationExecution {
    val executionId =
      connection.prepareStatement(
        """
        INSERT INTO operation_confirmation_executions (
          token, launch_id, state, admission_host, admission_boot, admission_pid, admission_birth
        ) VALUES (?, ?, ?, ?, ?, ?, ?)
        """.trimIndent(),
        Statement.RETURN_GENERATED_KEYS,
      ).use { statement ->
        statement.bindAll(
          token,
          launchId,
          ADMITTED,
          owner.hostIdentity,
          owner.bootIdentity,
          owner.pid,
          owner.processBirthToken,
        )
        statement.executeUpdate()
        statement.generatedKeys.use { keys ->
          check(keys.next()) { "The confirmation execution was not recorded." }
          keys.getLong(1)
        }
      }
    return ConfirmationExecution(executionId, token, launchId, ConfirmationExecutionState.ADMITTED, owner)
  }

  fun active(token: String): ConfirmationExecution? =
    connection.prepareStatement("$SELECT WHERE token = ? AND state != '$RETIRED'").use { statement ->
      statement.bindAll(token)
      statement.executeQuery().use { rows -> if (rows.next()) rows.execution() else null }
    }

  fun pendingCleanup(token: String): ConfirmationExecution? =
    connection.prepareStatement("$SELECT WHERE token = ? AND cleanup_pending = 1 ORDER BY execution_id DESC LIMIT 1")
      .use { statement ->
        statement.bindAll(token)
        statement.executeQuery().use { rows -> if (rows.next()) rows.execution() else null }
      }

  fun claim(
    token: String,
    launchId: String,
    invocationId: String,
    owner: FeatureTaskRuntimeProcessIdentity,
  ): Boolean =
    connection.prepareStatement(
      """
      UPDATE operation_confirmation_executions SET state = ?, invocation_id = ?, child_host = ?, child_boot = ?,
        child_pid = ?, child_birth = ?
      WHERE token = ? AND launch_id = ? AND state = ? AND invocation_id IS NULL
      """.trimIndent(),
    ).use { statement ->
      statement.bindAll(
        CLAIMED,
        invocationId,
        owner.hostIdentity,
        owner.bootIdentity,
        owner.pid,
        owner.processBirthToken,
        token,
        launchId,
        ADMITTED,
      )
      statement.executeUpdate() == 1
    }

  fun isClaimedBy(
    token: String,
    launchId: String,
    invocationId: String,
  ): Boolean =
    connection.prepareStatement(
      "SELECT 1 FROM operation_confirmation_executions WHERE token = ? AND launch_id = ? AND state = ? " +
        "AND invocation_id = ?",
    ).use { statement ->
      statement.bindAll(token, launchId, CLAIMED, invocationId)
      statement.executeQuery().use { it.next() }
    }

  fun retire(
    executionId: Long,
    cleanupPending: Boolean,
    evidence: String?,
  ): Boolean =
    connection.prepareStatement(
      "UPDATE operation_confirmation_executions SET state = ?, cleanup_pending = ?, result_evidence = ? " +
        "WHERE execution_id = ? AND state != ?",
    ).use { statement ->
      statement.bindAll(RETIRED, if (cleanupPending) 1 else 0, evidence, executionId, RETIRED)
      statement.executeUpdate() == 1
    }

  fun completeCleanup(executionId: Long) {
    connection.prepareStatement(
      "UPDATE operation_confirmation_executions SET cleanup_pending = 0 WHERE execution_id = ?",
    ).use { statement ->
      statement.bindAll(executionId)
      statement.executeUpdate()
    }
  }

  private fun ResultSet.execution(): ConfirmationExecution {
    val childHost = getString("child_host")
    return ConfirmationExecution(
      executionId = getLong("execution_id"),
      token = getString("token"),
      launchId = getString("launch_id"),
      state = stateOf(getString("state")),
      admissionOwner =
        FeatureTaskRuntimeProcessIdentity(
          getString("admission_host"),
          getString("admission_boot"),
          getLong("admission_pid"),
          getString("admission_birth"),
        ),
      invocationId = getString("invocation_id"),
      childOwner =
        childHost?.let {
          FeatureTaskRuntimeProcessIdentity(it, getString("child_boot"), getLong("child_pid"), getString("child_birth"))
        },
    )
  }

  private fun stateOf(wire: String): ConfirmationExecutionState =
    when (wire) {
      ADMITTED -> ConfirmationExecutionState.ADMITTED
      CLAIMED -> ConfirmationExecutionState.CLAIMED
      else -> ConfirmationExecutionState.RETIRED
    }

  private companion object {
    const val ADMITTED = "admitted"
    const val CLAIMED = "claimed"
    const val RETIRED = "retired"
    const val SELECT =
      "SELECT execution_id, token, launch_id, state, admission_host, admission_boot, admission_pid, " +
        "admission_birth, invocation_id, child_host, child_boot, child_pid, child_birth " +
        "FROM operation_confirmation_executions"
  }
}
