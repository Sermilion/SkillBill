package skillbill.infrastructure.sqlite

import skillbill.contracts.JsonCodec
import skillbill.contracts.persistence.DesktopReadSchema
import skillbill.error.core.SkillBillRuntimeException
import skillbill.infrastructure.contracts.workflow.featuretask.AcceptanceCriterionRepairAuxiliarySchemaValidator
import skillbill.infrastructure.contracts.workflow.featuretask.preservation.AcceptanceCriterionMigrationCodec
import skillbill.infrastructure.sqlite.core.migration.DatabaseMigration
import skillbill.infrastructure.sqlite.core.migration.area.criterion.AcceptanceCriterionArtifactsMigration
import skillbill.infrastructure.sqlite.workflow.retained.SQLiteRetainedWorkflowReader
import skillbill.ports.diagnostics.NoopRuntimeDiagnostics
import skillbill.workflow.engine.model.DurableWorkflowArtifactFamily
import skillbill.workflow.taskruntime.artifact.historical.asEngineProgressWireMap
import skillbill.workflow.taskruntime.artifact.historical.asEngineRepairWireMap
import skillbill.workflow.taskruntime.artifact.historical.toEngineImplementationProgress
import skillbill.workflow.taskruntime.artifact.historical.toEngineRepairState
import skillbill.workflow.taskruntime.artifact.historical.toMigrationProvenance
import skillbill.workflow.taskruntime.model.audit.history.AcceptanceCriterionArtifactTarget
import skillbill.workflow.taskruntime.model.audit.history.AcceptanceCriterionMigrationTarget
import skillbill.workflow.taskruntime.model.core.FeatureTaskRuntimeWorkflowArtifactMap
import java.nio.file.Files
import java.sql.Connection
import java.sql.DriverManager
import java.sql.SQLException
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertTrue
import skillbill.contracts.workflow.featuretask.AcceptanceCriterionMigrationKeys as MigrationKeys
import skillbill.contracts.workflow.featuretask.AcceptanceCriterionProgressPayloadKeys as ProgressKeys
import skillbill.contracts.workflow.featuretask.AcceptanceCriterionRepairPayloadKeys as Keys

class AcceptanceCriterionArtifactsMigrationTest {
  @Test
  fun `initial-loop target selection cannot promote the focused-loop artifacts`() {
    val source = CriterionMigrationFixtureSources.artifactsJson()
    val targets = listOf(AcceptanceCriterionArtifactTarget(progressFamily, "0.2"))
    val converted = envelope(AcceptanceCriterionMigrationCodec.prepare("workflow", source, targets))
    assertEquals("0.2", (progressFamily.value(converted) as Map<*, *>)[Keys.CONTRACT_VERSION])
    assertEquals("0.2", (repair.value(converted) as Map<*, *>)[Keys.CONTRACT_VERSION])
    assertEquals(
      "0.1",
      (
        DurableWorkflowArtifactFamily.FEATURE_TASK_RUNTIME_ACCEPTANCE_CRITERION_REPAIR_HANDOFF.value(
          converted,
        ) as Map<*, *>
      )[Keys.CONTRACT_VERSION],
    )
  }

  @Test
  fun `pending historical check resumes on its original reservation instead of reserving another mutation`() {
    val source = CriterionMigrationFixtureSources.artifactsJson(pendingCheck = true)
    val prepared =
      AcceptanceCriterionMigrationCodec.prepare(
        "workflow",
        source,
        AcceptanceCriterionMigrationTarget.COMPATIBLE,
      )
    val state =
      FeatureTaskRuntimeWorkflowArtifactMap.from(
        repair.value(envelope(prepared)),
      ).toEngineRepairState("pending-check")
    assertEquals("pending-reservation", state.reservation?.reservationId)
    assertEquals("pending-checkpoint", state.admittedCheckpointId)
    assertEquals("repair_completed_awaiting_targeted_verification", state.stage.wireValue)
    assertEquals(null, state.reserve("must-not-launch"))
    assertEquals(state.reservation, state.asEngineRepairWireMap().toEngineRepairState("restart").reservation)
  }

  @Test
  fun `oldest store readiness preserves accepted work and exact sources`() {
    val source = CriterionMigrationFixtureSources.artifactsJson()
    val path = Files.createTempDirectory("criterion-oldest-upgrade").resolve("runtime.db")
    DatabaseRuntime.ensureDatabase(path, NoopRuntimeDiagnostics).use { connection -> seed(connection, source) }
    DatabaseRuntime.migrateExistingDatabase(path, NoopRuntimeDiagnostics)
    DriverManager.getConnection("jdbc:sqlite:$path").use { connection ->
      val prepared = raw(connection)
      val artifacts = envelope(prepared)
      val provenance = provenance(artifacts)
      assertEquals(source, provenance.originalArtifactsJson)
      assertEquals(listOf("0.1", "0.1"), provenance.steps.map { it.sourceVersion })
      assertTrue(
        provenance.contexts.single { it.criterionRef == "AC-002" && it.family == repair.label() }.escalationOwed,
      )
      val repaired =
        FeatureTaskRuntimeWorkflowArtifactMap.from(
          repair.value(artifacts),
        ).toEngineRepairState("compatible")
      assertEquals(3, repaired.unsuccessfulTotals["AC-002"])
      assertEquals("source_closed", repaired.criteria.first().sourceDisposition.wireValue)
      assertEquals(3, repaired.outcomes.size)
      assertEquals("0.2", repaired.asEngineRepairWireMap()[Keys.CONTRACT_VERSION])
      val progress =
        FeatureTaskRuntimeWorkflowArtifactMap.from(
          progressFamily.value(artifacts),
        ).toEngineImplementationProgress("compatible")
      assertEquals(listOf("AC-001"), progress.closedIds)
      assertEquals(7, progress.launchCount)
      assertEquals("0.1", progress.asEngineProgressWireMap()[Keys.CONTRACT_VERSION])
      assertEquals(
        envelope(source)[CriterionMigrationFixtureSources.RETAINED_HISTORY],
        artifacts[CriterionMigrationFixtureSources.RETAINED_HISTORY],
      )
      DatabaseMigrations.apply(connection, NoopRuntimeDiagnostics)
      assertEquals(prepared, raw(connection))
      assertEquals(DesktopReadSchema.VERSION, scalar(connection, "PRAGMA user_version"))
    }
  }

  @Test
  fun `compatibility retains writable evidence and strict adoption remains owed`() {
    val source = CriterionMigrationFixtureSources.artifactsJson(oldest = false, retainedEvidence = true)
    val compatible =
      AcceptanceCriterionMigrationCodec.prepare(
        "workflow",
        source,
        AcceptanceCriterionMigrationTarget.COMPATIBLE,
      )
    val artifacts = envelope(compatible)
    val repairState =
      FeatureTaskRuntimeWorkflowArtifactMap.from(
        repair.value(artifacts),
      ).toEngineRepairState("retained")
    assertEquals(1, assertNotNull(repairState.retainedHistory).mutations.size)
    assertTrue(repairState.completedMutations.isEmpty())
    val rewritten = repairState.asEngineRepairWireMap().toEngineRepairState("rewritten")
    assertEquals(repairState.retainedHistory, rewritten.retainedHistory)
    val strict =
      AcceptanceCriterionMigrationCodec.prepare(
        "workflow",
        compatible,
        AcceptanceCriterionMigrationTarget.VERIFIED,
      )
    val converted = envelope(strict)
    assertEquals(compatible, provenance(converted).originalArtifactsJson)
    assertEquals("0.3", (repair.value(converted) as Map<*, *>)[Keys.CONTRACT_VERSION])
    assertEquals("0.2", (progressFamily.value(converted) as Map<*, *>)[Keys.CONTRACT_VERSION])
    assertEquals("0.2", (repair.value(converted) as Map<*, *>)[MigrationKeys.MIGRATION_SOURCE_VERSION])
    assertEquals(emptyList<Any?>(), (repair.value(converted) as Map<*, *>)[ProgressKeys.COMPLETED_MUTATIONS])
    assertFailsWith<SkillBillRuntimeException> {
      FeatureTaskRuntimeWorkflowArtifactMap.from(repair.value(converted)).toEngineRepairState("reconciliation-owed")
    }
    assertEquals(
      strict,
      AcceptanceCriterionMigrationCodec.prepare("workflow", strict, AcceptanceCriterionMigrationTarget.VERIFIED),
    )
  }

  @Test
  fun `targeted audit conversion retains facts without inventing a check`() {
    val artifacts = envelope(CriterionMigrationFixtureSources.artifactsJson(oldest = false)).toMutableMap()
    val auditFamily = DurableWorkflowArtifactFamily.FEATURE_TASK_RUNTIME_ACCEPTANCE_CRITERION_AUDIT_RESULT
    val audit = FeatureTaskRuntimeWorkflowArtifactMap.from(auditFamily.value(artifacts))
    val targeted =
      audit +
        mapOf(
          Keys.MODE to "current_criterion_targeted",
          Keys.CRITERIA to listOf((audit[Keys.CRITERIA] as List<*>).last()),
          Keys.CURRENT_CRITERION_REF to "AC-002",
          Keys.RESERVATION_ID to "historical-reservation",
          Keys.EXPECTED_REVISION to 3,
        )
    auditFamily.putInto(artifacts, targeted)
    val original = JsonCodec.valueToJsonString(artifacts)
    val strict =
      AcceptanceCriterionMigrationCodec.prepare(
        "workflow",
        original,
        AcceptanceCriterionMigrationTarget.VERIFIED,
      )
    val converted = FeatureTaskRuntimeWorkflowArtifactMap.from(auditFamily.value(envelope(strict)))
    assertEquals("0.3", converted[Keys.CONTRACT_VERSION])
    assertEquals("historical-reservation", converted[Keys.RESERVATION_ID])
    assertFalse(converted.containsKey(ProgressKeys.INDEPENDENT_PROGRESS))
    assertEquals(original, provenance(envelope(strict)).originalArtifactsJson)
    assertFailsWith<SkillBillRuntimeException> {
      AcceptanceCriterionRepairAuxiliarySchemaValidator.validateAuditResult(converted, "ordinary-checker")
    }
    assertEquals(
      strict,
      AcceptanceCriterionMigrationCodec.prepare("workflow", strict, AcceptanceCriterionMigrationTarget.VERIFIED),
    )
  }

  @Test
  fun `interruption after coupled publication rolls back sources and ledger then restart retries the same originals`() {
    val source = CriterionMigrationFixtureSources.artifactsJson()
    val path = Files.createTempDirectory("criterion-interrupted-upgrade").resolve("runtime.db")
    DatabaseRuntime.ensureDatabase(path, NoopRuntimeDiagnostics).use { connection ->
      seed(connection, source)
      val interrupted =
        DatabaseMigrations.migrations.dropLast(1) +
          DatabaseMigration(
            118,
            "prepare-preserved-criterion-artifact-compatibility",
            operation = { db: Connection ->
              AcceptanceCriterionArtifactsMigration.prepareCompatible(db, NoopRuntimeDiagnostics)
              throw SQLException("injected interruption after publication and before ledger")
            },
          )
      assertFailsWith<SQLException> { DatabaseMigrations.apply(connection, NoopRuntimeDiagnostics, interrupted) }
      assertEquals(source, raw(connection))
      assertEquals(0, scalar(connection, "SELECT count(*) FROM schema_migrations WHERE version = 118"))
      assertEquals(117, scalar(connection, "PRAGMA user_version"))
    }
    DatabaseRuntime.migrateExistingDatabase(path, NoopRuntimeDiagnostics)
    DriverManager.getConnection("jdbc:sqlite:$path").use { connection ->
      assertEquals(source, provenance(envelope(raw(connection))).originalArtifactsJson)
      assertEquals(1, scalar(connection, "SELECT count(*) FROM schema_migrations WHERE version = 118"))
    }
  }

  @Test
  fun `unsupported or corrupt coupled row keeps every source byte and exposes diagnostic reads after rollback`() {
    val source = CriterionMigrationFixtureSources.artifactsJson()
    val unsupported = JsonCodec.valueToJsonString(mapOf(repair.entry(mapOf(Keys.CONTRACT_VERSION to "99.0"))))
    listOf(unsupported, "  {corrupt-artifact-json}\n").forEach { refusedSource ->
      val path = Files.createTempDirectory("criterion-refused-upgrade").resolve("runtime.db")
      DatabaseRuntime.ensureDatabase(path, NoopRuntimeDiagnostics).use { connection ->
        seed(connection, source)
        connection.prepareStatement(
          "INSERT INTO feature_task_workflows (workflow_id, mode, contract_version, artifacts_json) " +
            "VALUES ('zz-unsupported', 'runtime', '0.4', ?)",
        ).use {
          it.setString(1, refusedSource)
          it.executeUpdate()
        }
        val failure =
          assertFailsWith<SkillBillRuntimeException> {
            DatabaseMigrations.apply(connection, NoopRuntimeDiagnostics)
          }
        assertTrue(failure.message.orEmpty().contains("zz-unsupported"))
        assertEquals(source, raw(connection))
        assertEquals(0, scalar(connection, "SELECT count(*) FROM schema_migrations WHERE version = 118"))
      }
      val database = sqliteDatabaseSessionFactory(path.parent, path.toString(), emptyMap())
      val retained = SQLiteRetainedWorkflowReader(database, NoopRuntimeDiagnostics).read(null)
      assertEquals(listOf("workflow", "zz-unsupported"), retained.map { it.workflowId })
      assertEquals(source, retained.first().source.artifactsJson)
      assertEquals(refusedSource, retained.last().source.artifactsJson)
      assertFalse(retained.first().source.location.isBlank())
    }
  }

  @Test
  fun `explicit strict storage conversion shares the registered transaction owner`() {
    val source = CriterionMigrationFixtureSources.artifactsJson()
    val path = Files.createTempDirectory("criterion-strict-storage").resolve("runtime.db")
    DatabaseRuntime.ensureDatabase(path, NoopRuntimeDiagnostics).use { connection ->
      seed(connection, source)
      DatabaseMigrations.apply(connection, NoopRuntimeDiagnostics)
      val compatible = raw(connection)
      val strictSet =
        DatabaseMigrations.migrations +
          DatabaseMigration(119, "adopt-test-strict-criterion-formats", operation = { db: Connection ->
            AcceptanceCriterionArtifactsMigration.apply(
              db,
              NoopRuntimeDiagnostics,
              AcceptanceCriterionMigrationTarget.VERIFIED,
            )
          })
      DatabaseMigrations.apply(connection, NoopRuntimeDiagnostics, strictSet)
      val strict = raw(connection)
      assertEquals(compatible, provenance(envelope(strict)).originalArtifactsJson)
      assertEquals("0.3", (repair.value(envelope(strict)) as Map<*, *>)[Keys.CONTRACT_VERSION])
      DatabaseMigrations.apply(connection, NoopRuntimeDiagnostics, strictSet)
      assertEquals(strict, raw(connection))
    }
  }

  private fun seed(
    connection: Connection,
    source: String,
  ) {
    connection.prepareStatement(
      "INSERT INTO feature_task_workflows (workflow_id, mode, contract_version, artifacts_json) " +
        "VALUES ('workflow', 'runtime', '0.4', ?)",
    ).use {
      it.setString(1, source)
      it.executeUpdate()
    }
    connection.createStatement().use {
      it.executeUpdate("DELETE FROM schema_migrations WHERE version = 118")
      it.execute("PRAGMA user_version = 117")
    }
  }

  private fun raw(connection: Connection): String =
    connection.createStatement().use {
      it.executeQuery("SELECT artifacts_json FROM feature_task_workflows WHERE workflow_id = 'workflow'").use {
          rows ->
        check(rows.next())
        rows.getString(1)
      }
    }

  private fun scalar(
    connection: Connection,
    sql: String,
  ): Int =
    connection.createStatement().use {
      it.executeQuery(sql).use { rows ->
        check(rows.next())
        rows.getInt(1)
      }
    }

  private fun envelope(json: String): Map<String, Any?> =
    checkNotNull(
      JsonCodec.anyToStringAnyMap(JsonCodec.parseValue(json)),
    )

  private fun provenance(artifacts: Map<String, Any?>) =
    FeatureTaskRuntimeWorkflowArtifactMap.from(
      DurableWorkflowArtifactFamily.FEATURE_TASK_RUNTIME_CRITERION_MIGRATION.value(artifacts),
    ).toMigrationProvenance("workflow")

  private val repair = DurableWorkflowArtifactFamily.FEATURE_TASK_RUNTIME_ACCEPTANCE_CRITERION_REPAIR
  private val progressFamily = DurableWorkflowArtifactFamily.FEATURE_TASK_RUNTIME_IMPLEMENTATION_PROGRESS
}
