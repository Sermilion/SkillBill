package skillbill.infrastructure.sqlite

import skillbill.contracts.JsonCodec
import skillbill.contracts.persistence.DesktopReadSchema
import skillbill.error.core.SkillBillRuntimeException
import skillbill.infrastructure.contracts.workflow.featuretask.AcceptanceCriterionRepairStateSchemaValidator
import skillbill.infrastructure.sqlite.core.migration.area.rewriteAcceptedAcceptanceCriterionRepairStates
import skillbill.infrastructure.sqlite.core.schema.DatabaseRuntime
import skillbill.workflow.engine.model.DurableWorkflowArtifactFamily
import skillbill.workflow.taskruntime.artifact.asRepairWireMap
import skillbill.workflow.taskruntime.artifact.toAcceptanceCriterionRepairState
import skillbill.workflow.taskruntime.model.audit.AcceptanceCriterionRepairAdmission
import skillbill.workflow.taskruntime.model.audit.AcceptanceCriterionRepairCriterion
import skillbill.workflow.taskruntime.model.audit.AcceptanceCriterionRepairMode
import skillbill.workflow.taskruntime.model.audit.AcceptanceCriterionRepairStage
import skillbill.workflow.taskruntime.model.audit.AcceptanceCriterionRepairState
import skillbill.workflow.taskruntime.model.audit.AcceptanceCriterionSourceDisposition
import skillbill.workflow.taskruntime.model.audit.AcceptanceCriterionValidationObligation
import skillbill.workflow.taskruntime.model.audit.originalAcceptanceCriterionAlias
import skillbill.workflow.taskruntime.model.core.FeatureTaskRuntimeWorkflowArtifactMap
import java.nio.file.Files
import java.nio.file.Path
import java.sql.Connection
import java.sql.DriverManager
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertTrue
import skillbill.contracts.workflow.featuretask.AcceptanceCriterionRepairPayloadKeys as Keys

class AcceptedRepairStateMigrationTest {
  @Test
  fun `legacy accepted repair rows become final acceptance and other rows stay`() {
    val dbPath = Files.createTempDirectory("runtime-kotlin-accepted-repair").resolve("metrics.db")
    val currentJson = artifactsJson(finishedRepairWire())
    val legacyJson = artifactsJson(legacyAcceptedWire())
    DatabaseRuntime.ensureDatabase(dbPath).use { connection ->
      connection.createStatement().use { statement ->
        statement.executeUpdate("DELETE FROM schema_migrations WHERE version = 60")
        statement.execute("PRAGMA user_version = 59")
      }
      insertWorkflow(connection, "current-repair", currentJson)
      insertWorkflow(connection, "legacy-repair", legacyJson)
    }

    DatabaseRuntime.ensureDatabase(dbPath).use { connection ->
      assertEquals(currentJson, storedArtifacts(connection, "current-repair"))
      val rewritten = storedArtifacts(connection, "legacy-repair")
      val artifacts = JsonCodec.parseValue(rewritten) as Map<*, *>
      assertEquals("kept", artifacts["kept"])
      val repair = repairMap(artifacts)
      AcceptanceCriterionRepairStateSchemaValidator.validate(repair, "legacy-repair")
      val state = FeatureTaskRuntimeWorkflowArtifactMap.from(repair).toAcceptanceCriterionRepairState("legacy-repair")
      assertEquals(AcceptanceCriterionRepairMode.FINAL_FULL, state.mode)
      assertEquals(AcceptanceCriterionRepairStage.FINAL_ACCEPTED, state.stage)
      assertFalse(state.finalAuditRequired)
      assertEquals(listOf("AC-001", "AC-002"), state.finalAudits.single().auditedRefs)
      assertEquals(emptyList(), state.finalAudits.single().regressedRefs)
      assertEquals(TREE_TWO, state.finalAudits.single().treeDigest)
      assertEquals("checkpoint-2", state.finalAudits.single().checkpointId)
      assertEquals(state.finalAuditDigest, state.finalAudits.single().digest)
      rewriteAcceptedAcceptanceCriterionRepairStates(connection)
      assertEquals(rewritten, storedArtifacts(connection, "legacy-repair"))
      connection.createStatement().use { statement ->
        statement.executeQuery("PRAGMA user_version").use { rows ->
          assertTrue(rows.next())
          assertEquals(DesktopReadSchema.VERSION, rows.getInt(1))
        }
      }
    }
  }

  @Test
  fun `accepted repair without closure evidence fails and leaves the row unchanged`() {
    val dbPath = Files.createTempDirectory("runtime-kotlin-accepted-repair-anchor").resolve("metrics.db")
    DatabaseRuntime.ensureDatabase(dbPath).use { connection ->
      connection.createStatement().use { statement ->
        statement.executeUpdate("DELETE FROM schema_migrations WHERE version = 60")
        statement.execute("PRAGMA user_version = 59")
      }
      insertWorkflow(connection, "unanchored-repair", artifactsJson(unanchoredAcceptedWire()))
    }

    val failure =
      assertFailsWith<SkillBillRuntimeException> {
        DatabaseRuntime.ensureDatabase(dbPath).close()
      }
    assertTrue(failure.message.orEmpty().contains("unanchored-repair"))
    assertTrue(failure.message.orEmpty().contains("no closure tree"))
    DriverManager.getConnection("jdbc:sqlite:$dbPath").use { connection ->
      val artifacts = JsonCodec.parseValue(storedArtifacts(connection, "unanchored-repair")) as Map<*, *>
      assertEquals("accepted", repairMap(artifacts)[Keys.MODE])
      connection.createStatement().use { statement ->
        statement.executeQuery("SELECT COUNT(*) FROM schema_migrations WHERE version = 60").use { rows ->
          assertTrue(rows.next())
          assertEquals(0, rows.getInt(1))
        }
      }
    }
  }

  @Test
  fun `accepted repair rows from a real store become readable final acceptance`() {
    val source = requireRealStore()
    val accepted = readAcceptedArtifacts(source)
    val dbPath = Files.createTempDirectory("runtime-kotlin-accepted-repair-store").resolve("metrics.db")
    DatabaseRuntime.ensureDatabase(dbPath).use { connection ->
      connection.createStatement().use { statement ->
        statement.executeUpdate("DELETE FROM schema_migrations WHERE version = 60")
        statement.execute("PRAGMA user_version = 59")
      }
      accepted.forEach { (workflowId, artifactsJson) -> insertWorkflow(connection, workflowId, artifactsJson) }
    }

    DatabaseRuntime.ensureDatabase(dbPath).use { connection ->
      assertEquals(emptyList(), readAcceptedArtifacts(dbPath))
      accepted.forEach { (workflowId, _) ->
        val artifacts = JsonCodec.parseValue(storedArtifacts(connection, workflowId)) as Map<*, *>
        val repair = repairMap(artifacts)
        AcceptanceCriterionRepairStateSchemaValidator.validate(repair, workflowId)
        val state = FeatureTaskRuntimeWorkflowArtifactMap.from(repair).toAcceptanceCriterionRepairState(workflowId)
        assertEquals(AcceptanceCriterionRepairMode.FINAL_FULL, state.mode)
        assertEquals(AcceptanceCriterionRepairStage.FINAL_ACCEPTED, state.stage)
        assertFalse(state.finalAuditRequired)
        assertNotNull(state.finalAuditDigest)
      }
    }
  }
}

private const val TREE_ONE = "bbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbb"
private const val TREE_TWO = "cccccccccccccccccccccccccccccccccccccccccccccccccccccccccccccccc"
private const val PROSE_DIGEST = "dddddddddddddddddddddddddddddddddddddddddddddddddddddddddddddddd"
private const val PROSE_TEXT = "closed by prose"

private fun finishedRepairWire(): MutableMap<String, Any?> {
  val admitted =
    assertNotNull(
      AcceptanceCriterionRepairState.admit(
        AcceptanceCriterionRepairAdmission(
          catalogId = "catalog",
          executionId = "execution",
          specDigest = "a".repeat(64),
          authoredCriteria = listOf("first", "second"),
          criteria =
            listOf(
              closedCriterion("AC-001", 1, "first", Closure("audit:AC-001", TREE_ONE, "checkpoint-1")),
              closedCriterion("AC-002", 2, "second", Closure(PROSE_TEXT, TREE_TWO, "checkpoint-2")),
            ),
          dependencies = emptyList(),
        ),
      ),
    )
  val wire = admitted.asRepairWireMap().toMutableMap()
  wire[Keys.REVISION] = 5
  wire[Keys.PROSE_AUDITS] =
    listOf(
      mapOf(
        Keys.ID to "prose-1",
        Keys.REVISION to 5,
        Keys.CRITERION_REF to "AC-002",
        Keys.SATISFIED to true,
        Keys.DIGEST to PROSE_DIGEST,
        Keys.PROSE to PROSE_TEXT,
        Keys.TREE_DIGEST to TREE_TWO,
        Keys.CHECKPOINT_ID to "checkpoint-2",
      ),
    )
  wire[Keys.SOURCE_TRANSITIONS] =
    listOf(
      mapOf(
        Keys.CANONICAL_REF to "AC-002",
        Keys.REVISION to 5,
        Keys.FROM_DISPOSITION to AcceptanceCriterionSourceDisposition.SOURCE_UNRESOLVED.wireValue,
        Keys.TO_DISPOSITION to AcceptanceCriterionSourceDisposition.SOURCE_CLOSED.wireValue,
        Keys.PRODUCING_ID to "prose-1",
        Keys.PRODUCING_DIGEST to PROSE_DIGEST,
        Keys.EVIDENCE_REF to PROSE_TEXT,
        Keys.TREE_DIGEST to TREE_TWO,
        Keys.CHECKPOINT_ID to "checkpoint-2",
      ),
    )
  return wire
}

private fun legacyAcceptedWire(): MutableMap<String, Any?> {
  val wire = finishedRepairWire()
  wire[Keys.MODE] = "accepted"
  wire[Keys.STAGE] = "accepted"
  wire.remove(Keys.FINAL_AUDIT_REQUIRED)
  wire.remove(Keys.FINAL_AUDIT_DIGEST)
  wire.remove(Keys.FINAL_AUDITS)
  return wire
}

private fun unanchoredAcceptedWire(): MutableMap<String, Any?> {
  val wire = legacyAcceptedWire()
  wire[Keys.CRITERIA] =
    (wire[Keys.CRITERIA] as List<*>).map { item ->
      val criterion = (item as Map<*, *>).entries.associate { (key, value) -> key as String to value }.toMutableMap()
      criterion[Keys.CLOSURE_TREE_DIGEST] = null
      criterion[Keys.CLOSURE_CHECKPOINT_ID] = null
      criterion
    }
  wire[Keys.SOURCE_TRANSITIONS] = emptyList<Any>()
  wire[Keys.PROSE_AUDITS] = emptyList<Any>()
  wire[Keys.OUTCOMES] = emptyList<Any>()
  return wire
}

private data class Closure(
  val evidence: String,
  val treeDigest: String,
  val checkpointId: String,
)

private fun closedCriterion(
  ref: String,
  ordinal: Int,
  text: String,
  closure: Closure,
) = AcceptanceCriterionRepairCriterion(
  canonicalRef = ref,
  originalAlias = originalAcceptanceCriterionAlias(text),
  originalText = text,
  ordinal = ordinal,
  sourceDisposition = AcceptanceCriterionSourceDisposition.SOURCE_CLOSED,
  validationObligation = AcceptanceCriterionValidationObligation.REQUIRED,
  validationOwnerAvailable = true,
  evidenceRef = "audit:$ref",
  closureEvidenceRef = closure.evidence,
  closureTreeDigest = closure.treeDigest,
  closureCheckpointId = closure.checkpointId,
)

private fun artifactsJson(repair: Map<String, Any?>): String =
  JsonCodec.mapToJsonString(
    mapOf(
      "kept" to "kept",
      DurableWorkflowArtifactFamily.FEATURE_TASK_RUNTIME_ACCEPTANCE_CRITERION_REPAIR.label() to repair,
    ),
  )

private fun insertWorkflow(
  connection: Connection,
  workflowId: String,
  artifactsJson: String,
) {
  connection.prepareStatement(
    """
    INSERT INTO feature_task_workflows (
      workflow_id, mode, contract_version, workflow_status, started_at, updated_at, state_entered_at, artifacts_json
    ) VALUES (?, 'prose', '0.1', 'blocked', '2026-10-17T00:00:00Z', '2026-10-17T00:00:00Z', '2026-10-17T00:00:00Z', ?)
    """.trimIndent(),
  ).use { statement ->
    statement.setString(1, workflowId)
    statement.setString(2, artifactsJson)
    statement.executeUpdate()
  }
}

private fun storedArtifacts(
  connection: Connection,
  workflowId: String,
): String =
  connection.prepareStatement(
    "SELECT artifacts_json FROM feature_task_workflows WHERE workflow_id = ?",
  ).use { statement ->
    statement.setString(1, workflowId)
    statement.executeQuery().use { rows ->
      check(rows.next())
      rows.getString(1)
    }
  }

private fun repairMap(artifacts: Map<*, *>): Map<String, Any?> {
  val repair =
    artifacts[DurableWorkflowArtifactFamily.FEATURE_TASK_RUNTIME_ACCEPTANCE_CRITERION_REPAIR.label()] as Map<*, *>
  return repair.entries.associate { (key, value) -> key as String to value }
}

private fun readAcceptedArtifacts(source: Path): List<Pair<String, String>> {
  val family = DurableWorkflowArtifactFamily.FEATURE_TASK_RUNTIME_ACCEPTANCE_CRITERION_REPAIR
  val modePath = "\$.${family.label()}.${Keys.MODE}"
  return DriverManager.getConnection("jdbc:sqlite:file:${source.toAbsolutePath()}?mode=ro").use { connection ->
    connection.prepareStatement(
      """
      SELECT workflow_id, artifacts_json
      FROM feature_task_workflows
      WHERE json_valid(artifacts_json) AND json_extract(artifacts_json, ?) = ?
      """.trimIndent(),
    ).use { statement ->
      statement.setString(1, modePath)
      statement.setString(2, "accepted")
      statement.executeQuery().use { rows ->
        buildList {
          while (rows.next()) {
            add(rows.getString(1) to rows.getString(2))
          }
        }
      }
    }
  }
}
