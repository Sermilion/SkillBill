package skillbill.engine.operation.core

import skillbill.error.operation.DuplicateOperationIdError
import skillbill.error.operation.UnknownOperationIdError
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertSame

class OperationRegistryTest {
  @Test
  fun `registering one id twice fails with a typed error`() {
    val error =
      assertFailsWith<DuplicateOperationIdError> {
        OperationRegistry(listOf(StubOperation("release"), StubOperation("update-check"), StubOperation("release")))
      }

    assertEquals("release", error.operationId)
  }

  @Test
  fun `an unknown id fails with a typed error naming the registered ids`() {
    val release = StubOperation("release")
    val registry = OperationRegistry(listOf(StubOperation("update-check"), release))

    assertSame(release, registry.get("release"))
    val error = assertFailsWith<UnknownOperationIdError> { registry.get("deploy") }
    assertEquals(listOf("update-check", "release"), error.knownIds)
  }

  private class StubOperation(
    override val id: String,
  ) : Operation {
    override fun run(context: OperationContext): OperationRunResult =
      OperationRunResult.Finished(OperationOutcome.Completed(id))
  }
}
