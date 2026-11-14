package skillbill.infrastructure.sqlite.workflow

import skillbill.contracts.JsonCodec
import skillbill.contracts.config.projectchecks.ProjectCheckCategoryConfig
import skillbill.contracts.config.projectchecks.ProjectCheckCommandParserFindings
import skillbill.contracts.config.projectchecks.ProjectCheckCommandParserMetadata
import skillbill.contracts.config.projectchecks.ProjectCheckCommandSnapshot
import skillbill.contracts.config.projectchecks.ProjectCheckCommandSnapshotKeys
import skillbill.contracts.config.projectchecks.ProjectCheckConfigKeys
import skillbill.infrastructure.sqlite.core.migration.area.monitor.MONITOR_REPAIR_VALIDATION_SNAPSHOTS_TABLE
import skillbill.infrastructure.sqlite.core.ops.bindAll
import skillbill.ports.config.projectchecks.ProjectCheckCommandSnapshotValidator
import skillbill.ports.idestatus.model.MonitorRepairValidationSnapshotKey
import skillbill.ports.idestatus.monitor.MonitorRepairValidationSnapshotRepository
import java.sql.Connection

internal class MonitorRepairValidationSnapshotStore(
  private val connection: Connection,
  private val validator: ProjectCheckCommandSnapshotValidator,
) : MonitorRepairValidationSnapshotRepository {
  override fun accept(
    key: MonitorRepairValidationSnapshotKey,
    snapshot: ProjectCheckCommandSnapshot,
  ): Boolean {
    require(key.ownerId.isNotBlank()) { "ownerId is required." }
    require(key.phaseId.isNotBlank()) { "phaseId is required." }
    require(key.cycle > 0) { "cycle must be positive." }
    val frozen = snapshot.frozen()
    val encoded = JsonCodec.valueToJsonString(encodeSnapshot(frozen))
    validator.validate(encoded, SNAPSHOT_SOURCE_LABEL)
    return connection.prepareStatement(
      """
      INSERT OR IGNORE INTO $MONITOR_REPAIR_VALIDATION_SNAPSHOTS_TABLE (
        workflow_id, cycle, phase_id, snapshot_json
      ) VALUES (?, ?, ?, ?)
      """.trimIndent(),
    ).use { statement ->
      statement.bindAll(key.ownerId, key.cycle, key.phaseId, encoded)
      statement.executeUpdate() == 1
    }
  }

  override fun load(key: MonitorRepairValidationSnapshotKey): ProjectCheckCommandSnapshot? {
    if (key.ownerId.isBlank() || key.phaseId.isBlank() || key.cycle <= 0) return null
    val json =
      connection.prepareStatement(
        """
        SELECT snapshot_json
        FROM $MONITOR_REPAIR_VALIDATION_SNAPSHOTS_TABLE
        WHERE workflow_id = ? AND cycle = ? AND phase_id = ?
        """.trimIndent(),
      ).use { statement ->
        statement.bindAll(key.ownerId, key.cycle, key.phaseId)
        statement.executeQuery().use { result -> if (result.next()) result.getString("snapshot_json") else null }
      }
    if (json == null) return null
    return validator.validate(json, SNAPSHOT_SOURCE_LABEL)
  }

  override fun latestCycle(
    ownerId: String,
    phaseId: String,
  ): Int? {
    if (ownerId.isBlank() || phaseId.isBlank()) return null
    return connection.prepareStatement(
      """
      SELECT cycle
      FROM $MONITOR_REPAIR_VALIDATION_SNAPSHOTS_TABLE
      WHERE workflow_id = ? AND phase_id = ?
      ORDER BY cycle DESC
      LIMIT 1
      """.trimIndent(),
    ).use { statement ->
      statement.bindAll(ownerId, phaseId)
      statement.executeQuery().use { result -> if (result.next()) result.getInt("cycle") else null }
    }
  }

  private fun encodeSnapshot(snapshot: ProjectCheckCommandSnapshot): Map<String, Any?> =
    linkedMapOf(
      ProjectCheckCommandSnapshotKeys.CONTRACT_VERSION to snapshot.contractVersion,
      ProjectCheckCommandSnapshotKeys.PROJECT_ID to snapshot.projectId,
      ProjectCheckCommandSnapshotKeys.BUILD_COMMANDS to encodeCategory(snapshot.buildCommands),
      ProjectCheckCommandSnapshotKeys.VALIDATION_COMMANDS to encodeCategory(snapshot.validationCommands),
      ProjectCheckCommandSnapshotKeys.PARSER_METADATA to encodeParserMetadata(snapshot.parserMetadata),
      ProjectCheckCommandSnapshotKeys.GRADLE_WRAPPER to snapshot.gradleWrapper,
    )

  private fun encodeCategory(category: ProjectCheckCategoryConfig?): Any? =
    category?.let {
      mapOf(
        ProjectCheckConfigKeys.COMMANDS to
          it.commands.map { command ->
            mapOf(
              ProjectCheckConfigKeys.ARGV to command.argv,
              ProjectCheckConfigKeys.WORKING_DIRECTORY to command.workingDirectory,
            )
          },
      )
    }

  private fun encodeParserMetadata(metadata: ProjectCheckCommandParserMetadata?): Any? =
    metadata?.let { meta ->
      mapOf(
        ProjectCheckCommandSnapshotKeys.PACK_SLUG to meta.packSlug,
        ProjectCheckCommandSnapshotKeys.FINDINGS to findingsPayload(meta.findings),
        ProjectCheckCommandSnapshotKeys.SUPPRESSION_MARKERS to meta.suppressionMarkers,
      )
    }

  private fun findingsPayload(findings: ProjectCheckCommandParserFindings): Map<String, Any?> =
    mapOf(
      ProjectCheckCommandSnapshotKeys.FORMAT to findings.format,
      ProjectCheckCommandSnapshotKeys.ARTIFACT_GLOBS to findings.artifactGlobs,
      ProjectCheckCommandSnapshotKeys.COMPILER_DIAGNOSTICS to
        mapOf(ProjectCheckCommandSnapshotKeys.FORMAT to findings.compilerDiagnosticsFormat),
      ProjectCheckCommandSnapshotKeys.EXECUTED_WORK to
        findings.executedWorkFormat?.let { mapOf(ProjectCheckCommandSnapshotKeys.FORMAT to it) },
    )

  private companion object {
    const val SNAPSHOT_SOURCE_LABEL = "monitor_repair_validation_snapshot"
  }
}
