package skillbill.architecture

import skillbill.contracts.workflow.featuretask.FEATURE_TASK_RUNTIME_CONTRACT_VERSION
import java.nio.file.Files
import java.nio.file.Path
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class PhaseOutputContractVersionPinParityTest {
  private val runtimeRoot: Path =
    Path.of("").toAbsolutePath().normalize().let { workingDir ->
      if (workingDir.fileName.toString().startsWith("runtime-")) {
        workingDir.parent
      } else {
        workingDir
      }
    }
  private val repoRoot: Path = runtimeRoot.parent

  @Test
  fun `every phase-output contract version pin equals FEATURE_TASK_RUNTIME_CONTRACT_VERSION`() {
    val pins = discoveredPins()

    assertEquals(
      EXPECTED_PIN_NAMES,
      pins.keys,
      "every phase-output contract version pin must be discovered; missing: ${EXPECTED_PIN_NAMES - pins.keys}",
    )
    val divergent = pins.filterValues { version -> version != FEATURE_TASK_RUNTIME_CONTRACT_VERSION }
    assertTrue(
      divergent.isEmpty(),
      "phase-output contract version pins must equal FEATURE_TASK_RUNTIME_CONTRACT_VERSION " +
        "($FEATURE_TASK_RUNTIME_CONTRACT_VERSION); a bump moves every pin in the same change:\n" +
        divergent.entries.joinToString(separator = "\n") { (pin, version) -> "$pin = $version" },
    )
  }

  private fun discoveredPins(): Map<String, String> =
    buildMap {
      firstGroup(read(GOAL_PLANNING_SCHEMA), GOAL_PLANNING_CONST)?.let { put(PIN_GOAL_PLANNING_SCHEMA, it) }
      val sqlChecks = SQL_CHECK.findAll(read(SCHEMA_STATEMENTS)).map { newestVersion(it.groupValues[1]) }.toList()
      if (sqlChecks.size == EXPECTED_SQL_CHECK_COUNT) {
        sqlChecks.forEachIndexed { index, version -> version?.let { put("$PIN_SQL_CHECK_PREFIX${index + 1}", it) } }
      }
      LATEST_MIGRATION.findAll(read(MIGRATION_ENTRIES))
        .map { match -> "${match.groupValues[1]}.${match.groupValues[2]}" }
        .maxWithOrNull(compareBy(::versionKey))
        ?.let { put(PIN_LATEST_MIGRATION, it) }
    }

  private fun read(repoRelativePath: String): String {
    val path = repoRoot.resolve(repoRelativePath)
    assertTrue(Files.isRegularFile(path), "expected pin source at $repoRelativePath")
    return Files.readString(path)
  }

  private fun firstGroup(
    text: String,
    pattern: Regex,
  ): String? = pattern.find(text)?.groupValues?.get(1)

  private fun newestVersion(inList: String): String? =
    QUOTED_VERSION.findAll(inList).map { it.groupValues[1] }.maxWithOrNull(compareBy(::versionKey))

  private fun versionKey(version: String): Long =
    version.split('.').fold(0L) { acc, part -> acc * VERSION_PART_RADIX + part.toLong() }

  private companion object {
    const val GOAL_PLANNING_SCHEMA = "orchestration/contracts/goal-planning-preparation-schema.yaml"
    const val SCHEMA_STATEMENTS =
      "runtime-kotlin/runtime-infra/sqlite/src/main/kotlin/skillbill/infrastructure/sqlite/core/schema/" +
        "DatabaseSchemaStatements.kt"
    const val MIGRATION_ENTRIES =
      "runtime-kotlin/runtime-infra/sqlite/src/main/kotlin/skillbill/infrastructure/sqlite/" +
        "DatabaseMigrationEntries.kt"

    const val PIN_GOAL_PLANNING_SCHEMA = "goal-planning preparation phase_output_contract_version const"
    const val PIN_SQL_CHECK_PREFIX = "goal-planning SQL phase_output_contract_version check #"
    const val PIN_LATEST_MIGRATION = "latest allow-goal-planning-phase-output migration"
    const val EXPECTED_SQL_CHECK_COUNT = 2
    const val VERSION_PART_RADIX = 1_000L

    val EXPECTED_PIN_NAMES: Set<String> =
      setOf(
        PIN_GOAL_PLANNING_SCHEMA,
        "${PIN_SQL_CHECK_PREFIX}1",
        "${PIN_SQL_CHECK_PREFIX}2",
        PIN_LATEST_MIGRATION,
      )

    val GOAL_PLANNING_CONST = Regex("phase_output_contract_version: \\{ type: string, const: \"([^\"]+)\" }")
    val SQL_CHECK = Regex("CHECK \\(phase_output_contract_version IN \\(([^)]*)\\)\\)")
    val QUOTED_VERSION = Regex("'([0-9]+\\.[0-9]+)'")
    val LATEST_MIGRATION = Regex("\"allow-goal-planning-phase-output-([0-9]+)-([0-9]+)\"")
  }
}
