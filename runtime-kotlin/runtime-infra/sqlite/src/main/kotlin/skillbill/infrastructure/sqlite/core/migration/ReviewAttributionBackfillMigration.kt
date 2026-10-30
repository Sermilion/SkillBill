package skillbill.infrastructure.sqlite.core.migration

import skillbill.contracts.review.ReviewVerificationSignalKeys
import skillbill.infrastructure.sqlite.core.ops.bindAll
import skillbill.review.attribution.EXECUTION_MODE_DELEGATED
import skillbill.review.attribution.canonicalPackSkillNames
import skillbill.review.attribution.canonicalPlatformSlugs
import skillbill.review.attribution.resolveCanonicalRoutedSkill
import skillbill.review.attribution.resolveCanonicalScope
import skillbill.review.attribution.resolveCanonicalStack
import skillbill.review.model.UNRESOLVED_ATTRIBUTION
import java.sql.Connection

internal object ReviewAttributionBackfillMigration {
  private val requiredRawColumns = setOf("routed_skill", "detected_stack", "detected_scope")

  fun apply(connection: Connection) {
    if (!DatabaseColumnMigrations.reviewRunsTableExists(connection)) return
    if (!DatabaseColumnMigrations.reviewRunColumnNames(connection).containsAll(requiredRawColumns)) return
    DatabaseColumnMigrations.ensureReviewRunColumns(connection)
    backfillCanonicals(connection)
  }

  fun backfillExecutionModes(connection: Connection) {
    connection.createStatement().use { statement ->
      statement.execute(
        """
        UPDATE review_runs
        SET execution_mode = '$EXECUTION_MODE_DELEGATED'
        WHERE (execution_mode IS NULL OR execution_mode = '')
          AND specialist_reviews IS NOT NULL AND specialist_reviews != ''
        """.trimIndent(),
      )
      statement.execute(
        """
        UPDATE review_runs
        SET execution_mode = '$UNRESOLVED_ATTRIBUTION'
        WHERE execution_mode IS NULL OR execution_mode = ''
        """.trimIndent(),
      )
    }
  }

  private fun backfillCanonicals(connection: Connection) {
    val pending = pendingRows(connection)
    if (pending.isEmpty()) return
    connection.prepareStatement(
      """
      UPDATE review_runs
      SET routed_skill_canonical = ?,
          detected_stack_canonical = ?,
          detected_scope_canonical = ?,
          detected_scope_detail = ?
      WHERE review_run_id = ?
      """.trimIndent(),
    ).use { statement ->
      var batched = 0
      pending.forEach { row ->
        val resolved = row.resolve()
        if (resolved == row.stored) return@forEach
        statement.bindAll(
          resolved.routedSkill,
          resolved.stack,
          resolved.scope,
          resolved.scopeDetail,
          row.reviewRunId,
        )
        statement.addBatch()
        batched += 1
      }
      if (batched > 0) statement.executeBatch()
    }
  }

  private fun pendingRows(connection: Connection): List<PendingRow> =
    connection.prepareStatement(
      """
      SELECT review_run_id, routed_skill, detected_stack, detected_scope,
             routed_skill_canonical, detected_stack_canonical, detected_scope_canonical,
             detected_scope_detail
      FROM review_runs
      WHERE routed_skill_canonical = '$UNRESOLVED_ATTRIBUTION'
         OR detected_stack_canonical = '$UNRESOLVED_ATTRIBUTION'
         OR detected_scope_canonical = '$UNRESOLVED_ATTRIBUTION'
      """.trimIndent(),
    ).use { statement ->
      statement.executeQuery().use { rows ->
        buildList {
          while (rows.next()) {
            add(
              PendingRow(
                reviewRunId = rows.getString(ReviewVerificationSignalKeys.REVIEW_RUN_ID),
                raw =
                  RawAttribution(
                    routedSkill = rows.getString("routed_skill"),
                    stack = rows.getString("detected_stack"),
                    scope = rows.getString("detected_scope"),
                  ),
                stored =
                  CanonicalAttributionColumns(
                    routedSkill = rows.getString("routed_skill_canonical"),
                    stack = rows.getString("detected_stack_canonical"),
                    scope = rows.getString("detected_scope_canonical"),
                    scopeDetail = rows.getString("detected_scope_detail"),
                  ),
              ),
            )
          }
        }
      }
    }

  private class RawAttribution(val routedSkill: String?, val stack: String?, val scope: String?)

  private data class CanonicalAttributionColumns(
    val routedSkill: String?,
    val stack: String?,
    val scope: String?,
    val scopeDetail: String?,
  )

  private class PendingRow(
    val reviewRunId: String,
    val raw: RawAttribution,
    val stored: CanonicalAttributionColumns,
  ) {
    fun resolve(): CanonicalAttributionColumns {
      val scope = resolveCanonicalScope(raw.scope)
      val scopeWasUnresolved = stored.scope == UNRESOLVED_ATTRIBUTION
      return CanonicalAttributionColumns(
        routedSkill =
          stored.routedSkill.takeUnless { it == UNRESOLVED_ATTRIBUTION }
            ?: resolveCanonicalRoutedSkill(raw.routedSkill, canonicalPackSkillNames).canonical,
        stack =
          stored.stack.takeUnless { it == UNRESOLVED_ATTRIBUTION }
            ?: resolveCanonicalStack(raw.stack, canonicalPlatformSlugs).canonical,
        scope = if (scopeWasUnresolved) scope.canonical else stored.scope,
        scopeDetail = if (scopeWasUnresolved) scope.detail else stored.scopeDetail,
      )
    }
  }
}
