package dev.skillbill.desktop.core.data.session

import dev.skillbill.desktop.core.common.DatabaseDispatcher
import dev.skillbill.desktop.core.domain.session.DatabaseSessionState
import dev.skillbill.desktop.core.domain.session.StorageFailure
import dev.skillbill.desktop.core.testing.FakeAppSettingsRepository
import dev.skillbill.desktop.core.testing.FakeSessionGraphFactory
import dev.skillbill.desktop.core.testing.RecordingDiagnostics
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.flow.update
import skillbill.error.core.DatabaseFailureCode
import skillbill.error.core.SkillBillRuntimeException
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertTrue

class SessionLifecycleTest {
  @Test
  fun `switching the database cancels the old session, releases its graph before opening the next, and classifies a missing store`() =
    runTest {
      val missing = "/missing.db"
      val factory = FakeSessionGraphFactory(mapOf(missing to SkillBillRuntimeException(DatabaseFailureCode.MISSING, "missing")))
      val settings = FakeAppSettingsRepository()
      val dispatcher = DatabaseDispatcher(StandardTestDispatcher(testScheduler))
      val lifecycle = SessionLifecycle(factory, settings, StorageFailureClassifier::classify, dispatcher, RecordingDiagnostics(), backgroundScope)
      val states = mutableListOf<DatabaseSessionState>()
      backgroundScope.launch(dispatcher.dispatcher) { lifecycle.state.toList(states) }
      lifecycle.start()
      runCurrent()
      val first = assertIs<DatabaseSessionState.Open>(lifecycle.state.value)
      val observation = checkNotNull(lifecycle.current.value).scope.launch { awaitCancellation() }

      settings.settings.update { it.copy(databasePath = missing) }
      runCurrent()

      assertTrue(observation.isCancelled)
      assertEquals(listOf("open 1", "release 1", "open 2", "release 2"), factory.events)
      assertEquals(
        listOf(DatabaseSessionState.Opening(missing), DatabaseSessionState.Failed(missing, StorageFailure.Missing)),
        states.takeLast(2),
      )

      settings.settings.update { it.copy(databasePath = null) }
      runCurrent()

      val reopened = assertIs<DatabaseSessionState.Open>(lifecycle.state.value)
      assertTrue(reopened.sessionId > first.sessionId)
    }
}
