package skillbill.architecture

import com.fasterxml.jackson.databind.JsonNode
import com.fasterxml.jackson.dataformat.yaml.YAMLMapper
import skillbill.application.decomposition.repoRelativePath
import skillbill.contracts.SharedPayloadKeys
import skillbill.contracts.decomposition.DecompositionManifestPayloadKeys
import skillbill.contracts.review.ReviewFindingPayloadKeys
import skillbill.contracts.review.ReviewFinishedTelemetryPayloadKeys
import skillbill.contracts.review.ReviewVerificationSignalKeys
import skillbill.contracts.telemetry.LifecycleTelemetryPayloadKeys
import skillbill.contracts.telemetry.TelemetryProxyPayloadKeys
import skillbill.contracts.workflow.identity.task.FeatureTaskRuntimeGoalContinuationArtifactPayloadKeys
import skillbill.infrastructure.contracts.locator.DecompositionManifestBundleJournalSchemaPaths
import skillbill.infrastructure.contracts.locator.DecompositionManifestSchemaPaths
import skillbill.infrastructure.contracts.locator.FeatureTaskRuntimeExecutionPlanSchemaPaths
import skillbill.infrastructure.contracts.locator.FeatureTaskRuntimeReadinessEvidenceSchemaPaths
import skillbill.infrastructure.sqlite.telemetry.SqliteReviewTelemetryPayloadKeys
import skillbill.infrastructure.sqlite.telemetry.goal.GoalTelemetryPayloadKeys
import skillbill.infrastructure.sqlite.telemetry.lifecycle.SqliteLifecycleTelemetryMaterializationPayloadKeys
import skillbill.testing.repoRootFromTest
import java.nio.file.Files
import kotlin.test.assertTrue

internal data class GovernedPayloadSeam(
  val seamId: String,
  val schemaRepoRelativePath: String,
  val governedRelativePathMarkers: List<String>,
)

internal object WireVocabularyGovernedSeamInventory {
  val seams: List<GovernedPayloadSeam> =
    listOf(
      GovernedPayloadSeam(
        seamId = "feature-task-runtime-execution-plan",
        schemaRepoRelativePath = FeatureTaskRuntimeExecutionPlanSchemaPaths.REPO_RELATIVE_PATH,
        governedRelativePathMarkers = listOf("FeatureTaskRuntimeExecutionPlan", "FeatureTaskExecutionPlan"),
      ),
      GovernedPayloadSeam(
        seamId = "decomposition-manifest",
        schemaRepoRelativePath = DecompositionManifestSchemaPaths.REPO_RELATIVE_PATH,
        governedRelativePathMarkers =
          listOf(
            "workflow/decomposition/",
            "DecompositionManifest",
            "application/decomposition/",
            "application/workflow/Decomposition",
            "application/featurespec/",
            "infrastructure/contracts/workflow/DecompositionManifest",
            "engine/goalrunner/manifest/WorkflowGoalRunnerManifest",
            "engine/goalrunner/persist/GoalContinuationArtifactCodec.kt",
            "engine/goalrunner/planning/GoalPlanningShared",
            "engine/goalrunner/planning/context/GoalPlanningSharedContextPacketPayloadKeys",
            "engine/goalrunner/model/ImplementationReturnContractPayloadKeys",
            "cli/workflow/WorkflowContinueCliBranchMapsDecomposition",
          ),
      ),
      GovernedPayloadSeam(
        seamId = "decomposition-manifest-bundle-journal",
        schemaRepoRelativePath = DecompositionManifestBundleJournalSchemaPaths.REPO_RELATIVE_PATH,
        governedRelativePathMarkers =
          listOf(
            "DecompositionManifestBundleJournal",
          ),
      ),
      GovernedPayloadSeam(
        seamId = "feature-task-runtime-readiness-evidence",
        schemaRepoRelativePath = FeatureTaskRuntimeReadinessEvidenceSchemaPaths.REPO_RELATIVE_PATH,
        governedRelativePathMarkers =
          listOf(
            "workflow/taskruntime/model/validation/FeatureTaskRuntimeReadinessEvidence",
            "workflow/taskruntime/artifact/FeatureTaskRuntimeWorkflowArtifactWire",
            "workflow/taskruntime/model/validation/ReadinessEvidencePayloadKeys",
            "engine/featuretask/validation/FeatureTaskRuntimeReadinessGateCoordinator",
            "engine/featuretask/validation/ReadinessCheckSelection",
            "engine/featuretask/phase/record/FeatureTaskRuntimeGateProgressRecorder",
          ),
      ),
      GovernedPayloadSeam(
        seamId = "feature-task-runtime-goal-continuation-artifact",
        schemaRepoRelativePath = GOAL_CONTINUATION_ARTIFACT_SCHEMA_AUTHORITY,
        governedRelativePathMarkers =
          listOf(
            "taskruntime/model/persistence/FeatureTaskRuntimeGoalContinuationArtifact",
            "engine/goalrunner/persist/GoalContinuationArtifactCodec.kt",
          ),
      ),
      GovernedPayloadSeam(
        seamId = "goal-runner-controls",
        schemaRepoRelativePath = GOAL_RUNNER_CONTROLS_AUTHORITY,
        governedRelativePathMarkers =
          listOf(
            "application/workflow/service/LegacyGoalRunnerControlMigration",
            "infrastructure/sqlite/workflow/goalrunner/runner/LegacyGoalRunnerControlLedgerMigration",
            "infrastructure/sqlite/workflow/goalrunner/runner/GoalRunnerControlStoreDecodePolicies",
            "infrastructure/sqlite/workflow/goalrunner/runner/GoalRunnerControlStoreEncode",
          ),
      ),
      GovernedPayloadSeam(
        seamId = "sqlite-telemetry-materialization",
        schemaRepoRelativePath = SQLITE_TELEMETRY_MATERIALIZATION_AUTHORITY,
        governedRelativePathMarkers =
          listOf(
            "infrastructure/sqlite/telemetry/",
          ),
      ),
      GovernedPayloadSeam(
        seamId = "sqlite-review-telemetry",
        schemaRepoRelativePath = SQLITE_REVIEW_TELEMETRY_AUTHORITY,
        governedRelativePathMarkers =
          listOf(
            "infrastructure/sqlite/review/",
            "ports/telemetry/model/ReviewFinishedTelemetryPayload",
          ),
      ),
      GovernedPayloadSeam(
        seamId = "telemetry-proxy",
        schemaRepoRelativePath = TELEMETRY_PROXY_AUTHORITY,
        governedRelativePathMarkers =
          listOf(
            "infrastructure/http/",
            "cli/telemetry/TelemetryCliResultMappers",
          ),
      ),
    )

  const val GOAL_CONTINUATION_ARTIFACT_SCHEMA_AUTHORITY: String =
    "internal/feature-task-runtime-goal-continuation-artifact"

  const val GOAL_RUNNER_CONTROLS_AUTHORITY: String =
    "internal/goal-runner-controls"

  const val SQLITE_TELEMETRY_MATERIALIZATION_AUTHORITY: String =
    "internal/sqlite-telemetry-materialization"

  const val SQLITE_REVIEW_TELEMETRY_AUTHORITY: String =
    "internal/sqlite-review-telemetry"

  const val TELEMETRY_PROXY_AUTHORITY: String =
    "internal/telemetry-proxy"

  fun closedSchemaPropertyKeys(schemaRepoRelativePath: String): Set<String> =
    when (schemaRepoRelativePath) {
      DecompositionManifestSchemaPaths.REPO_RELATIVE_PATH ->
        decompositionManifestGovernedKeys(
          loadRepoSchema(schemaRepoRelativePath),
        )
      DecompositionManifestBundleJournalSchemaPaths.REPO_RELATIVE_PATH ->
        bundleJournalGovernedKeys(
          loadRepoSchema(schemaRepoRelativePath),
        )
      FeatureTaskRuntimeReadinessEvidenceSchemaPaths.REPO_RELATIVE_PATH ->
        readinessEvidenceGovernedKeys(
          loadRepoSchema(schemaRepoRelativePath),
        )
      FeatureTaskRuntimeExecutionPlanSchemaPaths.REPO_RELATIVE_PATH ->
        executionPlanGovernedKeys(loadRepoSchema(schemaRepoRelativePath))
      GOAL_CONTINUATION_ARTIFACT_SCHEMA_AUTHORITY -> goalContinuationArtifactGovernedKeys()
      GOAL_RUNNER_CONTROLS_AUTHORITY -> goalRunnerControlsGovernedKeys()
      SQLITE_TELEMETRY_MATERIALIZATION_AUTHORITY -> sqliteTelemetryMaterializationGovernedKeys()
      SQLITE_REVIEW_TELEMETRY_AUTHORITY -> sqliteReviewTelemetryGovernedKeys()
      TELEMETRY_PROXY_AUTHORITY -> telemetryProxyGovernedKeys()
      else -> emptySet()
    }

  private fun executionPlanGovernedKeys(node: JsonNode): Set<String> =
    buildSet {
      if (node.isObject) addAll(propertyNames(node.path("properties")))
      if (node.isContainerNode) node.elements().forEachRemaining { addAll(executionPlanGovernedKeys(it)) }
    }

  private fun telemetryProxyGovernedKeys(): Set<String> =
    payloadKeyValues(TelemetryProxyPayloadKeys::class.java) + SharedPayloadKeys.CONTRACT_VERSION

  private fun sqliteTelemetryMaterializationGovernedKeys(): Set<String> =
    payloadKeyValues(
      SharedPayloadKeys::class.java,
      LifecycleTelemetryPayloadKeys::class.java,
      GoalTelemetryPayloadKeys::class.java,
      SqliteLifecycleTelemetryMaterializationPayloadKeys::class.java,
    )

  private fun sqliteReviewTelemetryGovernedKeys(): Set<String> =
    payloadKeyValues(
      SharedPayloadKeys::class.java,
      SqliteReviewTelemetryPayloadKeys::class.java,
      ReviewFindingPayloadKeys::class.java,
      ReviewFinishedTelemetryPayloadKeys::class.java,
      ReviewVerificationSignalKeys::class.java,
    ) +
      setOf(
        LifecycleTelemetryPayloadKeys.EVENT_NAME,
        LifecycleTelemetryPayloadKeys.GAPS_FOUND,
        GoalTelemetryPayloadKeys.FINISHED_AT,
        GoalTelemetryPayloadKeys.PARENT_WORKFLOW_ID,
        GoalTelemetryPayloadKeys.BLOCKED_REASON,
      )

  private fun goalContinuationArtifactGovernedKeys(): Set<String> =
    setOf(
      SharedPayloadKeys.ISSUE_KEY,
      SharedPayloadKeys.SUBTASK_ID,
      FeatureTaskRuntimeGoalContinuationArtifactPayloadKeys.SUPPRESS_PR,
      FeatureTaskRuntimeGoalContinuationArtifactPayloadKeys.GOAL_BRANCH,
      FeatureTaskRuntimeGoalContinuationArtifactPayloadKeys.PARENT_WORKFLOW_ID,
      FeatureTaskRuntimeGoalContinuationArtifactPayloadKeys.CODE_REVIEW_MODE,
      FeatureTaskRuntimeGoalContinuationArtifactPayloadKeys.VALIDATION_DEPTH,
      FeatureTaskRuntimeGoalContinuationArtifactPayloadKeys.QUALITY_GATE_SELECTION,
      FeatureTaskRuntimeGoalContinuationArtifactPayloadKeys.PARALLEL_REVIEW_AGENT,
      FeatureTaskRuntimeGoalContinuationArtifactPayloadKeys.SUBTASK_NAME,
      FeatureTaskRuntimeGoalContinuationArtifactPayloadKeys.AGENT_ADDON_SELECTION,
      FeatureTaskRuntimeGoalContinuationArtifactPayloadKeys.ADDON_SLUG,
      FeatureTaskRuntimeGoalContinuationArtifactPayloadKeys.ADDON_SOURCE_IDENTITY,
      FeatureTaskRuntimeGoalContinuationArtifactPayloadKeys.ADDON_CONTENT_SHA256,
    )

  private fun goalRunnerControlsGovernedKeys(): Set<String> =
    setOf(
      SharedPayloadKeys.SUBTASK_ID,
      DecompositionManifestPayloadKeys.COMMIT_SHA,
      FeatureTaskRuntimeGoalContinuationArtifactPayloadKeys.CODE_REVIEW_MODE,
      FeatureTaskRuntimeGoalContinuationArtifactPayloadKeys.PARALLEL_REVIEW_AGENT,
      FeatureTaskRuntimeGoalContinuationArtifactPayloadKeys.AGENT_ADDON_SELECTION,
      FeatureTaskRuntimeGoalContinuationArtifactPayloadKeys.ADDON_SLUG,
      FeatureTaskRuntimeGoalContinuationArtifactPayloadKeys.ADDON_SOURCE_IDENTITY,
      FeatureTaskRuntimeGoalContinuationArtifactPayloadKeys.ADDON_CONTENT_SHA256,
      FeatureTaskRuntimeGoalContinuationArtifactPayloadKeys.ACCEPTANCE_REASON,
      FeatureTaskRuntimeGoalContinuationArtifactPayloadKeys.ACCEPTED_AT,
    )

  private fun decompositionManifestGovernedKeys(schema: JsonNode): Set<String> {
    val keys = mutableSetOf<String>()
    keys += propertyNames(schema.path("properties"))
    listOf("subtask", "dependency", "stackBranch", "currentSubtaskIntent").forEach { defName ->
      keys += propertyNames(schema.path("\$defs").path(defName).path("properties"))
    }
    return keys
  }

  private fun bundleJournalGovernedKeys(schema: JsonNode): Set<String> {
    val keys = mutableSetOf<String>()
    keys += propertyNames(schema.path("properties"))
    keys += propertyNames(schema.path("\$defs").path("entry").path("properties"))
    return keys
  }

  private fun readinessEvidenceGovernedKeys(schema: JsonNode): Set<String> =
    propertyNames(schema.path("properties")).toSet() +
      propertyNames(
        schema.path("properties").path("check_results").path("items").path("properties"),
      )

  private fun propertyNames(propertiesNode: JsonNode): List<String> {
    if (!propertiesNode.isObject) return emptyList()
    val names = mutableListOf<String>()
    propertiesNode.fields().forEachRemaining { entry -> names += entry.key }
    return names
  }

  fun schemaFieldsMissingKotlinOwner(
    schemaRepoRelativePath: String,
    declaredKeyValues: Set<String>,
  ): List<String> =
    schemaFieldsMissingKotlinOwner(
      closedSchemaPropertyKeys(schemaRepoRelativePath),
      schemaRepoRelativePath,
      declaredKeyValues,
    )

  fun schemaFieldsMissingKotlinOwner(
    schemaPropertyKeys: Set<String>,
    schemaAuthority: String,
    declaredKeyValues: Set<String>,
  ): List<String> {
    val missing = schemaPropertyKeys.filter { it !in declaredKeyValues }
    return missing.sorted().map { field ->
      "schema field '$field' in $schemaAuthority has no owning runtime-contracts *Keys const"
    }
  }

  fun fileMatchesGovernedSeam(relativePath: String): Boolean =
    seams.any { seam -> seam.governedRelativePathMarkers.any(relativePath::contains) }

  private fun loadRepoSchema(repoRelativePath: String): JsonNode {
    val path = repoRootFromTest().resolve(repoRelativePath)
    assertTrue(Files.isRegularFile(path), "Missing canonical schema at $path")
    return YAMLMapper().readTree(Files.readString(path))
  }
}

internal fun payloadKeyValues(vararg owners: Class<*>): Set<String> =
  owners.flatMap { owner ->
    owner.declaredFields
      .filter { field -> field.type == String::class.java }
      .map { field ->
        field.isAccessible = true
        field.get(null) as String
      }
  }.toSet()
