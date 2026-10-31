package skillbill.infrastructure.sqlite.core.migration.area.idestatus

import skillbill.infrastructure.sqlite.core.migration.DatabaseColumnMigrations
import java.sql.Connection
import java.sql.ResultSet

internal const val REVIEW_RUN_ID_COLUMN: String = "review_run_id"

internal const val REVIEW_RUN_ID_ASSIGNMENT: String = "$REVIEW_RUN_ID_COLUMN = COALESCE(?, $REVIEW_RUN_ID_COLUMN)"

internal fun recordStandaloneReviewRunId(connection: Connection) {
  DatabaseColumnMigrations.ensureColumn(connection, "standalone_phase_status", REVIEW_RUN_ID_COLUMN, "TEXT")
}

internal fun ResultSet.readReviewRunId(): String? = getString(REVIEW_RUN_ID_COLUMN)
