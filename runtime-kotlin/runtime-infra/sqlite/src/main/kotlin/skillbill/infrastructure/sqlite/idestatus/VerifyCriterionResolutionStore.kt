package skillbill.infrastructure.sqlite.idestatus

import skillbill.infrastructure.sqlite.core.ops.bindAll
import skillbill.ports.idestatus.model.VerifyCriterionResolution
import skillbill.ports.idestatus.verify.VerifyCriterionResolutionRepository
import java.sql.Connection
import java.time.Instant

internal class VerifyCriterionResolutionStore(
  private val connection: Connection,
) : VerifyCriterionResolutionRepository {
  override fun record(
    workflowId: String,
    resolution: VerifyCriterionResolution,
  ) {
    connection.prepareStatement(
      """
      INSERT INTO verify_criterion_resolutions (workflow_id, criterion_number, evidence, recorded_at)
      VALUES (?, ?, ?, ?)
      ON CONFLICT(workflow_id, criterion_number) DO UPDATE SET
        evidence = excluded.evidence,
        recorded_at = excluded.recorded_at
      """.trimIndent(),
    ).use { statement ->
      statement.bindAll(
        workflowId,
        resolution.criterionNumber,
        resolution.evidence,
        resolution.recordedAt.toString(),
      )
      statement.executeUpdate()
    }
  }

  override fun resolutions(workflowId: String): List<VerifyCriterionResolution> =
    connection.prepareStatement(
      """
      SELECT criterion_number, evidence, recorded_at FROM verify_criterion_resolutions
      WHERE workflow_id = ? ORDER BY criterion_number
      """.trimIndent(),
    ).use { statement ->
      statement.bindAll(workflowId)
      statement.executeQuery().use { rows ->
        buildList {
          while (rows.next()) {
            add(
              VerifyCriterionResolution(
                criterionNumber = rows.getInt("criterion_number"),
                evidence = rows.getString("evidence"),
                recordedAt = Instant.parse(rows.getString("recorded_at")),
              ),
            )
          }
        }
      }
    }
}
