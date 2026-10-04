package skillbill.contracts.decomposition

import skillbill.contracts.decomposition.DecompositionPlanningPayloadKeys.MODE
import skillbill.contracts.decomposition.DecompositionPlanningPayloadKeys.STACK_BRANCHES
import skillbill.contracts.decomposition.DecompositionPlanningPayloadKeys.SUBTASKS
import skillbill.error.core.SkillBillRuntimeException
import skillbill.error.shellcontent.WorkflowFailureCode
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

class DecompositionPlanningContractsTest {
  @Test
  fun `present wrong type stack branches fails before manifest write`() {
    val error =
      assertFailsWith<SkillBillRuntimeException> {
        DecompositionPlanningResult.fromWireMap(
          wireMap =
            mapOf(
              MODE to "decompose",
              SUBTASKS to
                listOf(
                  mapOf(
                    "id" to 1,
                    "name" to "one",
                    "spec_path" to "spec.md",
                  ),
                ),
              STACK_BRANCHES to "invalid",
            ),
          sourceLabel = "planning-result",
        )
      }
    assertEquals(WorkflowFailureCode.DECOMPOSITION_MANIFEST_INVALID_SHAPE, error.code)
  }

  @Test
  fun `null stack branches retains the optional empty-list meaning`() {
    val result =
      DecompositionPlanningResult.fromWireMap(
        wireMap =
          mapOf(
            MODE to "decompose",
            SUBTASKS to
              listOf(
                mapOf(
                  "id" to 1,
                  "name" to "one",
                  "spec_path" to "spec.md",
                ),
              ),
            STACK_BRANCHES to null,
          ),
      )

    assertEquals(emptyList(), result.stackBranches)
  }
}
