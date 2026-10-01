package skillbill.infrastructure.sqlite.workflow.goalrunner.runner

import skillbill.contracts.JsonCodec
import skillbill.error.shellcontent.InvalidWorkflowStateSchemaError
import skillbill.goalrunner.model.GOAL_PAUSE_REASON_RUNNER_INTERRUPTED
import skillbill.goalrunner.model.GoalRunnerControlState
import skillbill.infrastructure.sqlite.core.ops.bindAll
import skillbill.ports.goalrunner.GoalRunnerControlRepository
import skillbill.ports.goalrunner.runner.model.GoalRunnerOutOfBandAcceptance
import skillbill.ports.goalrunner.runner.model.GoalRunnerReviewPolicy
import java.sql.Connection

internal fun goalRunnerControlSchemaError(
  reason: String,
  cause: Throwable? = null,
): Nothing = throw InvalidWorkflowStateSchemaError("Goal runner control state: $reason", cause)

internal class GoalRunnerControlStore(
  private val connection: Connection,
) : GoalRunnerControlRepository {
  override fun controlState(parentWorkflowId: String): GoalRunnerControlState =
    selectJson(parentWorkflowId, "control_state_json")?.let(::decodeControlState) ?: GoalRunnerControlState()

  override fun persistControlState(
    parentWorkflowId: String,
    state: GoalRunnerControlState,
  ): GoalRunnerControlState {
    val source =
      selectJson(parentWorkflowId, "control_state_json")?.let { raw ->
        decodeControlState(raw)
        JsonCodec.anyToStringAnyMap(JsonCodec.parseValue(raw))
          ?: goalRunnerControlSchemaError("control state must be an object")
      }
    connection.prepareStatement(
      """
      INSERT INTO goal_runner_controls (parent_workflow_id, control_state_json)
      VALUES (?, ?)
      ON CONFLICT(parent_workflow_id) DO UPDATE SET
        control_state_json = excluded.control_state_json,
        updated_at = CURRENT_TIMESTAMP
      """.trimIndent(),
    ).use { statement ->
      statement.bindAll(parentWorkflowId, JsonCodec.mapToJsonString(state.toArtifactMap(source)))
      statement.executeUpdate()
    }
    return state
  }

  override fun clearRunnerInterruptedPause(parentWorkflowId: String): GoalRunnerControlState {
    val state = controlState(parentWorkflowId)
    if (state.pauseReason != GOAL_PAUSE_REASON_RUNNER_INTERRUPTED) return state
    return persistControlState(
      parentWorkflowId,
      state.copy(
        paused = false,
        pauseRequested = false,
        pauseConsumed = false,
        pauseReason = null,
        pausedAt = null,
      ),
    )
  }

  override fun clearControlState(parentWorkflowId: String) {
    val existing = controlState(parentWorkflowId)

    val retained =
      GoalRunnerControlState(
        executionLease = existing.executionLease,
        activeDurationMs = existing.activeDurationMs,
        activeDurationAsOf = existing.activeDurationAsOf,
        currentSubtaskId = existing.currentSubtaskId,
        subtaskActiveDurationMs = existing.subtaskActiveDurationMs,
        subtaskActiveDurationAsOf = existing.subtaskActiveDurationAsOf,
      )
    if (retained != GoalRunnerControlState()) {
      persistControlState(parentWorkflowId, retained)
      return
    }
    connection.prepareStatement(
      """
      UPDATE goal_runner_controls
      SET control_state_json = NULL, updated_at = CURRENT_TIMESTAMP
      WHERE parent_workflow_id = ?
      """.trimIndent(),
    ).use { statement ->
      statement.bindAll(parentWorkflowId)
      statement.executeUpdate()
    }
  }

  override fun reviewPolicy(parentWorkflowId: String): GoalRunnerReviewPolicy? =
    selectJson(parentWorkflowId, "review_policy_json")?.let { decodeReviewPolicy(it) }

  override fun persistReviewPolicy(
    parentWorkflowId: String,
    policy: GoalRunnerReviewPolicy,
  ): GoalRunnerReviewPolicy {
    connection.prepareStatement(
      """
      INSERT INTO goal_runner_controls (parent_workflow_id, review_policy_json)
      VALUES (?, ?)
      ON CONFLICT(parent_workflow_id) DO UPDATE SET
        review_policy_json = excluded.review_policy_json,
        updated_at = CURRENT_TIMESTAMP
      """.trimIndent(),
    ).use { statement ->
      statement.bindAll(parentWorkflowId, JsonCodec.mapToJsonString(policy.toArtifactMap()))
      statement.executeUpdate()
    }
    return policy
  }

  override fun outOfBandAcceptances(parentWorkflowId: String): Map<Int, GoalRunnerOutOfBandAcceptance> =
    selectJson(parentWorkflowId, "out_of_band_acceptances_json")
      ?.let(::decodeAcceptances)
      .orEmpty()

  override fun persistOutOfBandAcceptance(
    parentWorkflowId: String,
    acceptance: GoalRunnerOutOfBandAcceptance,
  ): GoalRunnerOutOfBandAcceptance {
    val merged = outOfBandAcceptances(parentWorkflowId) + (acceptance.subtaskId to acceptance)
    connection.prepareStatement(
      """
      INSERT INTO goal_runner_controls (parent_workflow_id, out_of_band_acceptances_json)
      VALUES (?, ?)
      ON CONFLICT(parent_workflow_id) DO UPDATE SET
        out_of_band_acceptances_json = excluded.out_of_band_acceptances_json,
        updated_at = CURRENT_TIMESTAMP
      """.trimIndent(),
    ).use { statement ->
      statement.bindAll(
        parentWorkflowId,
        JsonCodec.valueToJsonElement(
          merged.values.sortedBy(GoalRunnerOutOfBandAcceptance::subtaskId)
            .map(GoalRunnerOutOfBandAcceptance::toArtifactMap),
        ).toString(),
      )
      statement.executeUpdate()
    }
    return acceptance
  }

  override fun clearOutOfBandAcceptances(parentWorkflowId: String) {
    connection.prepareStatement(
      """
      UPDATE goal_runner_controls
      SET out_of_band_acceptances_json = '[]', updated_at = CURRENT_TIMESTAMP
      WHERE parent_workflow_id = ?
      """.trimIndent(),
    ).use { statement ->
      statement.bindAll(parentWorkflowId)
      statement.executeUpdate()
    }
  }

  private fun selectJson(
    parentWorkflowId: String,
    column: String,
  ): String? =
    connection.prepareStatement(
      "SELECT $column FROM goal_runner_controls WHERE parent_workflow_id = ?",
    ).use { statement ->
      statement.bindAll(parentWorkflowId)
      statement.executeQuery().use { rows ->
        if (rows.next()) rows.getString(1)?.takeIf(String::isNotBlank) else null
      }
    }
}
