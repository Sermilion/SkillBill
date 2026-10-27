package skillbill.infrastructure.contracts.workflow.goal

import com.fasterxml.jackson.dataformat.yaml.YAMLMapper
import skillbill.contracts.workflow.goal.GOAL_FINALIZATION_HISTORY_CONTRACT_VERSION
import skillbill.error.core.SkillBillRuntimeException
import skillbill.error.shellcontent.WorkflowFailureCode
import skillbill.infrastructure.contracts.locator.GoalFinalizationHistorySchemaPaths
import java.nio.file.Files
import java.nio.file.Path
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

class GoalFinalizationHistorySchemaContractTest {
  @Test
  fun `canonical and bundled schema pin the finalization history contract`() {
    val source = YAMLMapper().readTree(Files.readString(repositorySchemaFile()))
    assertEquals(GoalFinalizationHistorySchemaPaths.EXPECTED_SCHEMA_ID, source.path("\$id").asText())
    assertEquals(
      GOAL_FINALIZATION_HISTORY_CONTRACT_VERSION,
      source.path("properties").path("contract_version").path("const").asText(),
    )
    val bundled =
      checkNotNull(javaClass.classLoader.getResourceAsStream(GoalFinalizationHistorySchemaPaths.CLASSPATH_RESOURCE))
        .use { YAMLMapper().readTree(it) }
    assertEquals(source, bundled)
  }

  @Test
  fun `schema rejects a completion without the post-writer tree identity`() {
    val completion =
      mapOf(
        "input_fingerprint" to "input-fingerprint",
        "pre_writer_content_fingerprint" to "before-writer",
        "accepted_output" to "accepted output",
      )
    val payload =
      mapOf(
        "contract_version" to GOAL_FINALIZATION_HISTORY_CONTRACT_VERSION,
        "repair_context" to null,
        "completion" to completion,
      )
    val failure =
      assertFailsWith<SkillBillRuntimeException> {
        GoalFinalizationHistorySchemaValidator.validate(payload, "test")
      }
    assertEquals(WorkflowFailureCode.INVALID_GOAL_FINALIZATION_HISTORY, failure.code)
    GoalFinalizationHistorySchemaValidator.validate(
      payload + ("completion" to (completion + ("post_writer_content_fingerprint" to "after-writer"))),
      "test",
    )
  }

  private fun repositorySchemaFile(): Path {
    var current: Path? = Path.of("").toAbsolutePath().normalize()
    while (current != null) {
      val candidate = current.resolve(GoalFinalizationHistorySchemaPaths.REPO_RELATIVE_PATH)
      if (Files.isRegularFile(candidate)) return candidate
      current = current.parent
    }
    error("canonical goal finalization history schema is missing")
  }
}
