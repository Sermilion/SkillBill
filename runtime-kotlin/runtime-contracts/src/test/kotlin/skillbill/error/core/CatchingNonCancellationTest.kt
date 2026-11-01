package skillbill.error.core

import kotlin.coroutines.cancellation.CancellationException
import kotlin.test.Test
import kotlin.test.assertFailsWith
import kotlin.test.assertSame

class CatchingNonCancellationTest {
  @Test
  fun `ordinary failures remain in the result`() {
    val failure = IllegalStateException("ordinary failure")

    assertSame(failure, catchingNonCancellation { throw failure }.exceptionOrNull())
  }

  @Test
  fun `cancellation is rethrown unchanged`() {
    val failure = CancellationException("cancelled")

    assertSame(failure, assertFailsWith<CancellationException> { catchingNonCancellation { throw failure } })
  }

  @Test
  fun `interruption is rethrown unchanged`() {
    val failure = InterruptedException("interrupted")

    assertSame(failure, assertFailsWith<InterruptedException> { catchingNonCancellation { throw failure } })
  }
}
