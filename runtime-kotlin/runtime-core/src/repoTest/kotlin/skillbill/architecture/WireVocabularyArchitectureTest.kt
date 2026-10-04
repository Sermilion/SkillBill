package skillbill.architecture

import skillbill.contracts.SharedPayloadKeys
import skillbill.contracts.learning.LearningPayloadKeys
import skillbill.contracts.review.ReviewAccountingPayloadKeys
import skillbill.contracts.review.ReviewFindingPayloadKeys
import skillbill.contracts.review.ReviewFinishedTelemetryPayloadKeys
import skillbill.contracts.review.ReviewVerificationSignalKeys
import skillbill.contracts.system.UpdateCheckPayloadKeys
import skillbill.contracts.telemetry.LifecycleTelemetryPayloadKeys
import skillbill.contracts.telemetry.TelemetryProxyPayloadKeys
import skillbill.contracts.workflow.payload.WorkflowWirePayloadKeys
import skillbill.contracts.workflow.session.WorkflowContinueSessionSummaryPayloadKeys
import skillbill.infrastructure.contracts.locator.DecompositionManifestSchemaPaths
import skillbill.infrastructure.sqlite.telemetry.SqliteReviewTelemetryPayloadKeys
import skillbill.infrastructure.sqlite.telemetry.goal.GoalTelemetryPayloadKeys
import skillbill.infrastructure.sqlite.telemetry.lifecycle.SqliteLifecycleTelemetryMaterializationPayloadKeys
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class WireVocabularyArchitectureTest {
  @Test
  fun `runtime wire vocabulary has no duplicate declarations or local restatements`() {
    val report = WireVocabularyArchitectureSupport.scanRuntimeMainSources()
    assertEquals(
      emptyList(),
      report.violations,
      "Wire vocabulary baseline delta ${report.baselineViolationCount} -> " +
        "${report.remainingViolationCount}:\n${report.violations.joinToString("\n")}",
    )
    assertEquals(0, report.remainingViolationCount)
    assertEquals(report.baselineViolationCount, report.remainingViolationCount)
  }

  @Test
  fun `sqlite adapter key objects restate no shared payload key value`() {
    val sharedValues =
      payloadKeyValues(
        SharedPayloadKeys::class.java,
        LifecycleTelemetryPayloadKeys::class.java,
        GoalTelemetryPayloadKeys::class.java,
        ReviewFindingPayloadKeys::class.java,
        ReviewFinishedTelemetryPayloadKeys::class.java,
        ReviewVerificationSignalKeys::class.java,
      )
    val adapterOwners =
      listOf(
        SqliteLifecycleTelemetryMaterializationPayloadKeys::class.java,
        SqliteReviewTelemetryPayloadKeys::class.java,
      )

    val restatements =
      adapterOwners.flatMap { owner ->
        (payloadKeyValues(owner) intersect sharedValues).sorted().map { value -> "${owner.simpleName}:$value" }
      }

    assertEquals(
      emptyList(),
      restatements,
      "SQLite adapter key objects must reference the shared owner instead of restating its wire value",
    )
  }

  @Test
  fun `mcp tool payload keys restate no shared payload key value`() {
    val owner = "skillbill.mcp.shared.McpToolPayloadKeys"
    val mcpValues =
      WireVocabularyArchitectureSupport.scanRuntimeMainSources().declarations
        .filter { it.owner == owner }
        .map { it.value }
    assertTrue(mcpValues.isNotEmpty(), "Wire vocabulary scan found no declarations for $owner")
    val sharedValues =
      payloadKeyValues(
        SharedPayloadKeys::class.java,
        LifecycleTelemetryPayloadKeys::class.java,
        ReviewFinishedTelemetryPayloadKeys::class.java,
        ReviewVerificationSignalKeys::class.java,
        ReviewAccountingPayloadKeys::class.java,
        UpdateCheckPayloadKeys::class.java,
        TelemetryProxyPayloadKeys::class.java,
        WorkflowWirePayloadKeys::class.java,
        LearningPayloadKeys::class.java,
        WorkflowContinueSessionSummaryPayloadKeys::class.java,
      )

    assertEquals(
      emptyList(),
      (mcpValues intersect sharedValues).sorted(),
      "The MCP key object must reference the shared owner instead of restating its wire value",
    )
  }

  @Test
  fun `sqlite seams keep governing the values their adapter key objects handed to shared owners`() {
    val reviewKeys =
      WireVocabularyGovernedSeamInventory.closedSchemaPropertyKeys(
        WireVocabularyGovernedSeamInventory.SQLITE_REVIEW_TELEMETRY_AUTHORITY,
      )
    val materializationKeys =
      WireVocabularyGovernedSeamInventory.closedSchemaPropertyKeys(
        WireVocabularyGovernedSeamInventory.SQLITE_TELEMETRY_MATERIALIZATION_AUTHORITY,
      )

    assertTrue(
      reviewKeys.containsAll(
        setOf("event_name", "gaps_found", "finished_at", "parent_workflow_id", "blocked_reason", "workflow_id"),
      ),
      "sqlite-review-telemetry lost a governed value when SqliteReviewTelemetryPayloadKeys dropped it",
    )
    assertTrue(
      "session_id" !in reviewKeys && "audit_gap_measurement_grain" !in reviewKeys,
      "sqlite-review-telemetry must not absorb lifecycle-only keys it never governed",
    )
    assertTrue(
      materializationKeys.containsAll(setOf("event_name", "blocked_reason", "session_id", "workflow_id")),
      "sqlite-telemetry-materialization lost a governed value when its adapter key object dropped it",
    )
  }

  @Test
  fun `scanner accepts owners references typed subsets prose and open extension values`() {
    val files =
      listOf(
        syntheticSourceFile(
          "fixture/Owner.kt",
          """

          package fixture

          enum class Owner(val wireValue: String) {
            READY("ready"),

            companion object {
              fun fromWire(value: String): Owner? = when (value) {
                "old_ready" -> READY
                else -> entries.firstOrNull { it.wireValue == value }
              }
            }
          }

          object ContractKeys {
            const val STATUS = "status"
          }
          """.trimIndent(),
        ),
        syntheticSourceFile(
          "fixture/Consumer.kt",
          """

          package fixture
          import fixture.Owner as AliasOwner


          fun consume(value: AliasOwner) = value.wireValue
          fun subset(): Set<AliasOwner> = setOf(AliasOwner.READY)
          fun keyed(): Map<String, String> = mapOf(ContractKeys.STATUS to "value")
          fun prose(): String = "ready is a word in this sentence"
          fun extension(value: String): String = value
          """.trimIndent(),
        ),
      )
    val report = WireVocabularyArchitectureSupport.scanSourceFiles(files)
    assertEquals(emptyList(), report.violations)
    assertTrue(report.declarations.any { it.category == "token" && it.value == "ready" })
    assertTrue(report.declarations.any { it.category == "alias" && it.value == "old_ready" })
    assertTrue(report.declarations.any { it.category == "key" && it.value == "status" })
  }

  @Test
  fun `scanner keeps identical spellings separate when the decoding context differs`() {
    val files =
      listOf(
        syntheticSourceFile(
          "fixture/Owner.kt",
          """

          enum class Owner(val wireValue: String) {
            READY("ready"),
          }
          """.trimIndent(),
        ),
        syntheticSourceFile(
          "unrelated/Labels.kt",
          """

          val labels = setOf("ready")
          """.trimIndent(),
        ),
        syntheticSourceFile(
          "consumer/Decoder.kt",
          """


          fun decode(value: Owner) = value.wireValue
          val labels = setOf("ready")
          """.trimIndent(),
        ),
      )
    val report = WireVocabularyArchitectureSupport.scanSourceFiles(files)
    assertTrue(report.violations.none { it.contains("unrelated/Labels.kt") })
    assertTrue(report.violations.any { it.contains("consumer/Decoder.kt") && it.contains("restates 'ready'") })
  }

  @Test
  fun `goal continuation artifact seam rejects undeclared literal key access`() {
    val files =
      listOf(
        syntheticSourceFile(
          "taskruntime/model/persistence/FeatureTaskRuntimeGoalContinuationArtifactKeys.kt",
          """

          object FeatureTaskRuntimeGoalContinuationArtifactPayloadKeys {
            const val SUPPRESS_PR: String = "suppress_pr"
          }
          """.trimIndent(),
        ),
        syntheticSourceFile(
          "taskruntime/model/persistence/FeatureTaskRuntimeGoalContinuationArtifact.kt",
          """

          fun read(raw: Map<String, Any?>) = raw["suppress_pr"]
          """.trimIndent(),
        ),
      )
    val report =
      WireVocabularyArchitectureSupport.scanSourceFiles(
        files,
        includePayloadKeyAccesses = true,
        enforceGovernedSeams = true,
        schemaPropertyKeysByPath =
          mapOf(
            WireVocabularyGovernedSeamInventory.GOAL_CONTINUATION_ARTIFACT_SCHEMA_AUTHORITY to setOf("suppress_pr"),
          ),
      )
    assertTrue(report.violations.any { it.contains("accesses key 'suppress_pr'") })
  }

  @Test
  fun `goal runner controls seam rejects undeclared literal key access`() {
    val files =
      listOf(
        syntheticSourceFile(
          "contracts/GoalRunnerControlPayloadKeys.kt",
          """

          object GoalRunnerControlPayloadKeys {
            const val ACCEPTED_AT: String = "accepted_at"
          }
          """.trimIndent(),
        ),
        syntheticSourceFile(
          "infrastructure/sqlite/workflow/goalrunner/runner/GoalRunnerControlStoreDecodePolicies.kt",
          """

          fun read(raw: Map<String, Any?>) = raw["accepted_at"]
          """.trimIndent(),
        ),
      )
    val report =
      WireVocabularyArchitectureSupport.scanSourceFiles(
        files,
        includePayloadKeyAccesses = true,
        enforceGovernedSeams = true,
        schemaPropertyKeysByPath =
          mapOf(
            WireVocabularyGovernedSeamInventory.GOAL_RUNNER_CONTROLS_AUTHORITY to setOf("accepted_at"),
          ),
      )
    assertTrue(report.violations.any { it.contains("accesses key 'accepted_at'") })
  }

  @Test
  fun `sqlite telemetry seam rejects inline session id access`() {
    val files =
      listOf(
        syntheticSourceFile(
          "infrastructure/sqlite/telemetry/TelemetryKeys.kt",
          """

          object TelemetryPayloadKeys {
            const val SESSION_ID: String = "session_id"
          }
          """.trimIndent(),
        ),
        syntheticSourceFile(
          "infrastructure/sqlite/telemetry/TelemetryReader.kt",
          """

          fun read(payload: Map<String, Any?>) = payload["session_id"]
          """.trimIndent(),
        ),
      )
    val report =
      WireVocabularyArchitectureSupport.scanSourceFiles(
        files = files,
        includePayloadKeyAccesses = true,
        enforceGovernedSeams = true,
        schemaPropertyKeysByPath =
          mapOf(
            WireVocabularyGovernedSeamInventory.SQLITE_TELEMETRY_MATERIALIZATION_AUTHORITY to setOf("session_id"),
          ),
      )
    assertTrue(report.violations.any { it.contains("accesses key 'session_id'") })
  }

  @Test
  fun `telemetry proxy seam rejects inline supports_stats access`() {
    val files =
      listOf(
        syntheticSourceFile(
          "infrastructure/http/TelemetryProxyPayloadKeys.kt",
          """

          object TelemetryProxyPayloadKeys {
            const val SUPPORTS_STATS: String = "supports_stats"
          }
          """.trimIndent(),
        ),
        syntheticSourceFile(
          "infrastructure/http/HttpTelemetryResultMappers.kt",
          """

          fun read(payload: Map<String, Any?>) = payload["supports_stats"]
          """.trimIndent(),
        ),
      )
    val report =
      WireVocabularyArchitectureSupport.scanSourceFiles(
        files = files,
        includePayloadKeyAccesses = true,
        enforceGovernedSeams = true,
        schemaPropertyKeysByPath =
          mapOf(
            WireVocabularyGovernedSeamInventory.TELEMETRY_PROXY_AUTHORITY to setOf("supports_stats"),
          ),
      )
    assertTrue(report.violations.any { it.contains("accesses key 'supports_stats'") })
  }

  @Test
  fun `new schema field without kotlin owner fails through the production scanner`() {
    val report =
      WireVocabularyArchitectureSupport.scanSourceFiles(
        files =
          listOf(
            syntheticSourceFile(
              "workflow/decomposition/ManifestKeys.kt",
              """

              object ManifestKeys {
                const val STATUS: String = "status"
                const val CONTRACT_VERSION: String = "contract_version"
                const val PHASE_ID: String = "phase_id"
                const val SUMMARY: String = "summary"
                const val PRODUCED_OUTPUTS: String = "produced_outputs"
                const val FAILURE_DISPOSITION: String = "failure_disposition"
                const val DERIVED_NOTES: String = "derived_notes"
                const val VERDICT: String = "verdict"
              }
              """.trimIndent(),
            ),
          ),
        enforceGovernedSeams = true,
        schemaPropertyKeysByPath =
          mapOf(
            DecompositionManifestSchemaPaths.REPO_RELATIVE_PATH to setOf("status", "schema_introduced_key"),
          ),
      )
    assertTrue(
      report.violations.any { it.contains("schema_introduced_key") },
      "Expected independent schema authority to flag a new field before its owner exists",
    )
  }

  @Test
  fun `governed seam rejects undeclared literal even when keys object declares the wire string`() {
    val files =
      listOf(
        syntheticSourceFile(
          "fixture/ManifestKeys.kt",
          """

          object ManifestKeys {
            const val STATUS: String = "status"
            const val CONTRACT_VERSION: String = "contract_version"
            const val PHASE_ID: String = "phase_id"
            const val SUMMARY: String = "summary"
            const val PRODUCED_OUTPUTS: String = "produced_outputs"
            const val FAILURE_DISPOSITION: String = "failure_disposition"
            const val DERIVED_NOTES: String = "derived_notes"
            const val VERDICT: String = "verdict"
          }
          """.trimIndent(),
        ),
        syntheticSourceFile(
          "workflow/decomposition/ForeignConsumer.kt",
          """

          fun read(payload: Map<String, Any?>) = payload["status"]
          """.trimIndent(),
        ),
      )
    val report =
      WireVocabularyArchitectureSupport.scanSourceFiles(
        files,
        includePayloadKeyAccesses = true,
        enforceGovernedSeams = true,
        schemaPropertyKeysByPath =
          mapOf(
            DecompositionManifestSchemaPaths.REPO_RELATIVE_PATH to setOf("status"),
          ),
      )
    assertTrue(report.violations.any { it.contains("accesses key 'status'") })
  }

  @Test
  fun `governed seam accepts keys constant reference and open extension map values`() {
    val files =
      listOf(
        syntheticSourceFile(
          "fixture/ManifestKeys.kt",
          """

          object ManifestKeys {
            const val STATUS: String = "status"
            const val CONTRACT_VERSION: String = "contract_version"
            const val PHASE_ID: String = "phase_id"
            const val SUMMARY: String = "summary"
            const val PRODUCED_OUTPUTS: String = "produced_outputs"
            const val FAILURE_DISPOSITION: String = "failure_disposition"
            const val DERIVED_NOTES: String = "derived_notes"
            const val VERDICT: String = "verdict"
            const val ENTRIES: String = "entries"
            const val STAGING_DIRECTORY: String = "staging_directory"
            const val TARGET: String = "target"
            const val STAGED: String = "staged"
            const val SHA256: String = "sha256"
          }
          """.trimIndent(),
        ),
        syntheticSourceFile(
          "workflow/decomposition/Codec.kt",
          """

          fun keyed(payload: Map<String, Any?>) = payload[ManifestKeys.STATUS]
          fun extensionValues(produced: Map<String, Any?>) = produced["custom_phase_output"]
          """.trimIndent(),
        ),
      )
    val report =
      WireVocabularyArchitectureSupport.scanSourceFiles(
        files,
        includePayloadKeyAccesses = true,
        enforceGovernedSeams = true,
        schemaPropertyKeysByPath =
          mapOf(
            DecompositionManifestSchemaPaths.REPO_RELATIVE_PATH to setOf("status"),
          ),
      )
    assertEquals(emptyList(), report.violations)
  }

  @Test
  fun `scanner rejects duplicates aliases local collections and foreign key accesses`() {
    val files =
      listOf(
        syntheticSourceFile(
          "fixture/Owner.kt",
          """

          enum class Owner(val wireValue: String) {
            FIRST("ready"),
            SECOND("ready"),

            companion object {
              fun fromWire(value: String): Owner? = when (value) {
                "old_ready" -> FIRST
                "old_ready" -> SECOND
                else -> null
              }
            }
          }

          object ContractKeys {
            const val STATUS = "status"
          }
          """.trimIndent(),
        ),
        syntheticSourceFile(
          "fixture/Consumer.kt",
          """

          @SerialName("status")
          val annotated = "value"
          val statuses = setOf("ready")
          fun consume(payload: Map<String, Any?>) = mapOf("ready" to payload["status"])
          """.trimIndent(),
        ),
      )
    val report = WireVocabularyArchitectureSupport.scanSourceFiles(files, includePayloadKeyAccesses = true)
    assertTrue(report.violations.any { it.contains("duplicate token 'ready'") })
    assertTrue(report.violations.any { it.contains("duplicate alias 'old_ready'") })
    assertTrue(report.violations.any { it.contains("restates 'ready'") })
    assertTrue(report.violations.any { it.contains("accesses key 'status'") })
    assertEquals(report.violations.sorted(), report.violations)
  }
}
