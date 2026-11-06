package dev.skillbill.desktop.core.data.repository

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals

@OptIn(ExperimentalCoroutinesApi::class)
class SpecTreeReadsTest {
  @Test
  fun `changes during a read do not prevent the first tree from appearing`() = runTest {
    val changes = MutableSharedFlow<Unit>(extraBufferCapacity = 1)
    val finishFirstRead = CompletableDeferred<Unit>()
    val results = mutableListOf<Int>()
    var reads = 0
    backgroundScope.launch {
      specTreeReads(changes) {
        val read = ++reads
        if (read == 1) finishFirstRead.await()
        read
      }.collect { results += it }
    }
    runCurrent()
    changes.emit(Unit)
    runCurrent()
    changes.emit(Unit)
    runCurrent()
    finishFirstRead.complete(Unit)
    runCurrent()
    assertEquals(listOf(1, 2), results)
  }
}
