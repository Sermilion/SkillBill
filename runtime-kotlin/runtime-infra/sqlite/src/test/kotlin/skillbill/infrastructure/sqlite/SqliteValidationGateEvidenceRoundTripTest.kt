package skillbill.infrastructure.sqlite

import skillbill.contracts.JsonCodec
import skillbill.contracts.SharedPayloadKeys
import skillbill.contracts.workflow.identity.evidence.ValidationEvidencePayloadKeys
import skillbill.infrastructure.sqlite.core.schema.DatabaseRuntime
import skillbill.ports.featuretask.model.FeatureTaskPhaseSettlement
import skillbill.ports.featuretask.model.FeatureTaskPhaseSettlementKind
import skillbill.workflow.taskruntime.artifact.asWorkflowArtifactEntry
import skillbill.workflow.taskruntime.artifact.decodeValidationGateExecutionEvidenceFromArtifact
import skillbill.workflow.taskruntime.model.validation.FeatureTaskRuntimeValidationGateExecutionEvidence
import skillbill.workflow.taskruntime.model.validation.FeatureTaskRuntimeValidationGateRunRecord
import skillbill.workflow.taskruntime.model.validation.ValidationGateCacheMode
import skillbill.workflow.taskruntime.model.validation.ValidationGateRunOutcome
import java.nio.file.Files
import java.time.Instant
import kotlin.test.Test
import kotlin.test.assertEquals

class SqliteValidationGateEvidenceRoundTripTest {
  @Test
  fun `validation gate execution evidence survives sqlite round trip`() {
    val repo = repository()
    persistEvidence(repo, gateEvidence())
    val decoded = readEvidence(repo)

    assertRoundTrip(decoded)
  }

  @Test
  fun cachedSuccessfulCommandRetainsExplicitEmptyWorkAndChecks() {
    val repo = repository()
    val evidence =
      FeatureTaskRuntimeValidationGateExecutionEvidence.fromGateMeasurements(
        listOf(
          FeatureTaskRuntimeValidationGateRunRecord(
            durationMs = 1,
            outcome = ValidationGateRunOutcome.PASSED,
            cacheMode = ValidationGateCacheMode.CACHE_ELIGIBLE,
            executedWorkUnits = 0,
            executedChecks = emptyList(),
            command = "./gradlew check",
            exitCode = 0,
            repositoryCheckpoint = "cached-checkpoint",
          ),
        ),
      )

    persistEvidence(repo, evidence)

    assertEquals(evidence, readEvidence(repo))
  }

  private fun repository(): SqliteFeatureTaskPhaseSettlementRepository {
    val tempDir = Files.createTempDirectory("validation-gate-evidence")
    val dbPath = tempDir.resolve("metrics.db")
    DatabaseRuntime.ensureDatabase(dbPath).close()
    return SqliteFeatureTaskPhaseSettlementRepository(
      sqliteDatabaseSessionFactory(userHome = tempDir, dbPathOverride = dbPath.toString(), environment = emptyMap()),
    )
  }

  private fun gateEvidence(): FeatureTaskRuntimeValidationGateExecutionEvidence =
    FeatureTaskRuntimeValidationGateExecutionEvidence.fromGateMeasurements(
      listOf(
        FeatureTaskRuntimeValidationGateRunRecord(
          durationMs = 4,
          outcome = ValidationGateRunOutcome.FAILED,
          cacheMode = ValidationGateCacheMode.CACHE_ELIGIBLE,
          executedWorkUnits = 2,
          executedChecks = listOf("runtime-engine|compileKotlin"),
          command = "./gradlew check",
          exitCode = 1,
          repositoryCheckpoint = "checkpoint-before",
        ),
        FeatureTaskRuntimeValidationGateRunRecord(
          durationMs = 6,
          outcome = ValidationGateRunOutcome.PASSED,
          cacheMode = ValidationGateCacheMode.FORCED_FULL,
          executedWorkUnits = 2,
          executedChecks = listOf("runtime-engine|compileKotlin", "runtime-engine|test"),
          command = "./gradlew check --rerun-tasks",
          exitCode = 0,
          repositoryCheckpoint = "checkpoint-after",
        ),
      ),
    )

  private fun persistEvidence(
    repo: SqliteFeatureTaskPhaseSettlementRepository,
    gateEvidence: FeatureTaskRuntimeValidationGateExecutionEvidence,
  ) {
    val envelopeJson =
      JsonCodec.mapToJsonString(
        mapOf(
          SharedPayloadKeys.STATUS to "completed",
          SharedPayloadKeys.PRODUCED_OUTPUTS to
            mapOf(
              ValidationEvidencePayloadKeys.VALIDATION_RESULT to
                gateEvidence.asWorkflowArtifactEntry(requireNotNull(gateEvidence.gateRuns.last().repositoryCheckpoint)),
            ),
        ),
      )
    repo.upsert(
      FeatureTaskPhaseSettlement(
        workflowId = "wftr-gate-evidence",
        phaseId = "validate",
        attempt = 1,
        kind = FeatureTaskPhaseSettlementKind.Complete,
        envelopeJson = envelopeJson,
        recordedAt = Instant.now().toString(),
      ),
    )
  }

  private fun readEvidence(
    repo: SqliteFeatureTaskPhaseSettlementRepository,
  ): FeatureTaskRuntimeValidationGateExecutionEvidence {
    val stored = requireNotNull(repo.find("wftr-gate-evidence", "validate", 1))
    val validationResult =
      JsonCodec.anyToStringAnyMap(
        JsonCodec.anyToStringAnyMap(
          JsonCodec.parseObjectOrNull(stored.envelopeJson)
            ?.let(JsonCodec::jsonElementToValue)
            ?.let(JsonCodec::anyToStringAnyMap)
            ?.get(SharedPayloadKeys.PRODUCED_OUTPUTS),
        )?.get(ValidationEvidencePayloadKeys.VALIDATION_RESULT),
      ) ?: error("validation_result missing")
    return requireNotNull(decodeValidationGateExecutionEvidenceFromArtifact(validationResult, "validate"))
  }

  private fun assertRoundTrip(decoded: FeatureTaskRuntimeValidationGateExecutionEvidence) {
    assertEquals(listOf("runtime-engine|compileKotlin", "runtime-engine|test"), decoded.checks)
    assertEquals(2, decoded.gateRunCount)
    assertEquals(listOf("./gradlew check", "./gradlew check --rerun-tasks"), decoded.gateRuns.map { it.command })
    assertEquals(listOf(1, 0), decoded.gateRuns.map { it.exitCode })
    assertEquals(listOf("checkpoint-before", "checkpoint-after"), decoded.gateRuns.map { it.repositoryCheckpoint })
    assertEquals(
      listOf(ValidationGateCacheMode.CACHE_ELIGIBLE, ValidationGateCacheMode.FORCED_FULL),
      decoded.gateRuns.map { it.cacheMode },
    )
    assertEquals(
      listOf(ValidationGateRunOutcome.FAILED, ValidationGateRunOutcome.PASSED),
      decoded.gateRuns.map { it.outcome },
    )
    assertEquals(listOf(2, 2), decoded.gateRuns.map { it.executedWorkUnits })
    assertEquals(
      listOf(
        listOf("runtime-engine|compileKotlin"),
        listOf("runtime-engine|compileKotlin", "runtime-engine|test"),
      ),
      decoded.gateRuns.map { it.executedChecks },
    )
  }
}
