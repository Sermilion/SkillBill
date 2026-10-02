package skillbill.workflow.model

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class ClosedStatusTypesTest {
  @Test
  fun `decomposition status preserves completed alias and rejects unknown values`() {
    assertEquals(DecompositionStatus.COMPLETE, DecompositionStatus.fromWire("completed"))
    assertEquals("complete", DecompositionStatus.COMPLETE.wireValue)
    assertNull(DecompositionStatus.fromWire("future_status"))
  }

  @Test
  fun `workflow status vocabularies keep workflow and step states distinct`() {
    assertEquals(WorkflowStatus.COMPLETED, WorkflowStatus.fromWire("completed"))
    assertEquals(WorkflowStepStatus.COMPLETED, WorkflowStepStatus.fromWire("completed"))
    assertNull(WorkflowStatus.fromWire("skipped"))
  }
}
