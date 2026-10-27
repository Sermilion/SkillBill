package skillbill.infrastructure.contracts.workflow.goal

import com.fasterxml.jackson.dataformat.yaml.YAMLMapper
import skillbill.contracts.workflow.goal.GOAL_CHILD_HISTORY_EVIDENCE_CONTRACT_VERSION
import skillbill.error.core.SkillBillRuntimeException
import skillbill.infrastructure.contracts.locator.GoalChildHistoryEvidenceSchemaPaths
import java.nio.file.Files
import java.nio.file.Path
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

class GoalChildHistoryEvidenceSchemaContractTest {
  @Test
  fun `canonical and bundled schema pin the draft contract version`() {
    val source = YAMLMapper().readTree(Files.readString(repositorySchemaFile()))
    assertEquals(GoalChildHistoryEvidenceSchemaPaths.EXPECTED_SCHEMA_ID, source.path("\$id").asText())
    assertEquals(
      GOAL_CHILD_HISTORY_EVIDENCE_CONTRACT_VERSION,
      source.path("properties").path("contract_version").path("const").asText(),
    )
    val bundled =
      checkNotNull(javaClass.classLoader.getResourceAsStream(GoalChildHistoryEvidenceSchemaPaths.CLASSPATH_RESOURCE))
        .use { YAMLMapper().readTree(it) }
    assertEquals(source, bundled)
  }

  @Test
  fun `evidence schema rejects restored records without their discarded acceptance`() {
    val record =
      mapOf(
        "contract_version" to GOAL_CHILD_HISTORY_EVIDENCE_CONTRACT_VERSION,
        "parent_workflow_id" to "parent", "subtask_id" to 1, "child_workflow_id" to "child",
        "execution_plan" to null, "continuation" to emptyMap<String, Any?>(), "history_record" to null,
        "history_step_status" to null,
        "acceptance" to null, "restore_eligible" to false, "restored" to false,
      )
    GoalChildHistoryEvidenceSchemaValidator.validate(record, "test")
    assertFailsWith<SkillBillRuntimeException> {
      GoalChildHistoryEvidenceSchemaValidator.validate(record + ("restored" to true), "test")
    }
    assertFailsWith<SkillBillRuntimeException> {
      GoalChildHistoryEvidenceSchemaValidator.validate(record + ("contract_version" to "unsupported"), "test")
    }
  }

  private fun repositorySchemaFile(): Path {
    var current: Path? = Path.of("").toAbsolutePath().normalize()
    while (current != null) {
      val candidate = current.resolve(GoalChildHistoryEvidenceSchemaPaths.REPO_RELATIVE_PATH)
      if (Files.isRegularFile(candidate)) return candidate
      current = current.parent
    }
    error("canonical goal-child history evidence schema is missing")
  }
}
