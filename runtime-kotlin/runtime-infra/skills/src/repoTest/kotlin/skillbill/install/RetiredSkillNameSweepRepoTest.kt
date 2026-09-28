package skillbill.install

import skillbill.testing.repoRootFromTest
import java.io.File
import java.nio.file.FileVisitResult
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.SimpleFileVisitor
import java.nio.file.attribute.BasicFileAttributes
import kotlin.io.path.name
import kotlin.test.Test
import kotlin.test.assertEquals

class RetiredSkillNameSweepRepoTest {
  @Test
  fun `retired skill names survive only as allowlisted labels and every allowlisted label is still present`() {
    val repoRoot = repoRootFromTest()
    val found = retiredNameTokensByFile(repoRoot)

    val unexpected =
      found
        .mapValues { (file, tokens) -> tokens - ALLOWED_RETIRED_NAMES[file].orEmpty() }
        .filterValues { tokens -> tokens.isNotEmpty() }
    val stale =
      ALLOWED_RETIRED_NAMES
        .mapValues { (file, tokens) -> tokens - found[file].orEmpty() }
        .filterValues { tokens -> tokens.isNotEmpty() }

    assertEquals(emptyMap<String, Set<String>>(), unexpected, "retired skill names outside the allowlist")
    assertEquals(emptyMap<String, Set<String>>(), stale, "allowlisted labels no longer present; drop them")
  }

  private fun retiredNameTokensByFile(repoRoot: Path): Map<String, Set<String>> =
    scannedFiles(repoRoot)
      .associate { file ->
        val text = String(Files.readAllBytes(file), Charsets.UTF_8)
        relativePath(repoRoot, file) to WHOLE_RETIRED_NAME.findAll(text).map { match -> match.value }.toSet()
      }.filterValues { tokens -> tokens.isNotEmpty() }

  private fun scannedFiles(repoRoot: Path): List<Path> =
    listOf("skills", "orchestration", "platform-packs").flatMap { root -> regularFiles(repoRoot.resolve(root)) } +
      regularFiles(repoRoot.resolve("runtime-kotlin")).filter { file ->
        "/src/main/" in "/${relativePath(repoRoot, file)}"
      }

  private fun regularFiles(root: Path): List<Path> {
    val files = mutableListOf<Path>()
    Files.walkFileTree(
      root,
      object : SimpleFileVisitor<Path>() {
        override fun preVisitDirectory(
          dir: Path,
          attrs: BasicFileAttributes,
        ): FileVisitResult = if (dir.name in SKIPPED_DIRS) FileVisitResult.SKIP_SUBTREE else FileVisitResult.CONTINUE

        override fun visitFile(
          file: Path,
          attrs: BasicFileAttributes,
        ): FileVisitResult {
          if (attrs.isRegularFile && !isBoundaryLedger(file)) {
            files.add(file)
          }
          return FileVisitResult.CONTINUE
        }
      },
    )
    return files
  }

  private fun isBoundaryLedger(file: Path): Boolean = file.name in LEDGER_FILES && file.parent?.name == "agent"

  private fun relativePath(
    repoRoot: Path,
    file: Path,
  ): String = repoRoot.relativize(file).toString().replace(File.separatorChar, '/')

  private companion object {
    val SKIPPED_DIRS = setOf("build", ".gradle", ".kotlin")
    val LEDGER_FILES = setOf("history.md", "decisions.md")

    val RETIRED_SKILLS =
      listOf(
        "bill-feature",
        "bill-feature-spec",
        "bill-code-review",
        "bill-code-review-inline",
        "bill-code-check",
        "bill-pr-description",
        "bill-boundary-history",
        "bill-boundary-decisions",
        "bill-pr-review-fix",
        "bill-unit-test-value-check",
        "bill-update-check",
        "bill-release",
        "bill-feature-verify",
        "bill-feature-guard",
        "bill-feature-guard-cleanup",
        "bill-monitor",
      )

    val WHOLE_RETIRED_NAME =
      Regex(
        RETIRED_SKILLS.sortedByDescending(String::length).joinToString(
          separator = "|",
          prefix = "(?<![A-Za-z0-9.-])(?:",
          postfix = ")(?![A-Za-z0-9-])",
        ),
      )

    const val APP = "runtime-kotlin/runtime-application/src/main/kotlin/skillbill/application"
    const val CLI = "runtime-kotlin/runtime-cli/src/main/kotlin/skillbill/cli"
    const val DOMAIN = "runtime-kotlin/runtime-domain/src/main/kotlin/skillbill"
    const val ENGINE = "runtime-kotlin/runtime-engine/src/main/kotlin/skillbill/engine"
    const val ENGINE_RESOURCES = "runtime-kotlin/runtime-engine/src/main/resources/skillbill/engine"
    const val SLOT = "$ENGINE_RESOURCES/featuretask/slot"
    const val MCP = "runtime-kotlin/runtime-mcp/src/main/kotlin/skillbill/mcp"
    const val SKILLS_INFRA = "runtime-kotlin/runtime-infra/skills/src/main/kotlin/skillbill/infrastructure/skills"
    const val SQLITE = "runtime-kotlin/runtime-infra/sqlite/src/main/kotlin/skillbill/infrastructure/sqlite"
    const val VERIFY_LABEL = "bill-feature-verify"

    val STABLE_WIRE_LABELS: Map<String, Set<String>> =
      mapOf(
        "$APP/telemetry/lifecycle/LifecycleTelemetryPayloads.kt" to
          setOf("bill-code-check", VERIFY_LABEL, "bill-pr-description"),
        "$APP/telemetry/lifecycle/LifecycleTelemetryGoalEmission.kt" to setOf("bill-code-check"),
        "$APP/telemetry/settings/TelemetrySettingsLoading.kt" to setOf(VERIFY_LABEL),
        "$APP/review/stats/ReviewStatsContractWorkflowPayloadMappers.kt" to setOf(VERIFY_LABEL),
        "$ENGINE/featuretask/phaserun/InMemoryPhaseRunState.kt" to setOf("bill-code-check"),
        "$ENGINE/operation/verify/VerifyTelemetry.kt" to setOf(VERIFY_LABEL),
        "$MCP/review/McpAdapterContracts.kt" to setOf("bill-code-review"),
        "$DOMAIN/telemetry/TelemetryConstants.kt" to setOf(VERIFY_LABEL),
        "$DOMAIN/workflow/verify/FeatureVerifyWorkflowDefinition.kt" to setOf(VERIFY_LABEL),
        "$DOMAIN/workflow/engine/WorkflowEngineContinuationPrompts.kt" to setOf(VERIFY_LABEL),
        "$SQLITE/core/schema/DatabaseSchemaStatements.kt" to setOf(VERIFY_LABEL),
        "$SQLITE/core/migration/DatabaseColumnMigrationsEnsure.kt" to setOf(VERIFY_LABEL),
        "$SQLITE/workflow/WorkflowStateWrites.kt" to setOf("bill-feature"),
      )

    val SCHEMA_ENUM_LABELS: Map<String, Set<String>> =
      mapOf(
        "orchestration/contracts/workflow-state-schema.yaml" to setOf(VERIFY_LABEL),
        "orchestration/contracts/telemetry-event-schema.yaml" to setOf(VERIFY_LABEL),
      )

    val VERIFY_WORKFLOW_HELP_LABELS: Map<String, Set<String>> =
      mapOf(
        "$MCP/core/McpToolRegistry.kt" to setOf(VERIFY_LABEL),
        "$CLI/review/ReviewCliCommands.kt" to setOf(VERIFY_LABEL),
        "$CLI/workflow/WorkflowCliCommands.kt" to setOf(VERIFY_LABEL),
      )

    val LEGACY_CLEANUP_NAMES: Map<String, Set<String>> =
      mapOf(
        "$SKILLS_INFRA/install/apply/InstallLegacySkillNames.kt" to RETIRED_SKILLS.toSet(),
        "$SKILLS_INFRA/agentaddon/AgentAddonConsumerMigration.kt" to setOf("bill-feature"),
        "$DOMAIN/agentaddon/model/AgentAddonModels.kt" to setOf("bill-feature"),
        "orchestration/contracts/agent-addon-schema.yaml" to setOf("bill-feature"),
      )

    val VERBATIM_DIRECTIVE_SELF_NAMES: Map<String, Set<String>> =
      mapOf(
        "$SLOT/plan/feature-spec-directive.md" to setOf("bill-feature-spec"),
        "$ENGINE_RESOURCES/operation/verify/verify-directive.md" to setOf(VERIFY_LABEL),
      )

    val ALLOWED_RETIRED_NAMES: Map<String, Set<String>> =
      STABLE_WIRE_LABELS + SCHEMA_ENUM_LABELS + VERIFY_WORKFLOW_HELP_LABELS + LEGACY_CLEANUP_NAMES +
        VERBATIM_DIRECTIVE_SELF_NAMES
  }
}
