package skillbill.infrastructure.sqlite.core.migration.area.idestatus

import skillbill.error.shellcontent.invalidIdeStatusSchemaError
import skillbill.ports.idestatus.model.StandaloneMonitorCleanup
import skillbill.ports.idestatus.model.StandaloneMonitorTarget
import skillbill.ports.monitor.model.MonitorRetentionReason
import java.sql.Connection
import java.sql.ResultSet

internal const val MONITOR_PR_KEY_COLUMN: String = "monitor_pr_key"
private const val MONITOR_PR_URL_COLUMN = "monitor_pr_url"
private const val MONITOR_HEAD_REPOSITORY_COLUMN = "monitor_head_repository"
private const val MONITOR_HEAD_REF_COLUMN = "monitor_head_ref"
private const val MONITOR_CALLER_ROOT_COLUMN = "monitor_caller_root"
private const val MONITOR_WORKTREE_PATH_COLUMN = "monitor_worktree_path"
private const val MONITOR_CLEANUP_DISPOSITION_COLUMN = "monitor_cleanup_disposition"
private const val MONITOR_CLEANUP_REASON_COLUMN = "monitor_cleanup_reason"
private const val MONITOR_CLEANUP_DETAIL_COLUMN = "monitor_cleanup_detail"
private const val CLEANUP_RELEASED = "released"
private const val CLEANUP_RETAINED = "retained"

internal val MONITOR_TARGET_COLUMNS: List<String> =
  listOf(
    MONITOR_PR_KEY_COLUMN,
    MONITOR_PR_URL_COLUMN,
    MONITOR_HEAD_REPOSITORY_COLUMN,
    MONITOR_HEAD_REF_COLUMN,
    MONITOR_CALLER_ROOT_COLUMN,
    MONITOR_WORKTREE_PATH_COLUMN,
  )

internal val MONITOR_TARGET_COLUMN_LIST: String = MONITOR_TARGET_COLUMNS.joinToString(", ")

internal val MONITOR_TARGET_PLACEHOLDERS: String = MONITOR_TARGET_COLUMNS.joinToString(", ") { "?" }

internal val MONITOR_CLEANUP_COLUMNS: List<String> =
  listOf(
    MONITOR_CLEANUP_DISPOSITION_COLUMN,
    MONITOR_CLEANUP_REASON_COLUMN,
    MONITOR_CLEANUP_DETAIL_COLUMN,
  )

internal val MONITOR_CLEANUP_ASSIGNMENTS: String =
  MONITOR_CLEANUP_COLUMNS.joinToString(", ") { column -> "$column = COALESCE(?, $column)" }

internal fun recordStandaloneMonitorTarget(connection: Connection) {
  (MONITOR_TARGET_COLUMNS + MONITOR_CLEANUP_COLUMNS).forEach { column ->
    val exists =
      connection.prepareStatement(
        "SELECT 1 FROM pragma_table_info('standalone_phase_status') WHERE name = ?",
      ).use { statement ->
        statement.setString(1, column)
        statement.executeQuery().use { it.next() }
      }
    if (!exists) {
      val constraint =
        if (column == MONITOR_CLEANUP_DISPOSITION_COLUMN) {
          " CHECK ($column IN ('$CLEANUP_RELEASED', '$CLEANUP_RETAINED'))"
        } else {
          ""
        }
      connection.createStatement().use { statement ->
        statement.execute("ALTER TABLE standalone_phase_status ADD COLUMN $column TEXT$constraint")
      }
    }
  }
}

internal fun StandaloneMonitorTarget?.columnValues(): List<String?> =
  listOf(
    this?.pullRequestKey,
    this?.pullRequestUrl,
    this?.headRepository,
    this?.headRef,
    this?.callerRoot,
    this?.worktreePath,
  )

internal fun StandaloneMonitorCleanup?.columnValues(): List<String?> =
  when (this) {
    null -> listOf(null, null, null)
    StandaloneMonitorCleanup.Released -> listOf(CLEANUP_RELEASED, null, null)
    is StandaloneMonitorCleanup.Retained -> listOf(CLEANUP_RETAINED, reason.wireValue, detail)
  }

internal fun ResultSet.readMonitorTarget(sourceLabel: String): StandaloneMonitorTarget? {
  val values = MONITOR_TARGET_COLUMNS.map(::getString)
  if (values.all { it == null }) return null
  val missing = MONITOR_TARGET_COLUMNS.filterIndexed { index, _ -> values[index].isNullOrBlank() }
  if (missing.isNotEmpty()) {
    throw invalidIdeStatusSchemaError(
      sourceLabel,
      MONITOR_PR_KEY_COLUMN,
      "Incomplete monitor target: ${missing.joinToString()}.",
    )
  }
  return try {
    fun column(name: String): String = requireNotNull(getString(name))
    StandaloneMonitorTarget(
      pullRequestKey = column(MONITOR_PR_KEY_COLUMN),
      pullRequestUrl = column(MONITOR_PR_URL_COLUMN),
      headRepository = column(MONITOR_HEAD_REPOSITORY_COLUMN),
      headRef = column(MONITOR_HEAD_REF_COLUMN),
      callerRoot = column(MONITOR_CALLER_ROOT_COLUMN),
      worktreePath = column(MONITOR_WORKTREE_PATH_COLUMN),
    )
  } catch (failure: IllegalArgumentException) {
    throw invalidIdeStatusSchemaError(
      sourceLabel,
      MONITOR_PR_KEY_COLUMN,
      "Invalid monitor target: ${failure.message}",
      failure,
    )
  }
}

internal fun ResultSet.readMonitorCleanup(
  sourceLabel: String,
  hasTarget: Boolean,
): StandaloneMonitorCleanup? {
  val disposition = getString(MONITOR_CLEANUP_DISPOSITION_COLUMN)
  val reason = getString(MONITOR_CLEANUP_REASON_COLUMN)
  val detail = getString(MONITOR_CLEANUP_DETAIL_COLUMN)
  if (disposition == null && reason == null && detail == null) return null

  fun invalid(message: String): Nothing =
    throw invalidIdeStatusSchemaError(sourceLabel, MONITOR_CLEANUP_DISPOSITION_COLUMN, message)
  if (!hasTarget) invalid("Monitor cleanup is recorded without a monitor target.")
  return when (disposition) {
    CLEANUP_RELEASED ->
      if (reason == null && detail == null) {
        StandaloneMonitorCleanup.Released
      } else {
        invalid("A released monitor cleanup carries no reason or detail.")
      }
    CLEANUP_RETAINED ->
      StandaloneMonitorCleanup.Retained(
        MonitorRetentionReason.fromWire(reason) ?: invalid("Unknown retention reason '$reason'."),
        detail?.takeIf(String::isNotBlank) ?: invalid("A retained monitor cleanup needs a detail."),
      )
    else -> invalid("Invalid monitor cleanup disposition '$disposition'.")
  }
}
