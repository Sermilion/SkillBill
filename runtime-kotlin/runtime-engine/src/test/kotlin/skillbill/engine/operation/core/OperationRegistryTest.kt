package skillbill.engine.operation.core

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNull
import kotlin.test.assertSame

class OperationRegistryTest {
  @Test
  fun `registering one id twice fails the wiring check`() {
    val error =
      assertFailsWith<IllegalArgumentException> {
        OperationRegistry(listOf(StubOperation("release"), StubOperation("update-check"), StubOperation("release")))
      }

    assertEquals("Operation 'release' is registered more than once.", error.message)
  }

  @Test
  fun `an unknown id finds nothing and the registry still names the registered ids`() {
    val release = StubOperation("release")
    val registry = OperationRegistry(listOf(StubOperation("update-check"), release))

    assertSame(release, registry.find("release"))
    assertNull(registry.find("deploy"))
    assertEquals(listOf("update-check", "release"), registry.ids)
  }

  private class StubOperation(
    override val id: String,
  ) : Operation {
    override fun run(context: OperationContext): OperationRunResult =
      OperationRunResult.Finished(OperationOutcome.Completed(id))
  }
}
