package dev.skillbill.desktop.core.data.runtime

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.cancel
import kotlinx.coroutines.yield
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import java.util.concurrent.CountDownLatch

class RuntimeReadsTest {
  @Test
  fun `canceled caller waits until its session-owned blocking operation exits`() = runTest {
    val sessionScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    val entered = CompletableDeferred<Unit>()
    val finish = CountDownLatch(1)
    val caller = async {
      runSessionOwnedCall(sessionScope, Dispatchers.IO) {
        entered.complete(Unit)
        finish.await()
      }
    }
    entered.await()

    caller.cancel()
    yield()
    assertFalse(caller.isCompleted)

    finish.countDown()
    caller.join()
    assertTrue(caller.isCompleted)
    sessionScope.cancel()
  }
}
