package skillbill.infrastructure.sqlite

import skillbill.contracts.SharedPayloadKeys
import skillbill.contracts.decomposition.DecompositionPlanningPayloadKeys
import skillbill.contracts.persistence.DesktopCommitSignal
import skillbill.contracts.persistence.DesktopReadSchema
import skillbill.contracts.workflow.identity.task.FeatureTaskRuntimeGoalContinuationArtifactPayloadKeys
import skillbill.infrastructure.sqlite.core.ops.bindAll
import skillbill.infrastructure.sqlite.core.ops.storedDesktopGeneration
import skillbill.ports.diagnostics.RuntimeDiagnostics
import skillbill.workflow.engine.model.DurableWorkflowArtifactFamily
import skillbill.workflow.model.FeatureTaskRouteScope
import java.io.IOException
import java.nio.channels.FileChannel
import java.nio.file.AtomicMoveNotSupportedException
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardCopyOption.ATOMIC_MOVE
import java.nio.file.StandardCopyOption.REPLACE_EXISTING
import java.nio.file.StandardOpenOption.CREATE
import java.nio.file.StandardOpenOption.WRITE
import java.sql.Connection
import java.sql.SQLException

private val desktopCommitSignalPublishLock = Any()

internal fun desktopCommitSignalPath(databasePath: Path): Path =
  databasePath.toAbsolutePath().normalize().let { absolute ->
    absolute.resolveSibling("${absolute.fileName}${DesktopCommitSignal.FILE_SUFFIX}")
  }

internal fun publishDesktopCommitSignal(
  databasePath: Path,
  connection: Connection,
  changedTopics: Set<String>,
  diagnostics: RuntimeDiagnostics,
) {
  val signalPath = desktopCommitSignalPath(databasePath)
  val parent = signalPath.parent ?: return
  var pending: Path? = null
  try {
    synchronized(desktopCommitSignalPublishLock) {
      Files.createDirectories(parent)
      val lockPath = signalPath.resolveSibling("${signalPath.fileName}.lock")
      FileChannel.open(lockPath, CREATE, WRITE).use { channel ->
        channel.lock().use {
          val payload = connection.desktopCommitSignalPayload(changedTopics, diagnostics) ?: return
          val temporary = Files.createTempFile(parent, "skillbill-commit-", ".pending")
          pending = temporary
          Files.writeString(temporary, payload)
          try {
            Files.move(temporary, signalPath, ATOMIC_MOVE, REPLACE_EXISTING)
          } catch (_: AtomicMoveNotSupportedException) {
            diagnostics.warning("skillbill sqlite: desktop commit signal used a non-atomic move")
            Files.move(temporary, signalPath, REPLACE_EXISTING)
          }
          pending = null
        }
      }
    }
  } catch (failure: IOException) {
    diagnostics.warning("skillbill sqlite: desktop commit signal publication failed", failure)
  } catch (failure: SQLException) {
    diagnostics.warning("skillbill sqlite: desktop commit revision read failed", failure)
  } catch (failure: SecurityException) {
    diagnostics.warning("skillbill sqlite: desktop commit signal publication was denied", failure)
  } finally {
    pending?.let { temporary ->
      try {
        Files.deleteIfExists(temporary)
      } catch (failure: IOException) {
        diagnostics.warning("skillbill sqlite: temporary desktop commit signal cleanup failed", failure)
      } catch (failure: SecurityException) {
        diagnostics.warning("skillbill sqlite: temporary desktop commit signal cleanup was denied", failure)
      }
    }
  }
}

private fun Connection.desktopTopicRevisions(): Map<String, Long> =
  prepareStatement("SELECT topic, revision FROM ${DesktopReadSchema.REVISIONS_TABLE}").use { statement ->
    statement.executeQuery().use { rows ->
      buildMap {
        while (rows.next()) put(rows.getString(1), rows.getLong(2))
      }
    }
  }

private fun Connection.desktopCommitSignalPayload(
  changedTopics: Set<String>,
  diagnostics: RuntimeDiagnostics,
): String? {
  val revisions = desktopTopicRevisions()
  if (DesktopReadSchema.SIGNALLED_TOPICS.any { topic -> revisions[topic]?.let { it >= 0 } != true }) {
    diagnostics.warning("skillbill sqlite: desktop commit signal lacked a topic revision")
    return null
  }
  val generation = storedDesktopGeneration()
  if (generation == null) {
    diagnostics.warning("skillbill sqlite: desktop commit signal lacked a store generation")
    return null
  }
  val header =
    listOf("${DesktopCommitSignal.GENERATION_LABEL}${DesktopCommitSignal.REVISION_SEPARATOR}$generation") +
      DesktopReadSchema.SIGNALLED_TOPICS.map { topic ->
        "$topic${DesktopCommitSignal.REVISION_SEPARATOR}${revisions.getValue(topic)}"
      }
  val mapper = DesktopWorkIdMapper(this)
  val changed =
    DesktopReadSchema.SIGNALLED_TOPICS.filter { topic -> topic in changedTopics }.associateWith { topic ->
      desktopChangedLine(this, topic, revisions.getValue(topic), mapper)
    }.toMutableMap()

  fun payload() = (header + changed.values).joinToString(separator = "\n", postfix = "\n")
  while (payload().length > DesktopCommitSignal.MAX_SIGNAL_CHARS) {
    val longest =
      changed.filterValues { line -> !isDesktopOverflowLine(line) }.maxByOrNull { it.value.length } ?: break
    changed[longest.key] = desktopOverflowLine(longest.key, revisions.getValue(longest.key))
  }
  return payload()
}

internal fun desktopChangedLine(
  connection: Connection,
  topic: String,
  current: Long,
  mapper: DesktopWorkIdMapper,
): String {
  val keysByRevision = connection.changeItemsAtOrBelow(topic, current)
  val ids = linkedSetOf<String>()
  var base = current
  while (base > 0) {
    val next = revisionBoardIds(keysByRevision[base], mapper)?.let { ids + it }
    if (next == null || next.size > DesktopCommitSignal.MAX_CHANGED_IDS_PER_TOPIC) break
    ids += next
    base -= 1
  }
  return if (base == current) {
    desktopOverflowLine(topic, current)
  } else {
    changedLine(topic, base, ids.joinToString(DesktopCommitSignal.CHANGED_ID_SEPARATOR))
  }
}

private fun revisionBoardIds(
  keys: List<String>?,
  mapper: DesktopWorkIdMapper,
): List<String>? {
  val mapped = keys?.map(mapper::boardIds) ?: return null
  return if (mapped.any { it == null }) null else mapped.filterNotNull().flatten()
}

internal fun desktopOverflowLine(
  topic: String,
  current: Long,
): String = changedLine(topic, current - 1, DesktopCommitSignal.OVERFLOW_MARKER)

internal fun isDesktopOverflowLine(line: String): Boolean =
  line.endsWith("${DesktopCommitSignal.CHANGED_BASE_SEPARATOR}${DesktopCommitSignal.OVERFLOW_MARKER}")

private fun changedLine(
  topic: String,
  base: Long,
  ids: String,
): String =
  "$topic${DesktopCommitSignal.CHANGED_SUFFIX}${DesktopCommitSignal.REVISION_SEPARATOR}" +
    "$base${DesktopCommitSignal.CHANGED_BASE_SEPARATOR}$ids"

private fun Connection.changeItemsAtOrBelow(
  topic: String,
  current: Long,
): Map<Long, List<String>> =
  prepareStatement(
    "SELECT revision, work_id FROM ${DesktopReadSchema.CHANGE_ITEMS_TABLE} " +
      "WHERE topic = ? AND revision <= ? ORDER BY revision DESC",
  ).use { statement ->
    statement.setString(1, topic)
    statement.setLong(2, current)
    statement.executeQuery().use { rows ->
      val grouped = linkedMapOf<Long, MutableList<String>>()
      while (rows.next()) grouped.getOrPut(rows.getLong(1)) { mutableListOf() }.add(rows.getString(2))
      grouped
    }
  }

internal class DesktopWorkIdMapper(
  private val connection: Connection,
) {
  private val cache = mutableMapOf<String, List<String>?>()

  fun boardIds(key: String): List<String>? =
    cache.getOrPut(key) {
      workflowBoardId(key)?.let(::listOf) ?: invocationBoardIds(key)
    }

  private fun workflowBoardId(key: String): String? =
    when {
      exists("SELECT 1 FROM goal_issue_progress WHERE parent_workflow_id = ?", key) -> key
      isGoalChild(key) -> goalChildParent(key)
      exists(
        "SELECT 1 FROM feature_task_workflows WHERE workflow_id = ? " +
          "UNION ALL SELECT 1 FROM feature_verify_workflows WHERE workflow_id = ?",
        key,
        key,
      ) -> key
      else -> null
    }

  private fun invocationBoardIds(key: String): List<String>? {
    if (!exists(INVOCATION_EXISTS, key, key)) return null
    val workflowId = singleString(INVOCATION_WORKFLOW, key, key)
    return listOfNotNull(key, workflowId?.let(::workflowBoardId)).distinct()
  }

  private fun isGoalChild(key: String): Boolean =
    exists(
      "SELECT 1 FROM feature_task_execution_identities WHERE workflow_id = ? AND route_scope = ?",
      key,
      FeatureTaskRouteScope.GOAL_CHILD.wireValue,
    )

  private fun goalChildParent(key: String): String? =
    singleString(CONTINUATION_PARENT, key) ?: singleString(DECOMPOSITION_PARENT, key)

  private fun exists(
    sql: String,
    vararg values: String,
  ): Boolean =
    connection.prepareStatement(sql).use { statement ->
      statement.bindAll(*values)
      statement.executeQuery().use { rows -> rows.next() }
    }

  private fun singleString(
    sql: String,
    vararg values: String,
  ): String? =
    connection.prepareStatement(sql).use { statement ->
      statement.bindAll(*values)
      statement.executeQuery().use { rows -> if (rows.next()) rows.getString(1) else null }
    }

  private companion object {
    const val INVOCATION_EXISTS =
      "SELECT 1 FROM standalone_phase_status WHERE invocation_id = ? " +
        "UNION ALL SELECT 1 FROM ide_status_workflow_execution WHERE invocation_id = ?"
    const val INVOCATION_WORKFLOW =
      "SELECT workflow_id FROM (SELECT workflow_id FROM standalone_phase_status WHERE invocation_id = ? " +
        "UNION ALL SELECT workflow_id FROM ide_status_workflow_execution WHERE invocation_id = ?) " +
        "WHERE workflow_id IS NOT NULL LIMIT 1"
    val CONTINUATION_PARENT =
      "SELECT CASE WHEN json_valid(artifacts_json) THEN json_extract(artifacts_json, " +
        "'$.${DurableWorkflowArtifactFamily.FEATURE_TASK_RUNTIME_GOAL_CONTINUATION.label()}" +
        ".${FeatureTaskRuntimeGoalContinuationArtifactPayloadKeys.PARENT_WORKFLOW_ID}') END " +
        "FROM feature_task_workflows WHERE workflow_id = ?"
    val DECOMPOSITION_PARENT =
      "SELECT parent.workflow_id FROM feature_task_workflows AS parent " +
        "JOIN json_each(CASE WHEN json_valid(parent.artifacts_json) THEN parent.artifacts_json ELSE '{}' END, " +
        "'$.${DurableWorkflowArtifactFamily.DECOMPOSITION_RUNTIME.label()}" +
        ".${DecompositionPlanningPayloadKeys.SUBTASKS}') AS subtask " +
        "WHERE json_extract(subtask.value, '$.${SharedPayloadKeys.WORKFLOW_ID}') = ? LIMIT 1"
  }
}
