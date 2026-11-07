package skillbill.engine.work.verify

import skillbill.engine.operation.verify.VerifyWorkflow
import kotlin.test.Test
import kotlin.test.assertEquals

class VerifyRunDetailsProjectionTest {
  @Test
  fun `projects stored spec file and directory paths as spec intake`() {
    assertEquals(
      "spec:docs/feature.md",
      VerifyRunDetailsProjection.intake(mapOf(VerifyWorkflow.SPEC_PATH to "docs/feature.md")),
    )
    assertEquals(
      "spec:docs/feature",
      VerifyRunDetailsProjection.intake(
        mapOf(VerifyWorkflow.SPEC_PATH to "docs/feature", VerifyWorkflow.INTAKE to "ignored text"),
      ),
    )
  }

  @Test
  fun `preserves stored text intake unchanged`() {
    val text = "  Requirements\nsecond line  "

    assertEquals(text, VerifyRunDetailsProjection.intake(mapOf(VerifyWorkflow.INTAKE to text)))
  }
}
