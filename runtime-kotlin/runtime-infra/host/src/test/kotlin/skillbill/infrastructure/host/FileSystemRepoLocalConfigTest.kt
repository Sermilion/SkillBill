package skillbill.infrastructure.host

import skillbill.config.model.RepoLocalConfig
import skillbill.config.model.SpecType
import skillbill.error.shellcontent.MalformedRepoLocalConfigError
import skillbill.ports.config.model.ReadRepoLocalConfigRequest
import skillbill.ports.diagnostics.RuntimeDiagnostics
import skillbill.review.context.model.accounting.ReviewContextBudgetPolicy
import java.nio.file.Files
import java.nio.file.Path
import kotlin.test.Test
import kotlin.test.assertContains
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

class FileSystemRepoLocalConfigTest {
  private val adapter = FileSystemRepoLocalConfig(RecordingDiagnostics())

  @Test
  fun `reads typed values from a valid config file`() {
    val repoRoot = writeConfig("spec_type: linear")

    val config = adapter.readRepoLocalConfig(ReadRepoLocalConfigRequest(repoRoot)).config

    assertEquals(SpecType.LINEAR, config.specType)
  }

  @Test
  fun `missing config file yields built-in defaults with no error`() {
    val repoRoot = Files.createTempDirectory("skillbill-repo-local-config-missing")

    val config = adapter.readRepoLocalConfig(ReadRepoLocalConfigRequest(repoRoot)).config

    assertEquals(RepoLocalConfig.defaults(), config)
    assertEquals(SpecType.LOCAL, config.specType)
  }

  @Test
  fun `absent known keys fall back to built-in defaults`() {
    val repoRoot = writeConfig("spec_type: linear")

    val config = adapter.readRepoLocalConfig(ReadRepoLocalConfigRequest(repoRoot)).config

    assertEquals(SpecType.LINEAR, config.specType)
  }

  @Test
  fun `malformed yaml loud-fails naming the file at document root`() {
    val repoRoot = writeConfig("spec_type: [unterminated")

    val error =
      assertFailsWith<MalformedRepoLocalConfigError> {
        adapter.readRepoLocalConfig(ReadRepoLocalConfigRequest(repoRoot))
      }

    assertContains(error.message.orEmpty(), repoRoot.fileName.toString())
    assertEquals("", error.key)
    assertContains(error.message.orEmpty(), "<root>")
  }

  @Test
  fun `non-scalar value for a known key loud-fails naming the key`() {
    val repoRoot =
      writeConfig(
        """
        spec_type:
          - local
          - linear
        """.trimIndent(),
      )

    val error =
      assertFailsWith<MalformedRepoLocalConfigError> {
        adapter.readRepoLocalConfig(ReadRepoLocalConfigRequest(repoRoot))
      }

    assertEquals("spec_type", error.key)
    assertContains(error.message.orEmpty(), "spec_type")
  }

  @Test
  fun `invalid value for a known key loud-fails naming key and value`() {
    val repoRoot = writeConfig("spec_type: nonsense")

    val error =
      assertFailsWith<MalformedRepoLocalConfigError> {
        adapter.readRepoLocalConfig(ReadRepoLocalConfigRequest(repoRoot))
      }

    assertEquals("spec_type", error.key)
    assertEquals("nonsense", error.value)
    assertContains(error.message.orEmpty(), "spec_type")
    assertContains(error.message.orEmpty(), "nonsense")
  }

  @Test
  fun `non-none parallel agent config loud-fails naming removed capability`() {
    val repoRoot = writeConfig("code_review_parallel_agent: claude")

    val error =
      assertFailsWith<MalformedRepoLocalConfigError> {
        adapter.readRepoLocalConfig(ReadRepoLocalConfigRequest(repoRoot))
      }

    assertEquals("code_review_parallel_agent", error.key)
    assertEquals("claude", error.value)
    assertContains(error.message.orEmpty(), "removed capability")
  }

  @Test
  fun `code_review_parallel_agent none is ignored as legacy key`() {
    val repoRoot =
      writeConfig(
        """
        spec_type: linear
        code_review_parallel_agent: none
        """.trimIndent(),
      )

    val config = adapter.readRepoLocalConfig(ReadRepoLocalConfigRequest(repoRoot)).config

    assertEquals(SpecType.LINEAR, config.specType)
  }

  @Test
  fun `validation gate gradle_wrapper is read as a repo-relative path`() {
    val repoRoot =
      writeConfig(
        """
        validation_gate:
          gradle_wrapper: runtime-kotlin/gradlew
        """.trimIndent(),
      )

    val config = adapter.readRepoLocalConfig(ReadRepoLocalConfigRequest(repoRoot)).config

    assertEquals("runtime-kotlin/gradlew", config.validationGate.gradleWrapper)
  }

  @Test
  fun `validation gate gradle_wrapper rejects absolute and traversal paths`() {
    val absolute =
      writeConfig(
        """
        validation_gate:
          gradle_wrapper: /abs/gradlew
        """.trimIndent(),
      )
    val absoluteError =
      assertFailsWith<MalformedRepoLocalConfigError> {
        adapter.readRepoLocalConfig(ReadRepoLocalConfigRequest(absolute))
      }
    assertEquals("validation_gate.gradle_wrapper", absoluteError.key)

    val traversal =
      writeConfig(
        """
        validation_gate:
          gradle_wrapper: ../gradlew
        """.trimIndent(),
      )
    val traversalError =
      assertFailsWith<MalformedRepoLocalConfigError> {
        adapter.readRepoLocalConfig(ReadRepoLocalConfigRequest(traversal))
      }
    assertEquals("validation_gate.gradle_wrapper", traversalError.key)
  }

  @Test
  fun `unknown future keys are tolerated without error and do not affect known values`() {
    val repoRoot =
      writeConfig(
        """
        spec_type: linear
        future_unrelated_key: some-value
        """.trimIndent(),
      )

    val config = adapter.readRepoLocalConfig(ReadRepoLocalConfigRequest(repoRoot)).config

    assertEquals(SpecType.LINEAR, config.specType)
  }

  @Test
  fun `a config still carrying the retired experiments block loads its other values`() {
    val diagnostics = RecordingDiagnostics()
    val repoRoot =
      writeConfig(
        """
        spec_type: linear
        experiments:
          availability: on_request
        """.trimIndent(),
      )

    val config =
      FileSystemRepoLocalConfig(diagnostics).readRepoLocalConfig(ReadRepoLocalConfigRequest(repoRoot)).config

    assertEquals(SpecType.LINEAR, config.specType)
    assertTrue(diagnostics.warnings.isEmpty())
  }

  @Test
  fun `review context budget accepts retained and retired keys`() {
    val diagnostics = RecordingDiagnostics()
    val repoRoot =
      writeConfig(
        """
        review_context_budget:
          max_parent_packet_bytes: 700000
          max_lane_launch_bytes: 70000
          max_lane_evidence_bytes: 300000
          max_evidence_result_bytes: 70000
          max_lane_result_bytes: 70000
          max_assignment_expansions: 5
          max_specialist_tool_calls: 50
          max_specialist_model_turns: 30
          max_routing_analysis_pairs: 1024
          max_routing_analysis_bytes: 2048000
          provider_token_thresholds:
            input_tokens: 41000
            cached_input_tokens: 31000
            output_tokens: 9000
            reasoning_tokens: 11000
            total_tokens: 57000
        """.trimIndent(),
      )

    val budget =
      FileSystemRepoLocalConfig(diagnostics)
        .readRepoLocalConfig(ReadRepoLocalConfigRequest(repoRoot)).config.reviewContextBudget

    assertEquals(700_000, budget.maxParentPacketBytes)
    assertEquals(70_000, budget.maxLaneLaunchBytes)
    assertEquals(300_000, budget.maxLaneEvidenceBytes)
    assertEquals(70_000, budget.maxEvidenceResultBytes)
    assertEquals(70_000, budget.maxLaneResultBytes)
    assertEquals(5, budget.maxAssignmentExpansions)
    assertEquals(50, budget.maxSpecialistToolCalls)
    assertEquals(30, budget.maxSpecialistModelTurns)
    assertEquals(1_024, budget.maxRoutingAnalysisPairs)
    assertEquals(2_048_000, budget.maxRoutingAnalysisBytes)
    assertEquals(1, diagnostics.warnings.size)
    assertContains(diagnostics.warnings.single(), "provider_token_thresholds")
  }

  @Test
  fun `review context budget without retired block emits no degradation`() {
    val diagnostics = RecordingDiagnostics()
    val repoRoot =
      writeConfig(
        """
        review_context_budget:
          max_parent_packet_bytes: 700000
        """.trimIndent(),
      )

    FileSystemRepoLocalConfig(diagnostics).readRepoLocalConfig(ReadRepoLocalConfigRequest(repoRoot))

    assertTrue(diagnostics.warnings.isEmpty())
  }

  @Test
  fun `review context budget rejects a non-positive routing analysis override`() {
    val repoRoot =
      writeConfig(
        """
        review_context_budget:
          max_routing_analysis_pairs: 0
        """.trimIndent(),
      )

    val error =
      assertFailsWith<MalformedRepoLocalConfigError> {
        adapter.readRepoLocalConfig(ReadRepoLocalConfigRequest(repoRoot))
      }

    assertEquals("review_context_budget", error.key)
    assertContains(error.message.orEmpty(), "must be positive")
  }

  @Test
  fun `review context budget rejects an unsupported nested key naming it`() {
    val repoRoot =
      writeConfig(
        """
        review_context_budget:
          review_context_pilot_mode: delegated
        """.trimIndent(),
      )

    val error =
      assertFailsWith<MalformedRepoLocalConfigError> {
        adapter.readRepoLocalConfig(ReadRepoLocalConfigRequest(repoRoot))
      }

    assertEquals("review_context_budget.review_context_pilot_mode", error.key)
    assertContains(error.message.orEmpty(), "is not a recognized key")
  }

  @Test
  fun `review context budget overrides merge over governed defaults`() {
    val repoRoot =
      writeConfig(
        """
        review_context_budget:
          max_parent_packet_bytes: 600000
          max_lane_launch_bytes: 60000
          max_assignment_expansions: 1
          provider_token_thresholds:
            total_tokens: 100000
        """.trimIndent(),
      )
    val budget = adapter.readRepoLocalConfig(ReadRepoLocalConfigRequest(repoRoot)).config.reviewContextBudget
    assertEquals(600_000, budget.maxParentPacketBytes)
    assertEquals(60_000, budget.maxLaneLaunchBytes)
    assertEquals(1, budget.maxAssignmentExpansions)
    assertEquals(ReviewContextBudgetPolicy.DEFAULT.maxRoutingAnalysisPairs, budget.maxRoutingAnalysisPairs)
    assertEquals(ReviewContextBudgetPolicy.DEFAULT.maxRoutingAnalysisBytes, budget.maxRoutingAnalysisBytes)
  }

  @Test
  fun `review context budget rejects unknown nested keys before launch`() {
    val repoRoot =
      writeConfig(
        """
        review_context_budget:
          silently_truncate: true
        """.trimIndent(),
      )
    val error =
      assertFailsWith<MalformedRepoLocalConfigError> {
        adapter.readRepoLocalConfig(ReadRepoLocalConfigRequest(repoRoot))
      }
    assertEquals("review_context_budget.silently_truncate", error.key)
  }

  @Test
  fun `review context budget rejects inconsistent limits before launch`() {
    val repoRoot =
      writeConfig(
        """
        review_context_budget:
          max_lane_evidence_bytes: 10
          max_evidence_result_bytes: 11
        """.trimIndent(),
      )
    assertFailsWith<MalformedRepoLocalConfigError> {
      adapter.readRepoLocalConfig(ReadRepoLocalConfigRequest(repoRoot))
    }
  }

  @Test
  fun `review context budget rejects explicit null fractional and narrowing values`() {
    listOf(
      "review_context_budget: null",
      "review_context_budget:\n  max_lane_launch_bytes: 1.5",
      "review_context_budget:\n  max_assignment_expansions: 2147483648",
      "review_context_budget:\n  max_lane_launch_bytes: null",
    ).forEach { content ->
      val repoRoot = writeConfig(content)
      assertFailsWith<MalformedRepoLocalConfigError>(content) {
        adapter.readRepoLocalConfig(ReadRepoLocalConfigRequest(repoRoot))
      }
    }
  }

  @Test
  fun `ignores execution matrix because it belongs to the machine config`() {
    val repoRoot =
      writeConfig(
        """
        spec_type: linear
        execution_matrix:
          invalid: repo-local matrices are ignored
        """.trimIndent(),
      )

    val config = adapter.readRepoLocalConfig(ReadRepoLocalConfigRequest(repoRoot)).config

    assertEquals(SpecType.LINEAR, config.specType)
  }

  private fun writeConfig(content: String): Path {
    val repoRoot = Files.createTempDirectory("skillbill-repo-local-config")
    val configPath = repoRoot.resolve(".skill-bill").resolve("config.yaml")
    Files.createDirectories(configPath.parent)
    Files.writeString(configPath, content)
    return repoRoot
  }

  private class RecordingDiagnostics : RuntimeDiagnostics {
    val warnings = mutableListOf<String>()

    override fun warning(
      message: String,
      error: Throwable?,
    ) {
      warnings += message
    }

    override fun error(
      message: String,
      error: Throwable?,
    ) = Unit
  }
}
