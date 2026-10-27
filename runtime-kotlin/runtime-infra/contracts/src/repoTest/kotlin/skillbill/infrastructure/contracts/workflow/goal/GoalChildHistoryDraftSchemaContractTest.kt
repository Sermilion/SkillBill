package skillbill.infrastructure.contracts.workflow.goal

import com.fasterxml.jackson.dataformat.yaml.YAMLMapper
import skillbill.contracts.workflow.goal.GOAL_CHILD_HISTORY_DRAFT_CONTRACT_VERSION
import skillbill.error.core.SkillBillRuntimeException
import skillbill.error.shellcontent.WorkflowFailureCode
import skillbill.goalrunner.model.history.GoalChildHistoryDraftPayloadKeys
import skillbill.infrastructure.contracts.locator.GoalChildHistoryDraftSchemaPaths
import java.nio.file.Files
import java.nio.file.Path
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

class GoalChildHistoryDraftSchemaContractTest {
  private val keys = GoalChildHistoryDraftPayloadKeys

  @Test
  fun `canonical and bundled schema pin the draft contract version`() {
    val source = YAMLMapper().readTree(Files.readString(repositorySchemaFile()))
    assertEquals(GoalChildHistoryDraftSchemaPaths.EXPECTED_SCHEMA_ID, source.path("\$id").asText())
    assertEquals(
      GOAL_CHILD_HISTORY_DRAFT_CONTRACT_VERSION,
      source.path("properties").path("contract_version").path("const").asText(),
    )
    val bundled =
      checkNotNull(javaClass.classLoader.getResourceAsStream(GoalChildHistoryDraftSchemaPaths.CLASSPATH_RESOURCE))
        .use { YAMLMapper().readTree(it) }
    assertEquals(source, bundled)
  }

  @Test
  fun `schema accepts a complete draft and rejects a skip carrying draft fields`() {
    GoalChildHistoryDraftSchemaValidator.validate(
      mapOf(
        keys.CONTRACT_VERSION to GOAL_CHILD_HISTORY_DRAFT_CONTRACT_VERSION,
        keys.PARENT_WORKFLOW_ID to "parent",
        keys.SUBTASK_ID to 1,
        keys.CHILD_WORKFLOW_ID to "child",
        keys.DISPOSITION to "draft",
        keys.BOUNDARY to "runtime",
        keys.HISTORY to "Keep the accepted output durable.",
        keys.DECISIONS to "Store on the parent ledger.",
      ),
      "test",
    )
    val failure =
      assertFailsWith<SkillBillRuntimeException> {
        GoalChildHistoryDraftSchemaValidator.validate(
          mapOf(
            keys.CONTRACT_VERSION to GOAL_CHILD_HISTORY_DRAFT_CONTRACT_VERSION,
            keys.PARENT_WORKFLOW_ID to "parent",
            keys.SUBTASK_ID to 1,
            keys.CHILD_WORKFLOW_ID to "child",
            keys.DISPOSITION to "skip",
            keys.BOUNDARY to "runtime",
            keys.SKIP_REASON to "No durable learning.",
          ),
          "test",
        )
      }
    assertEquals(WorkflowFailureCode.INVALID_GOAL_CHILD_HISTORY_DRAFT, failure.code)
  }

  private fun repositorySchemaFile(): Path {
    var current: Path? = Path.of("").toAbsolutePath().normalize()
    while (current != null) {
      val candidate = current.resolve(GoalChildHistoryDraftSchemaPaths.REPO_RELATIVE_PATH)
      if (Files.isRegularFile(candidate)) return candidate
      current = current.parent
    }
    error("canonical goal-child history draft schema is missing")
  }
}
