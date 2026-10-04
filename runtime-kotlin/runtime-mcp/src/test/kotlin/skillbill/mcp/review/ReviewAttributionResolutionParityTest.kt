package skillbill.mcp.review

import skillbill.cli.core.CliRuntime
import skillbill.cli.model.CliRuntimeContext
import skillbill.error.core.SkillBillRuntimeException
import skillbill.mcp.shared.McpRuntimeContext
import skillbill.mcp.shared.callToolPayload
import skillbill.review.attribution.canonicalPlatformSlugs
import skillbill.review.attribution.resolveCanonicalRoutedSkill
import skillbill.review.attribution.resolveCanonicalStack
import skillbill.review.model.ReviewAttributionFailureCode
import skillbill.telemetry.CONFIG_ENVIRONMENT_KEY
import java.nio.file.Files
import java.nio.file.Path
import java.sql.DriverManager
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

class ReviewAttributionResolutionParityTest {
  private val resolvableReview =
    """
    Routed to: bill-kotlin-code-review
    Review session ID: rvs-parity-ok
    Review run ID: rvw-parity-ok
    Detected review scope: commit range (main..HEAD)
    Detected stack: Kotlin/JVM
    Execution mode: inline

    ### 2. Risk Register
    No findings.
    """.trimIndent()

  private val unresolvableReview =
    """
    Routed to: bill-kmp-code-review, bill-ios-code-review
    Review session ID: rvs-parity-bad
    Review run ID: rvw-parity-bad
    Detected review scope: whatever the agent felt like
    Detected stack: kotlin, ios

    ### 2. Risk Register
    No findings.
    """.trimIndent()

  @Test
  fun `both surfaces persist the same canonical attribution for a resolvable review`() {
    assertEquals(
      importedAttributionViaCli(resolvableReview, "rvw-parity-ok"),
      importedAttributionViaMcp(resolvableReview, "rvw-parity-ok"),
    )
    assertEquals(
      listOf("bill-kotlin-code-review", "kotlin", "commit_range", "main..HEAD", "inline"),
      importedAttributionViaCli(resolvableReview, "rvw-parity-ok").drop(1),
    )
  }

  @Test
  fun `both surfaces record the explicit unresolved marker without a silent fallback`() {
    val viaCli = importedAttributionViaCli(unresolvableReview, "rvw-parity-bad")
    val viaMcp = importedAttributionViaMcp(unresolvableReview, "rvw-parity-bad")

    assertEquals(viaCli, viaMcp)
    assertEquals(
      listOf(
        "bill-kmp-code-review, bill-ios-code-review",
        "unresolved",
        "unresolved",
        "unresolved",
        null,
        "unresolved",
      ),
      viaCli,
    )
  }

  @Test
  fun `a resolver contract failure is the same typed error for both surfaces`() {
    val malformedCatalog = setOf("Bill KMP Code Review")

    val routedFailure =
      assertFailsWith<SkillBillRuntimeException> {
        resolveCanonicalRoutedSkill("bill-kmp-code-review", malformedCatalog)
      }
    val stackFailure =
      assertFailsWith<SkillBillRuntimeException> {
        resolveCanonicalStack("kotlin", canonicalPlatformSlugs + "Kotlin JVM")
      }

    assertEquals(ReviewAttributionFailureCode.MALFORMED_VOCABULARY, routedFailure.code)
    assertEquals(
      "Review attribution vocabulary 'pack_skill_names' contains the malformed entry 'Bill KMP Code Review' " +
        "while resolving 'bill-kmp-code-review'.",
      routedFailure.message,
    )
    assertEquals(ReviewAttributionFailureCode.MALFORMED_VOCABULARY, stackFailure.code)
    assertEquals(
      "Review attribution vocabulary 'platform_slugs' contains the malformed entry 'Kotlin JVM' " +
        "while resolving 'kotlin'.",
      stackFailure.message,
    )
  }

  private fun importedAttributionViaCli(
    reviewText: String,
    reviewRunId: String,
  ): List<String?> {
    val tempDir = Files.createTempDirectory("skillbill-parity-cli")
    val reviewFile = tempDir.resolve("review.md")
    Files.writeString(reviewFile, reviewText)
    val result =
      CliRuntime.run(
        listOf("--db", tempDir.resolve("metrics.db").toString(), "import-review", reviewFile.toString()),
        CliRuntimeContext(environment = telemetryEnvironment(tempDir), userHome = tempDir),
      )
    check(result.exitCode == 0) { "CLI import failed: ${result.stdout}" }
    return attributionRow(tempDir.resolve("metrics.db"), reviewRunId)
  }

  private fun importedAttributionViaMcp(
    reviewText: String,
    reviewRunId: String,
  ): List<String?> {
    val tempDir = Files.createTempDirectory("skillbill-parity-mcp")
    McpRuntimeContext(environment = telemetryEnvironment(tempDir), userHome = tempDir)
      .callToolPayload("import_review", mapOf("review_text" to reviewText))
    return attributionRow(tempDir.resolve("metrics.db"), reviewRunId)
  }

  private fun attributionRow(
    dbPath: Path,
    reviewRunId: String,
  ): List<String?> =
    DriverManager.getConnection("jdbc:sqlite:$dbPath").use { connection ->
      connection.prepareStatement(
        """
        SELECT routed_skill, routed_skill_canonical, detected_stack_canonical,
               detected_scope_canonical, detected_scope_detail, execution_mode
        FROM review_runs
        WHERE review_run_id = ?
        """.trimIndent(),
      ).use { statement ->
        statement.setString(1, reviewRunId)
        statement.executeQuery().use { rows ->
          check(rows.next()) { "No review_runs row for '$reviewRunId'." }
          (1..6).map(rows::getString)
        }
      }
    }

  private fun telemetryEnvironment(tempDir: Path): Map<String, String> {
    val configPath = tempDir.resolve("config.json")
    Files.writeString(
      configPath,
      """
      {
        "install_id": "test-install-id",
        "telemetry": {
          "level": "anonymous",
          "proxy_url": "",
          "batch_size": 50
        }
      }
      """.trimIndent() + "\n",
    )
    return mapOf(
      "SKILL_BILL_REVIEW_DB" to tempDir.resolve("metrics.db").toString(),
      CONFIG_ENVIRONMENT_KEY to configPath.toString(),
    )
  }
}
