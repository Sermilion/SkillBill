package skillbill.infrastructure.sqlite.workflow

import skillbill.contracts.JsonCodec
import skillbill.error.core.catchingNonCancellation
import skillbill.infrastructure.sqlite.core.ops.bindAll
import skillbill.ports.idestatus.model.MonitorCiCheckBucket
import skillbill.ports.idestatus.model.MonitorCiCycleCheck
import skillbill.ports.idestatus.model.MonitorCiCycleOutcome
import skillbill.ports.idestatus.model.MonitorCiCycleRecord
import skillbill.ports.idestatus.monitor.MonitorCiCycleRepository
import java.sql.Connection
import java.sql.ResultSet
import java.time.Instant
import java.time.format.DateTimeParseException

internal class MonitorCiCycleStore(
  private val connection: Connection,
) : MonitorCiCycleRepository {
  override fun upsert(
    workflowId: String,
    record: MonitorCiCycleRecord,
  ) {
    require(workflowId.isNotBlank()) { "workflowId is required." }
    connection.prepareStatement(
      """
      INSERT INTO monitor_ci_cycles (
        workflow_id, cycle, phase_id, started_at, observed_at, finished_at, outcome, head_sha,
        pull_request_number, pull_request_url, conflict_base_branch, repair_commit_sha, checks_json
      )
      VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
      ON CONFLICT(workflow_id, cycle) DO UPDATE SET
        phase_id = excluded.phase_id,
        started_at = excluded.started_at,
        observed_at = excluded.observed_at,
        finished_at = excluded.finished_at,
        outcome = excluded.outcome,
        head_sha = excluded.head_sha,
        pull_request_number = excluded.pull_request_number,
        pull_request_url = excluded.pull_request_url,
        conflict_base_branch = excluded.conflict_base_branch,
        repair_commit_sha = excluded.repair_commit_sha,
        checks_json = excluded.checks_json
      """.trimIndent(),
    ).use { statement ->
      statement.bindAll(
        workflowId,
        record.cycle,
        record.phaseId,
        record.startedAt.toString(),
        record.observedAt.toString(),
        record.finishedAt?.toString(),
        record.outcome?.wireValue,
        record.headSha,
        record.pullRequestNumber,
        record.pullRequestUrl,
        record.conflictBaseBranch,
        record.repairCommitSha,
        encodeChecks(record.checks.take(MAX_CHECKS)),
      )
      statement.executeUpdate()
    }
  }

  override fun latest(workflowId: String): MonitorCiCycleRecord? = newest(workflowId, 1).firstOrNull()

  override fun cycles(
    workflowId: String,
    limit: Int,
  ): List<MonitorCiCycleRecord> = newest(workflowId, limit).reversed()

  private fun newest(
    workflowId: String,
    limit: Int,
  ): List<MonitorCiCycleRecord> {
    if (workflowId.isBlank() || limit <= 0) return emptyList()
    val rows =
      connection.prepareStatement(
        """
        SELECT cycle, phase_id, started_at, observed_at, finished_at, outcome, head_sha, pull_request_number,
          pull_request_url, conflict_base_branch, repair_commit_sha, checks_json
        FROM monitor_ci_cycles
        WHERE workflow_id = ?
        """.trimIndent(),
      ).use { statement ->
        statement.bindAll(workflowId)
        statement.executeQuery().use { result ->
          buildList { while (result.next()) recordFrom(result)?.let(::add) }
        }
      }
    return rows
      .sortedWith(compareByDescending<MonitorCiCycleRecord> { it.startedAt }.thenByDescending { it.cycle })
      .take(limit)
  }

  private fun recordFrom(rows: ResultSet): MonitorCiCycleRecord? {
    val startedAt = parseInstant(rows.getString("started_at")) ?: return null
    return MonitorCiCycleRecord(
      cycle = rows.getInt("cycle"),
      phaseId = rows.getString("phase_id"),
      startedAt = startedAt,
      observedAt = parseInstant(rows.getString("observed_at")) ?: startedAt,
      finishedAt = parseInstant(rows.getString("finished_at")),
      outcome = MonitorCiCycleOutcome.fromWire(rows.getString("outcome")),
      headSha = rows.getString("head_sha"),
      pullRequestNumber = rows.getInt("pull_request_number").takeUnless { rows.wasNull() },
      pullRequestUrl = rows.getString("pull_request_url"),
      conflictBaseBranch = rows.getString("conflict_base_branch"),
      repairCommitSha = rows.getString("repair_commit_sha"),
      checks = decodeChecks(rows.getString("checks_json")),
    )
  }

  private fun encodeChecks(checks: List<MonitorCiCycleCheck>): String =
    JsonCodec.valueToJsonString(
      checks.map { check ->
        buildMap {
          put(MonitorCiCycleCheckKeys.NAME, check.name)
          put(MonitorCiCycleCheckKeys.BUCKET, check.bucket.wireValue)
          put(MonitorCiCycleCheckKeys.LINK, check.link)
          check.startedAt?.let { put(MonitorCiCycleCheckKeys.STARTED_AT, it.toString()) }
          check.completedAt?.let { put(MonitorCiCycleCheckKeys.COMPLETED_AT, it.toString()) }
        }
      },
    )

  private fun decodeChecks(json: String?): List<MonitorCiCycleCheck> {
    if (json.isNullOrBlank()) return emptyList()
    val entries = catchingNonCancellation { JsonCodec.anyToStringAnyMapList(JsonCodec.parseValue(json)) }.getOrNull()
    return entries.orEmpty().mapNotNull { entry ->
      val name = entry[MonitorCiCycleCheckKeys.NAME] as? String ?: return@mapNotNull null
      val bucketWire = entry[MonitorCiCycleCheckKeys.BUCKET] as? String
      val bucket = MonitorCiCheckBucket.fromWire(bucketWire) ?: return@mapNotNull null
      MonitorCiCycleCheck(
        name = name,
        bucket = bucket,
        link = (entry[MonitorCiCycleCheckKeys.LINK] as? String).orEmpty(),
        startedAt = parseInstant(entry[MonitorCiCycleCheckKeys.STARTED_AT] as? String),
        completedAt = parseInstant(entry[MonitorCiCycleCheckKeys.COMPLETED_AT] as? String),
      )
    }
  }

  private fun parseInstant(raw: String?): Instant? {
    if (raw.isNullOrBlank()) return null
    return try {
      Instant.parse(raw)
    } catch (_: DateTimeParseException) {
      null
    }
  }

  private companion object {
    const val MAX_CHECKS: Int = 200
  }
}

internal object MonitorCiCycleCheckKeys {
  const val NAME: String = "name"
  const val BUCKET: String = "bucket"
  const val LINK: String = "link"
  const val STARTED_AT: String = "started_at"
  const val COMPLETED_AT: String = "completed_at"
}
