package skillbill.infrastructure.host

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertSame

private const val CAP = 3

class JdkBoundedWorkFanOutPortTest {
  @Test
  fun `a unit that raises fails at its own index while its peers still return values`() {
    val port = JdkBoundedWorkFanOutPort()
    val failure = IllegalStateException("second unit could not run")
    val units = listOf<() -> String>({ "first" }, { throw failure }, { "third" })

    val results = port.runBounded(CAP, units)

    assertEquals("first", results[0].getOrThrow())
    assertSame(failure, results[1].exceptionOrNull())
    assertEquals("third", results[2].getOrThrow())
  }
}
